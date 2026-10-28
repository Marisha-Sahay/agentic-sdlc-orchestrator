package io.github.marishasahay.agentic.core.approval;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-interactive gateway with pre-recorded decisions per node, falling back to a default. Used
 * by tests and by unattended demo runs; every request it receives is kept for inspection.
 */
public final class ScriptedApprovalGateway implements ApprovalGateway {

  private final Map<String, Deque<ApprovalDecision>> scripted = new ConcurrentHashMap<>();
  private final ApprovalDecision defaultDecision;
  private final List<ApprovalRequest> received = new CopyOnWriteArrayList<>();

  public ScriptedApprovalGateway(ApprovalDecision defaultDecision) {
    this.defaultDecision = defaultDecision;
  }

  public static ScriptedApprovalGateway approveAll() {
    return new ScriptedApprovalGateway(ApprovalDecision.approve("auto", "scripted approval"));
  }

  /**
   * Queues decisions for a node; they are consumed in order, then the default applies.
   *
   * @param nodeId node id
   * @param decisions decisions in order
   * @return this gateway
   */
  public ScriptedApprovalGateway script(String nodeId, ApprovalDecision... decisions) {
    scripted.computeIfAbsent(nodeId, k -> new ArrayDeque<>()).addAll(List.of(decisions));
    return this;
  }

  @Override
  public ApprovalDecision decide(ApprovalRequest request) {
    received.add(request);
    Deque<ApprovalDecision> queue = scripted.get(request.nodeId());
    synchronized (this) {
      return queue == null || queue.isEmpty() ? defaultDecision : queue.poll();
    }
  }

  public List<ApprovalRequest> received() {
    return List.copyOf(received);
  }
}
