package io.github.marishasahay.agentic.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marishasahay.agentic.agents.llm.LlmMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalModesTest {

  @Test
  void liveRunsWithoutATerminalNeverFallBackToAutomaticApproval() {
    for (LlmMode mode : new LlmMode[] {LlmMode.LIVE, LlmMode.RECORD}) {
      assertThatThrownBy(() -> ApprovalModes.resolve(Optional.empty(), null, mode, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No reviewer available");
      assertThatThrownBy(() -> ApprovalModes.resolve(Optional.empty(), "scripted", mode, false))
          .as("a scenario default applies to replays only").hasMessageContaining("No reviewer");
    }
    assertThat(ApprovalModes.resolve(Optional.empty(), null, LlmMode.LIVE, true))
        .isEqualTo("interactive");
    assertThat(ApprovalModes.resolve(Optional.of("scripted"), null, LlmMode.LIVE, false))
        .isEqualTo("scripted");
    assertThat(ApprovalModes.resolve(Optional.empty(), "scripted", LlmMode.REPLAY, false))
        .isEqualTo("scripted");
  }

  @Test
  void unknownApprovalModesAreRejected() {
    assertThatThrownBy(() -> ApprovalModes.parse("auto"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown approval mode 'auto'");
    assertThatThrownBy(() -> ApprovalModes.resolve(Optional.of("yes"), null, LlmMode.REPLAY,
        true)).isInstanceOf(IllegalArgumentException.class);
    assertThat(ApprovalModes.parse(" Interactive ")).isEqualTo("interactive");
  }
}
