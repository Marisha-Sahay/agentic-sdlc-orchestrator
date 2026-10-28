package io.github.marishasahay.agentic.agents.contract;

import java.util.List;

/**
 * Release-readiness summary for the human release approver.
 *
 * @param title PR/release title
 * @param summary what ships
 * @param changes notable changes
 * @param risks residual risks
 * @param rollbackPlan how to back the change out
 * @param testingSummary what was verified and how
 * @param followUps known gaps and next steps
 */
public record ReleaseNotes(
    String title,
    String summary,
    List<String> changes,
    List<String> risks,
    String rollbackPlan,
    String testingSummary,
    List<String> followUps) {}
