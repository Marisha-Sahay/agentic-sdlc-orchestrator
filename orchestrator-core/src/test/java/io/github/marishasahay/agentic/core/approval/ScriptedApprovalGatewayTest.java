package io.github.marishasahay.agentic.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway.ScriptedDecision;
import io.github.marishasahay.agentic.core.graph.Stage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScriptedApprovalGatewayTest {

  private static ApprovalRequest request(String node, ApprovalKind kind, String... codes) {
    return new ApprovalRequest("run", node, Stage.IMPLEMENTATION, kind, "summary", List.of(),
        Set.of(codes), List.of(), Map.of(), "", List.of(), "proposal", null, List.of());
  }

  @Test
  void decidesOnlyWhatItsNextDecisionCoversAndFailsClosedOnAnythingElse() {
    ScriptedApprovalGateway gateway = ScriptedApprovalGateway.failClosed().script("impl",
        ScriptedDecision.acceptance(ApprovalDecision.approve("demo", "ok"), "sign-off"));

    assertThatThrownBy(() -> gateway.decide(request("impl", ApprovalKind.ACCEPTANCE,
        "sign-off", "schema-migration")))
        .isInstanceOf(ReviewerUnavailableException.class)
        .hasMessageContaining("does not cover acceptance of node impl with reasons "
            + "[schema-migration, sign-off]");
    assertThatThrownBy(() -> gateway.decide(request("impl", ApprovalKind.AUTHORIZATION,
        "sign-off"))).isInstanceOf(ReviewerUnavailableException.class);
    assertThat(gateway.decide(request("impl", ApprovalKind.ACCEPTANCE, "sign-off")).approved())
        .isTrue();
    assertThatThrownBy(() -> gateway.decide(request("impl", ApprovalKind.ACCEPTANCE,
        "sign-off")))
        .isInstanceOf(ReviewerUnavailableException.class)
        .hasMessageContaining("no scripted decision left");
    assertThatThrownBy(() -> gateway.decide(request("other", ApprovalKind.ACCEPTANCE,
        "sign-off"))).isInstanceOf(ReviewerUnavailableException.class);
    assertThat(gateway.received()).hasSize(5);
  }
}
