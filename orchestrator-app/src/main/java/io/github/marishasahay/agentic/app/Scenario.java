package io.github.marishasahay.agentic.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A scenario file ({@code scenarios/*.yaml}): the requirement, the codebase it starts from and,
 * for unattended runs, the reviewer decisions to apply at each human checkpoint.
 *
 * @param name scenario id
 * @param kind greenfield, brownfield or ambiguous
 * @param title human title
 * @param baseline directory (relative to the repo root) copied into the run workspace
 * @param requirement raw stakeholder request
 * @param scriptedApprovals node id to decisions, used with {@code --approvals scripted}
 */
public record Scenario(
    String name,
    Kind kind,
    String title,
    String baseline,
    String requirement,
    Map<String, List<ScriptedDecision>> scriptedApprovals) {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /** Scenario shape. */
  public enum Kind {
    GREENFIELD,
    BROWNFIELD,
    AMBIGUOUS
  }

  /**
   * One scripted reviewer decision.
   *
   * @param approve approve or reject
   * @param comment reviewer rationale
   * @param amendments artifact key to replacement content
   */
  public record ScriptedDecision(boolean approve, String comment, Map<String, String> amendments) {}

  public Scenario {
    scriptedApprovals = scriptedApprovals == null ? Map.of() : Map.copyOf(scriptedApprovals);
  }

  /**
   * Loads a scenario file.
   *
   * @param file YAML file
   * @return scenario
   */
  public static Scenario load(Path file) {
    try {
      return YAML.readValue(file.toFile(), Scenario.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read scenario " + file, e);
    }
  }

  public boolean startsFromExistingCode() {
    return kind != Kind.GREENFIELD;
  }
}
