package io.github.marishasahay.agentic.agents;

import io.github.marishasahay.agentic.agents.llm.LlmCall;
import io.github.marishasahay.agentic.agents.llm.LlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmReply;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.NodeResult;
import io.github.marishasahay.agentic.core.engine.NodeTask;
import io.github.marishasahay.agentic.core.engine.RunOutcome;
import io.github.marishasahay.agentic.core.engine.WorkflowEngine;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.gate.Gate;
import io.github.marishasahay.agentic.core.graph.NodeSpec;
import io.github.marishasahay.agentic.core.graph.RetryPolicy;
import io.github.marishasahay.agentic.core.graph.Stage;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one agent inside a real engine: a seed node publishes the agent's inputs, the agent node
 * runs with a scripted model, real policy-free gates and a real git workspace.
 */
final class AgentHarness implements LlmClient {

  final GitWorkspace workspace;
  final ArtifactStore artifacts = ArtifactStore.inMemory();
  final EventLog log = EventLog.inMemory("agent-test", Clock.systemUTC());
  final List<LlmCall> calls = new CopyOnWriteArrayList<>();
  private final Map<String, Deque<String>> replies = new HashMap<>();

  AgentHarness(Path tmp, Map<String, String> baselineFiles) {
    Path source = tmp.resolve("source");
    try {
      Files.createDirectories(source);
      for (Map.Entry<String, String> file : baselineFiles.entrySet()) {
        Path target = source.resolve(file.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    workspace = GitWorkspace.createFrom(source, tmp.resolve("workspace"));
  }

  AgentHarness reply(String agent, String... json) {
    replies.computeIfAbsent(agent, k -> new ArrayDeque<>()).addAll(List.of(json));
    return this;
  }

  AgentSupport llm() {
    return new AgentSupport(this);
  }

  @Override
  public LlmReply complete(LlmCall call) {
    calls.add(call);
    Deque<String> queue = replies.get(call.agent());
    if (queue == null || queue.isEmpty()) {
      throw new IllegalStateException("no scripted reply for " + call.agent());
    }
    String text = queue.size() == 1 ? queue.peek() : queue.poll();
    return new LlmReply(text, "scripted-model", "replay", 5, 100, 50);
  }

  RunOutcome run(NodeTask agent, Map<String, String> inputs, Gate... exitGates) {
    NodeSpec.Builder node = NodeSpec.builder("agent", Stage.IMPLEMENTATION)
        .dependsOn("seed").task(agent)
        .retry(new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)));
    for (Gate gate : exitGates) {
      node.exitGate(gate);
    }
    NodeSpec seed = NodeSpec.builder("seed", Stage.REQUIREMENTS).retry(RetryPolicy.none())
        .task(ctx -> {
          NodeResult.Builder result = NodeResult.builder();
          inputs.forEach(result::output);
          return result.build();
        }).build();
    return WorkflowEngine.builder().events(log).artifacts(artifacts).workspace(workspace)
        .approvals(ScriptedApprovalGateway.approveAll()).build()
        .run(WorkflowGraph.of(List.of(seed, node.build())), "agent-test", "req");
  }

  String file(String path) {
    try {
      return Files.readString(workspace.root().resolve(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
