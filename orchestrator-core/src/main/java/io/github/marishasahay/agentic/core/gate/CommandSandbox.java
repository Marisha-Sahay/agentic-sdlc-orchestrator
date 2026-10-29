package io.github.marishasahay.agentic.core.gate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Where gate commands run. Builds and tests execute code the agents wrote, so this, not the
 * git workspace, is the execution-isolation boundary.
 */
public interface CommandSandbox {

  /**
   * Starts a command.
   *
   * @param command command and arguments
   * @param workdir workspace directory the command operates on
   * @param log file receiving stdout and stderr
   * @return the running execution; closing it kills the process and removes what it created
   * @throws IOException if the command cannot be started
   */
  Execution start(List<String> command, Path workdir, Path log) throws IOException;

  /**
   * What isolation this sandbox provides, recorded in the audit log with every gate result.
   *
   * @return description
   */
  String describe();

  /** A started command. */
  interface Execution extends AutoCloseable {

    Process process();

    /** Kills the process tree and removes containers or other resources it created. */
    @Override
    void close();
  }

  /**
   * The default: a host process whose environment is reduced to an allow-list.
   *
   * @return host sandbox
   */
  static CommandSandbox host() {
    return HostSandbox.scrubbed();
  }
}
