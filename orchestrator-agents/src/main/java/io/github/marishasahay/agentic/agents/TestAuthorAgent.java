package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.analysis.SourceContext;
import io.github.marishasahay.agentic.agents.contract.CodeProposal;
import io.github.marishasahay.agentic.core.engine.NodeContext;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import java.util.Arrays;
import java.util.List;

/** Writes unit and integration tests for the changed behaviour, proving the requirements. */
public final class TestAuthorAgent implements NodeTask {

  private static final String SYSTEM = """
      You are a senior engineer writing tests for a change to a Spring Boot 4 / Java 21 service.
      - Unit tests (*Test.java) for new logic with JUnit 5 and AssertJ; no Docker needed.
      - Integration tests (*IT.java) that extend the project's existing integration test base
        (Testcontainers PostgreSQL) for new endpoints and persistence.
      - Cover every acceptance criterion of the requirements touched by the change, including
        error paths, and add a regression test for any bug being fixed.
      - Only create or modify files under src/test/. Do not change main code.
      - Return complete file contents. Tests must compile and pass.""";

  private final AgentSupport llm;
  private final String conventions;

  public TestAuthorAgent(AgentSupport llm, String conventions) {
    this.llm = llm;
    this.conventions = conventions;
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    List<String> changed = Arrays.stream(ctx.input(ArtifactKeys.CHANGED_FILES).split("\n"))
        .filter(s -> !s.isBlank()).toList();
    CodeProposal proposal = llm.ask(ctx, "test-author", SYSTEM,
        "## Requirements\n" + ctx.input(ArtifactKeys.REQUIREMENTS)
            + "\n\n## Plan\n" + ctx.input(ArtifactKeys.PLAN)
            + "\n\n## Changed files\n" + String.join("\n", changed)
            + "\n\n## Engineering conventions\n" + conventions
            + "\n\n## Workspace\n" + SourceContext.render(ctx.workspaceRoot(), changed, true,
                120_000),
        CodeProposal.class);
    return NodeResult.builder()
        .changes(proposal.toChanges())
        .output(ArtifactKeys.TEST_REPORT, proposal.summary())
        .summary(proposal.summary())
        .build();
  }
}
