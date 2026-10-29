package io.github.marishasahay.agentic.core.gate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs gate commands as host processes with an allow-listed environment, so model API keys and
 * other secrets in the orchestrator's environment never reach generated code. This is the only
 * isolation it provides: the process can still read the user's files, use the network and
 * consume unbounded CPU and memory (bounded only by the gate's timeout). Use
 * {@link DockerSandbox} for execution isolation.
 *
 * @param allowedVariables variables passed through unchanged
 * @param allowedPrefixes prefixes of variables passed through (e.g. {@code TESTCONTAINERS_})
 */
public record HostSandbox(Set<String> allowedVariables, List<String> allowedPrefixes)
    implements CommandSandbox {

  public HostSandbox {
    allowedVariables = Set.copyOf(allowedVariables);
    allowedPrefixes = List.copyOf(allowedPrefixes);
  }

  /**
   * The environment a JDK, Maven and Testcontainers need, and nothing else.
   *
   * @return the sandbox
   */
  public static HostSandbox scrubbed() {
    return new HostSandbox(Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL",
        "LC_CTYPE", "TMPDIR", "TZ", "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "MAVEN_OPTS",
        "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY"),
        List.of("TESTCONTAINERS_"));
  }

  @Override
  public Execution start(List<String> command, Path workdir, Path log) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(workdir.toFile())
        .redirectErrorStream(true).redirectOutput(log.toFile());
    filter(builder.environment());
    Process process = builder.start();
    return new Execution() {
      @Override
      public Process process() {
        return process;
      }

      @Override
      public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    };
  }

  /**
   * Removes every variable that is not allow-listed.
   *
   * @param environment mutable environment
   */
  void filter(Map<String, String> environment) {
    environment.keySet().removeIf(name -> !allowedVariables.contains(name)
        && allowedPrefixes.stream().noneMatch(name::startsWith));
  }

  @Override
  public String describe() {
    return "host process; environment reduced to " + new TreeSet<>(allowedVariables) + " and "
        + allowedPrefixes + "*; NOT isolated: filesystem, network and resources are the host's";
  }
}
