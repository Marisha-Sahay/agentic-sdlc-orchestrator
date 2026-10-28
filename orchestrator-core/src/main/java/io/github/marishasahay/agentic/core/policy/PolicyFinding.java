package io.github.marishasahay.agentic.core.policy;

/**
 * One rule's verdict on a proposed change.
 *
 * @param rule rule name
 * @param severity effect on the node
 * @param message human-readable reason (also fed back to the agent)
 * @param path offending path, or null if not path-specific
 */
public record PolicyFinding(String rule, Severity severity, String message, String path) {}
