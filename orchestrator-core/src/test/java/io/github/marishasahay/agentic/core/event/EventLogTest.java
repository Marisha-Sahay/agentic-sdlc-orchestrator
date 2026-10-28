package io.github.marishasahay.agentic.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.policy.PolicyFinding;
import io.github.marishasahay.agentic.core.policy.Severity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventLogTest {

  @TempDir
  Path tmp;

  @Test
  void persistsTypedEventsAsAHashChainedJsonLinesFile() {
    Path file = tmp.resolve("events.jsonl");
    EventLog log = new EventLog("run-1", file, Clock.systemUTC());
    log.append(new RunEvent.RunStarted("greenfield", "abc", 3, Map.of("k", "v")));
    log.append(new RunEvent.PolicyEvaluated("impl", 1, List.of(
        new PolicyFinding("secret-leak", Severity.BLOCK, "key found", "src/A.java"))));
    log.append(new RunEvent.RunFinished("SUCCEEDED", ""));

    List<EventEnvelope> reread = EventLog.readFile(file);

    assertThat(reread).hasSize(3);
    assertThat(reread.get(1).event()).isInstanceOf(RunEvent.PolicyEvaluated.class);
    assertThat(reread.get(1).event().nodeId()).isEqualTo("impl");
    assertThat(reread.getFirst().prevHash()).isEqualTo(EventLog.GENESIS);
    assertThat(reread.get(1).prevHash()).isEqualTo(reread.getFirst().hash());
    assertThat(EventLog.verifyChain(reread)).isEmpty();
  }

  @Test
  void detectsTamperingWithAnyEarlierEvent() throws Exception {
    Path file = tmp.resolve("events.jsonl");
    EventLog log = new EventLog("run-1", file, Clock.systemUTC());
    log.append(new RunEvent.ApprovalResolved("release", false, "carol", "not yet", List.of()));
    log.append(new RunEvent.RunFinished("FAILED", "rejected"));

    List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    lines.set(0, lines.get(0).replace("\"approved\":false", "\"approved\":true"));
    Files.write(file, lines, StandardCharsets.UTF_8);

    assertThat(EventLog.verifyChain(EventLog.readFile(file))).contains(1L);
  }

  @Test
  void reopeningALogContinuesTheChain() {
    Path file = tmp.resolve("events.jsonl");
    new EventLog("run-1", file, Clock.systemUTC()).append(new RunEvent.RunResumed(0));
    EventLog reopened = new EventLog("run-1", file, Clock.systemUTC());
    EventEnvelope second = reopened.append(new RunEvent.RunResumed(1));

    assertThat(second.seq()).isEqualTo(2);
    assertThat(EventLog.verifyChain(reopened.events())).isEmpty();
  }

  @Test
  void subscribersSeeEveryEventAndCannotBreakTheLog() {
    EventLog log = EventLog.inMemory("run-1", Clock.systemUTC());
    List<Long> seen = new ArrayList<>();
    log.subscribe(e -> {
      throw new IllegalStateException("broken listener");
    });
    log.subscribe(e -> seen.add(e.seq()));

    log.append(new RunEvent.StopRequested("test"));
    log.append(new RunEvent.RunFinished("PAUSED", "test"));

    assertThat(seen).containsExactly(1L, 2L);
  }
}
