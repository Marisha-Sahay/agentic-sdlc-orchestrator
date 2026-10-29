package io.github.marishasahay.agentic.core.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs gate commands in a throwaway container: only the workspace is mounted (plus a dedicated
 * dependency cache volume, never the host's {@code ~/.m2}), no environment from the host, no
 * capabilities, CPU, memory and process limits, and no network unless explicitly enabled. The
 * host Docker socket is never mounted, so integration tests cannot use Testcontainers inside;
 * instead an optional PostgreSQL sidecar is started on a private internal network and its URL
 * is passed in {@code IT_DATABASE_URL}.
 *
 * <p>Offline mode needs the dependency cache to be warmed once with {@link Network#ONLINE} on
 * trusted (human-reviewed) projects; an offline build that needs an artifact not in the cache
 * fails visibly rather than reaching the network.
 *
 * @param image worker image (JDK and Maven)
 * @param cpus CPU limit, e.g. {@code 2}
 * @param memory memory limit, e.g. {@code 3g}
 * @param pids process limit
 * @param cacheVolume named volume holding the Maven repository
 * @param network whether the worker may reach the internet
 * @param databaseImage PostgreSQL image for the sidecar, or null for none
 */
public record DockerSandbox(String image, String cpus, String memory, int pids,
    String cacheVolume, Network network, String databaseImage) implements CommandSandbox {

  /** Network access of the worker. */
  public enum Network {
    /** No route out (an internal network that only reaches the database sidecar, if any). */
    OFFLINE,
    /** Internet access; only for warming the dependency cache on trusted code. */
    ONLINE
  }

  static final String DB_USER = "gate";
  static final String DB_NAME = "gate";

  /**
   * Defaults used by the CLI.
   *
   * @param network network access
   * @return the sandbox
   */
  public static DockerSandbox standard(Network network) {
    return new DockerSandbox("maven:3.9.11-eclipse-temurin-21", "2", "3g", 512,
        "agentic-gate-m2", network, "postgres:17-alpine");
  }

  @Override
  public Execution start(List<String> command, Path workdir, Path log) throws IOException {
    String id = "agentic-gate-" + UUID.randomUUID().toString().substring(0, 8);
    List<String> cleanup = new ArrayList<>();
    try {
      String networkName = id + "-net";
      List<String> create = new ArrayList<>(List.of("docker", "network", "create"));
      if (network == Network.OFFLINE) {
        create.add("--internal");
      }
      create.add(networkName);
      docker(create);
      cleanup.add("network:" + networkName);
      String password = UUID.randomUUID().toString();
      if (databaseImage != null) {
        docker(List.of("docker", "run", "-d", "--name", id + "-db", "--network", networkName,
            "--memory", "512m", "-e", "POSTGRES_USER=" + DB_USER, "-e",
            "POSTGRES_PASSWORD=" + password, "-e", "POSTGRES_DB=" + DB_NAME, databaseImage));
        cleanup.add("container:" + id + "-db");
        awaitDatabase(id + "-db");
      }
      List<String> run = new ArrayList<>(List.of("docker", "run", "--rm", "--name", id,
          "--network", networkName, "--cpus", cpus, "--memory", memory,
          "--pids-limit", String.valueOf(pids), "--cap-drop", "ALL",
          "--security-opt", "no-new-privileges", "-v", workdir.toAbsolutePath() + ":/workspace",
          "-w", "/workspace", "-v", cacheVolume + ":/root/.m2"));
      if (databaseImage != null) {
        run.addAll(List.of("-e", "IT_DATABASE_URL=jdbc:postgresql://" + id + "-db:5432/"
            + DB_NAME, "-e", "IT_DATABASE_USER=" + DB_USER, "-e",
            "IT_DATABASE_PASSWORD=" + password));
      }
      run.add(image);
      run.addAll(containerCommand(command));
      cleanup.add(0, "container:" + id);
      Process process = new ProcessBuilder(run).redirectErrorStream(true)
          .redirectOutput(log.toFile()).start();
      return new Execution() {
        @Override
        public Process process() {
          return process;
        }

        @Override
        public void close() {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          remove(cleanup);
        }
      };
    } catch (IOException | RuntimeException e) {
      remove(cleanup);
      throw e;
    }
  }

  /** Maven runs offline in an offline sandbox, so a missing dependency fails fast. */
  List<String> containerCommand(List<String> command) {
    List<String> result = new ArrayList<>(command);
    if (network == Network.OFFLINE && !result.isEmpty() && result.getFirst().equals("mvn")) {
      result.add(1, "-o");
    }
    return result;
  }

  @Override
  public String describe() {
    return "docker container " + image + " (cpus " + cpus + ", memory " + memory + ", pids "
        + pids + ", no capabilities, network " + network.name().toLowerCase()
        + (databaseImage == null ? "" : ", private PostgreSQL sidecar " + databaseImage)
        + "; mounts: workspace and the " + cacheVolume + " dependency cache only; no host "
        + "environment, no Docker socket)";
  }

  private void awaitDatabase(String container) throws IOException {
    for (int i = 0; i < 60; i++) {
      if (exec(List.of("docker", "exec", container, "pg_isready", "-U", DB_USER, "-d", DB_NAME))
          == 0) {
        return;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while waiting for the database sidecar", e);
      }
    }
    throw new IOException("database sidecar " + container + " did not become ready in 30 s");
  }

  /** Containers first: a network cannot be removed while a container is attached. */
  private static void remove(List<String> resources) {
    List<String> ordered = new ArrayList<>(resources);
    ordered.sort((a, b) -> Boolean.compare(a.startsWith("network:"), b.startsWith("network:")));
    for (String resource : ordered) {
      String[] kind = resource.split(":", 2);
      try {
        exec(kind[0].equals("network") ? List.of("docker", "network", "rm", kind[1])
            : List.of("docker", "rm", "-f", kind[1]));
      } catch (IOException e) {
        // best effort; leftovers are named agentic-gate-* for manual cleanup
      }
    }
  }

  private static void docker(List<String> command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (waitFor(process) != 0) {
      throw new IOException(String.join(" ", command.subList(0, 3)) + " failed: " + output);
    }
  }

  private static int exec(List<String> command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    return waitFor(process);
  }

  private static int waitFor(Process process) throws IOException {
    try {
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("docker command timed out");
      }
      return process.exitValue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }
}
