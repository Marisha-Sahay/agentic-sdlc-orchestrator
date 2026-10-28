package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.llm.CassetteLlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmMode;
import io.github.marishasahay.agentic.agents.llm.ModelSettings;
import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.EngineConfig;
import io.github.marishasahay.agentic.core.engine.NodeState;
import io.github.marishasahay.agentic.core.engine.RunOutcome;
import io.github.marishasahay.agentic.core.engine.RunStatus;
import io.github.marishasahay.agentic.core.engine.WorkflowEngine;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.Json;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.metrics.MetricsCalculator;
import io.github.marishasahay.agentic.core.metrics.RunMetrics;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Command-line entry point.
 *
 * <pre>
 * agentic run &lt;scenario&gt; [--llm live|record|replay] [--approvals interactive|scripted]
 *                       [--run-id id] [--max-parallel n]
 * agentic resume &lt;run-id&gt; [--llm ...] [--approvals ...]
 * agentic stop &lt;run-id&gt;        engage the kill switch of a running run
 * agentic report &lt;run-id&gt;      re-render the HTML report from the event log
 * agentic verify-log &lt;run-id&gt;  verify the hash chain of the audit log
 * agentic metrics              reliability metrics across all runs
 * agentic list                 list runs
 * </pre>
 */
public final class AgenticCli {

  private final Path repo;

  private AgenticCli(Path repo) {
    this.repo = repo;
  }

  /**
   * Entry point.
   *
   * @param args command line
   */
  public static void main(String[] args) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
      System.out.println(usage());
      return;
    }
    Args parsed = Args.parse(args);
    AgenticCli cli = new AgenticCli(findRepoRoot());
    int code;
    try {
      code = switch (parsed.command()) {
        case "run" -> cli.run(parsed, false);
        case "resume" -> cli.run(parsed, true);
        case "stop" -> cli.stop(parsed.target());
        case "report" -> cli.report(parsed.target());
        case "verify-log" -> cli.verifyLog(parsed.target());
        case "metrics" -> cli.metrics();
        case "list" -> cli.list();
        default -> {
          System.err.println("Unknown command " + parsed.command() + "\n" + usage());
          yield 2;
        }
      };
    } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
      System.err.println(Ansi.red("error: " + e.getMessage()));
      code = 1;
    }
    System.exit(code);
  }

  private int run(Args args, boolean resume) {
    Path runDir;
    Scenario scenario;
    LlmMode mode;
    String approvalsMode;
    if (resume) {
      runDir = runsDir().resolve(args.target());
      Map<?, ?> meta = Json.read(read(runDir.resolve("run.json")), Map.class);
      scenario = loadScenario((String) meta.get("scenario"));
      mode = args.option("llm").map(v -> LlmMode.valueOf(v.toUpperCase()))
          .orElse(LlmMode.valueOf((String) meta.get("llm")));
      approvalsMode = args.option("approvals").orElse((String) meta.get("approvals"));
    } else {
      scenario = loadScenario(args.target());
      mode = args.option("llm").map(v -> LlmMode.valueOf(v.toUpperCase()))
          .orElse(hasCassettes(scenario) ? LlmMode.REPLAY : LlmMode.LIVE);
      approvalsMode = args.option("approvals")
          .orElse(System.console() != null ? "interactive" : "scripted");
      String runId = args.option("run-id").orElse(scenario.name() + "-"
          + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
      runDir = runsDir().resolve(runId);
      if (Files.exists(runDir)) {
        throw new IllegalStateException("Run " + runId + " already exists; use resume");
      }
    }
    String runId = runDir.getFileName().toString();

    ModelSettings models = ModelSettings.fromEnvironment(System.getenv());
    Path cassettes = repo.resolve("cassettes").resolve(scenario.name());
    AgentSupport primary = new AgentSupport(llm(models, models.primaryModel(), cassettes, mode));
    AgentSupport fallback = new AgentSupport(llm(models, models.fallbackModel(), cassettes, mode));
    if (!resume) {
      write(runDir.resolve("run.json"), Json.write(Map.of("scenario", scenario.name(),
          "llm", mode.name(), "approvals", approvalsMode)));
    }
    WorkflowTemplates.BuildCommands build = new WorkflowTemplates.BuildCommands(dockerAvailable());
    Path baseline = repo.resolve(scenario.baseline());
    String baselineContract = scenario.startsFromExistingCode()
        ? readIfExists(baseline.resolve(ArchitectAgent.CONTRACT_PATH)) : "";
    WorkflowGraph graph = new WorkflowTemplates(primary, fallback, conventions(),
        baselineContract, build).build(scenario);

    Path workspaceDir = runDir.resolve("workspace");
    GitWorkspace workspace = resume ? GitWorkspace.open(workspaceDir)
        : GitWorkspace.createFrom(baseline, workspaceDir);
    EventLog log = new EventLog(runId, runDir.resolve("events.jsonl"), Clock.systemUTC());
    log.subscribe(new ProgressPrinter(System.out));
    WorkflowEngine engine = WorkflowEngine.builder()
        .config(EngineConfig.defaults().withKillSwitch(runDir.resolve("STOP"))
            .withMaxParallelNodes(args.option("max-parallel").map(Integer::parseInt).orElse(4)))
        .events(log)
        .artifacts(new ArtifactStore(runDir.resolve("artifacts")))
        .workspace(workspace)
        .policy(GovernancePolicy.standard())
        .approvals(approvals(approvalsMode, scenario, runDir))
        .build();

    System.out.println(Ansi.bold("Scenario: ") + scenario.title());
    System.out.println(Ansi.bold("Run dir:  ") + runDir);
    System.out.println(Ansi.bold("Models:   ") + mode.name().toLowerCase() + " " + models);
    System.out.println(Ansi.bold("Verify:   ") + build.profile());
    System.out.println(Ansi.bold("Approvals:") + " " + approvalsMode
        + "   (safe-stop anytime: agentic stop " + runId + ")\n");

    CountDownLatch finished = new CountDownLatch(1);
    Thread hook = new Thread(() -> {
      engine.stopController().requestStop("operator interrupt (SIGINT)");
      awaitQuietly(finished);
    });
    Runtime.getRuntime().addShutdownHook(hook);
    RunOutcome outcome;
    try {
      outcome = resume ? engine.resume(graph)
          : engine.run(graph, scenario.name(), scenario.requirement(), Map.of(
              "llmMode", mode.name(), "primaryModel", String.valueOf(models.primaryModel()),
              "fallbackModel", String.valueOf(models.fallbackModel()),
              "verifyProfile", build.profile(), "approvals", approvalsMode,
              "baseline", scenario.baseline()));
    } finally {
      finished.countDown();
    }
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException alreadyShuttingDown) {
      // the hook is running; it only waits for the latch released above
    }

    String mermaid = graph.toMermaid(id -> outcome.nodeStates().getOrDefault(id,
        NodeState.PENDING).name().toLowerCase()) + HtmlReport.mermaidClasses();
    write(runDir.resolve("graph.mmd"), mermaid);
    RunMetrics metrics = renderReport(runDir, log.events(), mermaid);
    printSummary(outcome, metrics, runDir);
    return outcome.status() == RunStatus.SUCCEEDED ? 0 : 3;
  }

  private LlmClient llm(ModelSettings models, String model, Path cassettes, LlmMode mode) {
    LlmClient live = null;
    if (mode != LlmMode.REPLAY) {
      live = models.client(model);
    }
    return new CassetteLlmClient(live, cassettes, mode);
  }

  private ApprovalGateway approvals(String mode, Scenario scenario, Path runDir) {
    if (mode.equals("interactive")) {
      return new ConsoleApprovalGateway(runDir.resolve("edits"),
          System.getProperty("user.name", "reviewer"));
    }
    ScriptedApprovalGateway scripted = new ScriptedApprovalGateway(ApprovalDecision.approve(
        "scripted-reviewer", "approved by scenario script (unattended run)"));
    scenario.scriptedApprovals().forEach((node, decisions) -> scripted.script(node,
        decisions.stream().map(d -> new ApprovalDecision(d.approve(), "scripted-reviewer",
            d.comment(), d.amendments())).toArray(ApprovalDecision[]::new)));
    return scripted;
  }

  private RunMetrics renderReport(Path runDir, List<EventEnvelope> events, String mermaid) {
    RunMetrics metrics = MetricsCalculator.compute(events);
    write(runDir.resolve("report.html"), HtmlReport.render(events, mermaid, metrics));
    write(runDir.resolve("metrics.json"), AgentSupport.pretty(metrics));
    return metrics;
  }

  private void printSummary(RunOutcome outcome, RunMetrics m, Path runDir) {
    System.out.println();
    System.out.println(Ansi.bold("Status: ") + switch (outcome.status()) {
      case SUCCEEDED -> Ansi.green("SUCCEEDED");
      case FAILED -> Ansi.red("FAILED") + " " + outcome.reason();
      case PAUSED -> Ansi.yellow("PAUSED") + " " + outcome.reason()
          + Ansi.dim("  (resume: agentic resume " + outcome.runId() + ")");
    });
    System.out.printf("Nodes %d ok / %d failed / %d skipped  |  retries %d  rollbacks %d  "
            + "fallbacks %d  MTTR %s  |  e2e %s (human %s)  |  model calls %d%n",
        m.nodesSucceeded(), m.nodesFailed(), m.nodesSkipped(), m.retries(), m.rollbacks(),
        m.fallbacks(), m.mttrMs() < 0 ? "n/a" : HtmlReport.dur(m.mttrMs()),
        HtmlReport.dur(m.endToEndMs()), HtmlReport.dur(m.humanWaitMs()), m.agentCalls());
    System.out.println(Ansi.bold("Report: ") + runDir.resolve("report.html").toUri());
    Path patch = runDir.resolve("artifacts").resolve("release.patch");
    if (Files.isDirectory(patch)) {
      System.out.println(Ansi.bold("Patch:  ") + patch + "  (review, then git apply)");
    }
  }

  private int stop(String runId) {
    Path runDir = runsDir().resolve(runId);
    if (!Files.isDirectory(runDir)) {
      throw new IllegalArgumentException("No such run: " + runId);
    }
    write(runDir.resolve("STOP"), "stop requested at " + LocalDateTime.now() + "\n");
    System.out.println("Kill switch engaged for " + runId + ": running attempts finish, no new "
        + "work starts, the run ends PAUSED and can be resumed.");
    return 0;
  }

  private int report(String runId) {
    Path runDir = runsDir().resolve(runId);
    List<EventEnvelope> events = EventLog.readFile(runDir.resolve("events.jsonl"));
    String mermaid = readIfExists(runDir.resolve("graph.mmd"));
    renderReport(runDir, events, mermaid.isBlank() ? "flowchart LR\n  empty" : mermaid);
    System.out.println(runDir.resolve("report.html").toUri());
    return 0;
  }

  private int verifyLog(String runId) {
    List<EventEnvelope> events = EventLog.readFile(runsDir().resolve(runId)
        .resolve("events.jsonl"));
    Optional<Long> broken = EventLog.verifyChain(events);
    if (broken.isPresent()) {
      System.out.println(Ansi.red("Audit chain BROKEN at event " + broken.get()));
      return 4;
    }
    System.out.println(Ansi.green("Audit chain verified: " + events.size() + " events, head "
        + events.getLast().hash().substring(0, 16)));
    return 0;
  }

  private int metrics() {
    List<RunMetrics> runs = new ArrayList<>();
    System.out.printf("%-34s %-11s %6s %6s %7s %9s %9s %8s%n", "run", "status", "nodes",
        "retry", "rollbk", "MTTR", "e2e", "calls");
    for (Path dir : runDirs()) {
      RunMetrics m = MetricsCalculator.compute(EventLog.readFile(dir.resolve("events.jsonl")));
      runs.add(m);
      System.out.printf("%-34s %-11s %6d %6d %7d %9s %9s %8d%n", m.runId(), m.status(),
          m.nodesSucceeded(), m.retries(), m.rollbacks(),
          m.mttrMs() < 0 ? "n/a" : HtmlReport.dur(m.mttrMs()), HtmlReport.dur(m.endToEndMs()),
          m.agentCalls());
    }
    System.out.println();
    Map<String, Object> aggregate = new LinkedHashMap<>(MetricsCalculator.aggregate(runs));
    aggregate.forEach((k, v) -> System.out.printf("  %-16s %s%n", k,
        v instanceof Double d ? String.format("%.2f", d) : v));
    return 0;
  }

  private int list() {
    for (Path dir : runDirs()) {
      RunMetrics m = MetricsCalculator.compute(EventLog.readFile(dir.resolve("events.jsonl")));
      System.out.printf("%-34s %-10s %s%n", m.runId(), m.status(), m.scenario());
    }
    return 0;
  }

  private Scenario loadScenario(String name) {
    Path file = repo.resolve("scenarios").resolve(name + ".yaml");
    if (!Files.exists(file)) {
      throw new IllegalArgumentException("No scenario " + name + " (looked for " + file + ")");
    }
    return Scenario.load(file);
  }

  private boolean hasCassettes(Scenario scenario) {
    Path dir = repo.resolve("cassettes").resolve(scenario.name());
    try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
      return files.findAny().isPresent();
    } catch (IOException e) {
      return false;
    }
  }

  private String conventions() {
    return readIfExists(repo.resolve("config/agent-skills/java-spring-boot.md"));
  }

  private Path runsDir() {
    return repo.resolve(".runs");
  }

  private List<Path> runDirs() {
    if (!Files.isDirectory(runsDir())) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(runsDir())) {
      return dirs.filter(d -> Files.exists(d.resolve("events.jsonl"))).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static boolean dockerAvailable() {
    try {
      Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
      process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
      return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("scenarios")) && Files.exists(dir.resolve("pom.xml"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Run from inside the agentic-swe-urlshortener repository");
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(120, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readIfExists(Path file) {
    return Files.exists(file) ? read(file) : "";
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String usage() {
    return """
        agentic run <scenario> [--llm live|record|replay] [--approvals interactive|scripted]
                               [--run-id <id>] [--max-parallel <n>]
        agentic resume <run-id> [--llm ...] [--approvals ...]
        agentic stop <run-id>        engage the kill switch (safe stop, resumable)
        agentic report <run-id>      re-render report.html from the audit log
        agentic verify-log <run-id>  verify the audit log's hash chain
        agentic metrics              reliability metrics across all runs
        agentic list                 list runs

        Scenarios: greenfield, brownfield, ambiguous (scenarios/*.yaml)
        Live/record mode needs GEMINI_API_KEY and AGENTIC_MODEL (see .env.example).""";
  }

  /**
   * Parsed command line.
   *
   * @param command sub-command
   * @param target scenario or run id
   * @param options {@code --key value} options
   */
  record Args(String command, String target, Map<String, String> options) {

    static Args parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      String target = null;
      for (int i = 1; i < args.length; i++) {
        if (args[i].startsWith("--")) {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i]);
          }
          options.put(args[i].substring(2), args[++i]);
        } else if (target == null) {
          target = args[i];
        }
      }
      return new Args(args[0], target, options);
    }

    Optional<String> option(String key) {
      return Optional.ofNullable(options.get(key));
    }
  }
}
