package io.github.marishasahay.agentic.core.approval;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-interactive gateway that replays pre-recorded decisions per node, in order. It fails
 * closed: a request is decided only by the next scripted decision for its node, and only if
 * that decision is for the same kind of request and covers every one of its reason codes.
 * Anything else (an unexpected schema change, a second approval the script did not foresee)
 * throws {@link ReviewerUnavailableException} instead of being approved silently.
 *
 * <p>Used by tests and by demonstration runs whose decisions are written in the scenario file;
 * the reviewer name it is given should say so.
 */
public final class ScriptedApprovalGateway implements ApprovalGateway {

  /** Covers every reason code; only for tests and the standing decision of {@link #approveAll}. */
  public static final String ANY = "*";

  /**
   * One pre-recorded decision and what it is allowed to decide.
   *
   * @param kind request kind it answers, or null for either kind
   * @param covers reason codes it covers ({@link #ANY} for all)
   * @param decision the decision
   */
  public record ScriptedDecision(ApprovalKind kind, Set<String> covers,
      ApprovalDecision decision) {

    public ScriptedDecision {
      covers = Set.copyOf(covers);
    }

    /**
     * An acceptance decision covering the given reason codes.
     *
     * @param decision the decision
     * @param codes reason codes it covers
     * @return the scripted decision
     */
    public static ScriptedDecision acceptance(ApprovalDecision decision, String... codes) {
      return new ScriptedDecision(ApprovalKind.ACCEPTANCE, Set.of(codes), decision);
    }

    /**
     * An authorization decision covering the given reason codes.
     *
     * @param decision the decision
     * @param codes reason codes it covers
     * @return the scripted decision
     */
    public static ScriptedDecision authorization(ApprovalDecision decision, String... codes) {
      return new ScriptedDecision(ApprovalKind.AUTHORIZATION, Set.of(codes), decision);
    }

    boolean answers(ApprovalRequest request) {
      return (kind == null || kind == request.kind())
          && (covers.contains(ANY) || covers.containsAll(request.reasonCodes()));
    }
  }

  private final Map<String, Deque<ScriptedDecision>> scripted = new ConcurrentHashMap<>();
  private final ScriptedDecision standing;
  private final List<ApprovalRequest> received = new CopyOnWriteArrayList<>();

  private ScriptedApprovalGateway(ScriptedDecision standing) {
    this.standing = standing;
  }

  /**
   * A gateway that decides only what is scripted and fails closed on everything else.
   *
   * @return the gateway
   */
  public static ScriptedApprovalGateway failClosed() {
    return new ScriptedApprovalGateway(null);
  }

  /**
   * Test helper: approves every request of every kind. Never used for scenario runs.
   *
   * @return the gateway
   */
  public static ScriptedApprovalGateway approveAll() {
    return new ScriptedApprovalGateway(new ScriptedDecision(null, Set.of(ANY),
        ApprovalDecision.approve("test-auto-approver", "approve-all test gateway")));
  }

  /**
   * Queues acceptance decisions for a node that cover any reason; consumed in order.
   *
   * @param nodeId node id
   * @param decisions decisions in order
   * @return this gateway
   */
  public ScriptedApprovalGateway script(String nodeId, ApprovalDecision... decisions) {
    for (ApprovalDecision decision : decisions) {
      script(nodeId, ScriptedDecision.acceptance(decision, ANY));
    }
    return this;
  }

  /**
   * Queues scripted decisions for a node; consumed in order.
   *
   * @param nodeId node id
   * @param decisions decisions in order
   * @return this gateway
   */
  public ScriptedApprovalGateway script(String nodeId, ScriptedDecision... decisions) {
    scripted.computeIfAbsent(nodeId, k -> new ArrayDeque<>()).addAll(List.of(decisions));
    return this;
  }

  @Override
  public synchronized ApprovalDecision decide(ApprovalRequest request) {
    received.add(request);
    Deque<ScriptedDecision> queue = scripted.get(request.nodeId());
    ScriptedDecision next = queue == null ? null : queue.peek();
    if (next != null && next.answers(request)) {
      return queue.poll().decision();
    }
    if (standing != null && standing.answers(request)) {
      return standing.decision();
    }
    throw new ReviewerUnavailableException(next == null
        ? "no scripted decision left for " + describe(request)
        : "the next scripted decision for node " + request.nodeId() + " ("
            + (next.kind() == null ? "any kind" : next.kind().name().toLowerCase())
            + ", covers " + new TreeSet<>(next.covers()) + ") does not cover "
            + describe(request));
  }

  public List<ApprovalRequest> received() {
    return List.copyOf(received);
  }

  private static String describe(ApprovalRequest request) {
    return request.kind().name().toLowerCase() + " of node " + request.nodeId()
        + " with reasons " + new TreeSet<>(request.reasonCodes());
  }
}
