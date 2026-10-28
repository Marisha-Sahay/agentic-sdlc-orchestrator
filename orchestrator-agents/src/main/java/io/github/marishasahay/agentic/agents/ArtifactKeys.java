package io.github.marishasahay.agentic.agents;

/** Artifact keys shared by agents, gates and workflow templates (the inter-stage contract). */
public final class ArtifactKeys {

  public static final String REQUIREMENT_RAW = "requirement.raw";
  public static final String REQUIREMENTS_DRAFT = "requirements.draft";
  public static final String REQUIREMENTS = "requirements.spec";
  public static final String CLARIFICATION_QUESTIONS = "clarification.questions";
  public static final String CLARIFICATION_ANSWERS = "clarification.answers";
  public static final String CODEBASE_INDEX = "codebase.index";
  public static final String IMPACT = "impact.analysis";
  public static final String DESIGN = "design.spec";
  public static final String DESIGN_DOC = "design.doc";
  public static final String OPENAPI = "design.openapi";
  public static final String PLAN = "plan.tasks";
  public static final String CHANGED_FILES = "integration.changed-files";
  public static final String TEST_REPORT = "testing.summary";
  public static final String DOCS_SUMMARY = "docs.summary";
  public static final String SECURITY = "security.review";
  public static final String VERIFICATION = "verification.report";
  public static final String RELEASE_NOTES = "release.notes";
  public static final String RELEASE_PATCH = "release.patch";
  public static final String TRACEABILITY = "release.traceability";

  private ArtifactKeys() {}

  /**
   * Key of an implementation task's summary.
   *
   * @param taskId planned task id
   * @return artifact key
   */
  public static String taskResult(String taskId) {
    return "impl." + taskId;
  }
}
