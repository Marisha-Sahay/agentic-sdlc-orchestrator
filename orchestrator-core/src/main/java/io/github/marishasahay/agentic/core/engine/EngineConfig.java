package io.github.marishasahay.agentic.core.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine limits. The budgets are the "bounded autonomy" knobs: whichever is hit first engages a
 * safe stop.
 *
 * @param maxParallelNodes concurrent node attempts (bounds model concurrency and cost)
 * @param maxRunDuration wall-clock budget for the whole run
 * @param maxTotalAttempts attempt budget across all nodes
 * @param maxAgentCalls model-call budget across all nodes
 * @param maxFailedNodes failed nodes tolerated before the run safe-stops (1 = fail fast)
 * @param killSwitch file whose presence engages a safe stop, or null
 * @param pollInterval how often the scheduler re-checks stop conditions while waiting
 */
public record EngineConfig(
    int maxParallelNodes,
    Duration maxRunDuration,
    int maxTotalAttempts,
    int maxAgentCalls,
    int maxFailedNodes,
    Path killSwitch,
    Duration pollInterval) {

  public static EngineConfig defaults() {
    return new EngineConfig(4, Duration.ofMinutes(45), 60, 120, 1, null, Duration.ofMillis(200));
  }

  public EngineConfig withKillSwitch(Path file) {
    return new EngineConfig(maxParallelNodes, maxRunDuration, maxTotalAttempts, maxAgentCalls,
        maxFailedNodes, file, pollInterval);
  }

  public EngineConfig withMaxParallelNodes(int value) {
    return new EngineConfig(value, maxRunDuration, maxTotalAttempts, maxAgentCalls,
        maxFailedNodes, killSwitch, pollInterval);
  }

  public EngineConfig withMaxTotalAttempts(int value) {
    return new EngineConfig(maxParallelNodes, maxRunDuration, value, maxAgentCalls,
        maxFailedNodes, killSwitch, pollInterval);
  }

  public EngineConfig withMaxRunDuration(Duration value) {
    return new EngineConfig(maxParallelNodes, value, maxTotalAttempts, maxAgentCalls,
        maxFailedNodes, killSwitch, pollInterval);
  }

  public EngineConfig withMaxFailedNodes(int value) {
    return new EngineConfig(maxParallelNodes, maxRunDuration, maxTotalAttempts, maxAgentCalls,
        value, killSwitch, pollInterval);
  }

  /** Settings as recorded in the audit log. */
  public Map<String, String> describe() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("maxParallelNodes", String.valueOf(maxParallelNodes));
    settings.put("maxRunDuration", maxRunDuration.toString());
    settings.put("maxTotalAttempts", String.valueOf(maxTotalAttempts));
    settings.put("maxAgentCalls", String.valueOf(maxAgentCalls));
    settings.put("maxFailedNodes", String.valueOf(maxFailedNodes));
    settings.put("killSwitch", killSwitch == null ? "none"
        : killSwitch.getFileName() + " in the run directory");
    return settings;
  }
}
