package io.github.marishasahay.agentic.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.RunEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

  /** Clock the test advances explicitly, so every duration is exact. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-10-28T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void derivesReliabilityMetricsFromTheEventLog() {
    ManualClock clock = new ManualClock();
    EventLog log = EventLog.inMemory("run-m", clock);
    log.append(new RunEvent.RunStarted("brownfield", "sha", 3, Map.of()));
    log.append(new RunEvent.NodeStarted("design", "DESIGN", 1, "primary"));
    clock.advance(Duration.ofSeconds(2));
    log.append(new RunEvent.ApprovalRequested("design", List.of("mandatory")));
    clock.advance(Duration.ofSeconds(30));
    log.append(new RunEvent.ApprovalResolved("design", true, "alice", "ok", List.of()));
    log.append(new RunEvent.NodeSucceeded("design", "DESIGN", 1, Map.of(), Map.of()));
    log.append(new RunEvent.NodeStarted("impl", "IMPLEMENTATION", 1, "primary"));
    log.append(new RunEvent.AgentCalled("impl", "coder", "model-x", "replay", 900, 1000, 200));
    clock.advance(Duration.ofSeconds(4));
    log.append(new RunEvent.GateEvaluated("impl", 1, "exit", "maven-compile", false, "error"));
    log.append(new RunEvent.RollbackPerformed("impl", "abc", "gate failed"));
    log.append(new RunEvent.AttemptFailed("impl", 1, "gate-failed", "error"));
    clock.advance(Duration.ofSeconds(6));
    log.append(new RunEvent.NodeStarted("impl", "IMPLEMENTATION", 2, "primary"));
    log.append(new RunEvent.NodeSucceeded("impl", "IMPLEMENTATION", 2, Map.of(), Map.of()));
    log.append(new RunEvent.NodeFailed("docs", "DOCUMENTATION", "model down"));
    log.append(new RunEvent.NodeSkipped("release", "upstream docs"));
    log.append(new RunEvent.RunFinished("FAILED", "docs"));

    RunMetrics metrics = MetricsCalculator.compute(log.events());

    assertThat(metrics.scenario()).isEqualTo("brownfield");
    assertThat(metrics.status()).isEqualTo("FAILED");
    assertThat(metrics.endToEndMs()).isEqualTo(42_000);
    assertThat(metrics.humanWaitMs()).isEqualTo(30_000);
    assertThat(metrics.machineMs()).isEqualTo(12_000);
    assertThat(metrics.nodesSucceeded()).isEqualTo(2);
    assertThat(metrics.nodesFailed()).isEqualTo(1);
    assertThat(metrics.nodesSkipped()).isEqualTo(1);
    assertThat(metrics.nodeSuccessRate()).isEqualTo(2.0 / 3);
    assertThat(metrics.firstPassYield()).isEqualTo(0.5);
    assertThat(metrics.attempts()).isEqualTo(3);
    assertThat(metrics.retries()).isEqualTo(1);
    assertThat(metrics.retryRate()).isEqualTo(1.0 / 3);
    assertThat(metrics.rollbacks()).isEqualTo(1);
    assertThat(metrics.gateFailures()).isEqualTo(1);
    assertThat(metrics.mttrMs()).isEqualTo(6_000);
    assertThat(metrics.agentCalls()).isEqualTo(1);
    assertThat(metrics.inputTokens()).isEqualTo(1000);
    assertThat(metrics.stageDurationsMs()).containsEntry("DESIGN", 32_000L)
        .containsEntry("IMPLEMENTATION", 10_000L);
    assertThat(metrics.auditChainValid()).isTrue();
  }

  @Test
  void aggregatesAcrossRuns() {
    EventLog ok = EventLog.inMemory("a", Clock.systemUTC());
    ok.append(new RunEvent.RunFinished("SUCCEEDED", ""));
    EventLog failed = EventLog.inMemory("b", Clock.systemUTC());
    failed.append(new RunEvent.NodeStarted("x", "TESTING", 1, "primary"));
    failed.append(new RunEvent.RunFinished("FAILED", "x"));

    Map<String, Object> aggregate = MetricsCalculator.aggregate(List.of(
        MetricsCalculator.compute(ok.events()), MetricsCalculator.compute(failed.events())));

    assertThat(aggregate).containsEntry("runs", 2).containsEntry("runSuccessRate", 0.5)
        .containsEntry("attempts", 1).containsEntry("meanMttrMs", -1L);
  }
}
