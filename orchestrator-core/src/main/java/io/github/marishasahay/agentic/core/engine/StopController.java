package io.github.marishasahay.agentic.core.engine;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Safe-stop switch. Once engaged the scheduler starts no new attempts; attempts already running
 * finish (their workspace changes are atomic under the workspace lock), and the run ends PAUSED
 * with a resumable log. The first reason wins.
 */
public final class StopController {

  private final AtomicReference<String> reason = new AtomicReference<>();

  /**
   * Engages the stop.
   *
   * @param why reason recorded in the audit log
   * @return true if this call engaged it, false if it was already engaged
   */
  public boolean requestStop(String why) {
    return reason.compareAndSet(null, why);
  }

  public boolean isStopRequested() {
    return reason.get() != null;
  }

  public String reason() {
    return reason.get();
  }
}
