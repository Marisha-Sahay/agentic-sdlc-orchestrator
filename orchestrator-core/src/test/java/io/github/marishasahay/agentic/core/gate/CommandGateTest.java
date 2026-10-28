package io.github.marishasahay.agentic.core.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.Stage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandGateTest {

  private static final NodeSpec NODE = NodeSpec.builder("verify", Stage.VERIFICATION)
      .task(ctx -> NodeResult.builder().build()).build();

  @TempDir
  Path workspace;

  private GateContext context(Path root) {
    return new GateContext(NODE, ArtifactStore.inMemory(), Map.of(), root);
  }

  @Test
  void passesOnExitZeroAndKeepsTheOutputTailAsEvidenceOnFailure() {
    GateResult pass = CommandGate.of("ok", Duration.ofSeconds(10), "sh", "-c", "echo fine")
        .evaluate(context(workspace));
    GateResult fail = CommandGate.of("build", Duration.ofSeconds(10), "sh", "-c",
        "echo 'App.java:3: error: ; expected'; exit 1").evaluate(context(workspace));

    assertThat(pass.passed()).isTrue();
    assertThat(fail.passed()).isFalse();
    assertThat(fail.evidence()).contains("exit 1").contains("App.java:3: error");
  }

  @Test
  void killsCommandsThatExceedTheirTimeout() {
    GateResult result = CommandGate.of("hang", Duration.ofMillis(200), "sleep", "5")
        .evaluate(context(workspace));

    assertThat(result.passed()).isFalse();
    assertThat(result.evidence()).contains("timed out");
  }

  @Test
  void failsClosedWithoutAWorkspaceOrWhenTheToolIsMissing() {
    CommandGate gate = CommandGate.of("tool", Duration.ofSeconds(5), "definitely-not-a-binary");

    assertThat(gate.evaluate(context(null)).passed()).isFalse();
    assertThat(gate.evaluate(context(workspace)).evidence()).contains("could not run");
    assertThat(gate.usesWorkspace()).isTrue();
  }

  @Test
  void requiredArtifactsGateReportsWhatIsMissing() {
    GateContext ctx = new GateContext(NODE, ArtifactStore.inMemory(),
        Map.of("design.doc", "# Design", "plan", " "), null);

    GateResult result = RequiredArtifactsGate.of("design.doc", "plan", "openapi").evaluate(ctx);

    assertThat(result.passed()).isFalse();
    assertThat(result.evidence()).contains("[plan, openapi]");
  }
}
