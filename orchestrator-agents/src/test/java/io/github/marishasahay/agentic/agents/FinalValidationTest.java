package io.github.marishasahay.agentic.agents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marishasahay.agentic.agents.contract.ValidationEvidence;
import io.github.marishasahay.agentic.agents.gates.ContractGate;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.RunOutcome;
import io.github.marishasahay.agentic.core.engine.RunStatus;
import io.github.marishasahay.agentic.core.engine.WorkflowEngine;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.workspace.FileChange;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evidence produced before an automatic repair must not be accepted as covering the result. */
class FinalValidationTest {

  @TempDir
  Path tmp;

  private RunOutcome runWithRepairOf(String repairedFile) {
    GitWorkspace workspace = GitWorkspace.createFrom(null, tmp.resolve("ws"));
    WorkflowGraph graph = WorkflowGraph.of(List.of(
        NodeSpec.builder("security-review", Stage.SECURITY_REVIEW).retry(RetryPolicy.none())
            .task(ctx -> NodeResult.builder().output(ArtifactKeys.SECURITY, "{}").build())
            .build(),
        NodeSpec.builder("verify", Stage.VERIFICATION).dependsOn("security-review")
            .retry(RetryPolicy.none())
            .task(ctx -> NodeResult.builder()
                .change(FileChange.write(repairedFile, "repaired after the review")).build())
            .build(),
        NodeSpec.builder("final-validation", Stage.VERIFICATION).dependsOn("verify")
            .retry(RetryPolicy.none())
            .task(new FinalValidationTask("FULL", List.of("maven-verify"), List.of(), "host",
                Map.of("security-review", List.of("README.md"))))
            .exitGate(new ContractGate<>("evidence-freshness", ArtifactKeys.VALIDATION,
                ValidationEvidence.class, ValidationEvidence::staleEvidence))
            .build()));
    return WorkflowEngine.builder()
        .events(EventLog.inMemory("final", Clock.systemUTC()))
        .artifacts(ArtifactStore.inMemory()).workspace(workspace)
        .approvals(ScriptedApprovalGateway.approveAll()).build()
        .run(graph, "final", "req");
  }

  @Test
  void aCodeRepairAfterTheSecurityReviewMakesThatEvidenceStale() {
    RunOutcome outcome = runWithRepairOf("src/main/java/App.java");

    assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
    assertThat(outcome.reason()).contains("final-validation");
  }

  @Test
  void aDocumentationOnlyChangeAfterTheReviewKeepsItsEvidenceValid() {
    assertThat(runWithRepairOf("README.md").status()).isEqualTo(RunStatus.SUCCEEDED);
  }
}
