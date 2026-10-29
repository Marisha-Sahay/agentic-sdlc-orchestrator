package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.agents.AgentSupport;
import io.github.marishasahay.agentic.agents.ArchitectAgent;
import io.github.marishasahay.agentic.agents.gates.AmendmentContracts;
import io.github.marishasahay.agentic.agents.llm.CassetteLlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmClient;
import io.github.marishasahay.agentic.agents.llm.LlmMode;
import io.github.marishasahay.agentic.agents.llm.ModelSettings;
import io.github.marishasahay.agentic.agents.llm.ThrottledLlmClient;
import io.github.marishasahay.agentic.core.approval.ApprovalDecision;
import io.github.marishasahay.agentic.core.approval.ApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ApprovalKind;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway;
import io.github.marishasahay.agentic.core.approval.ScriptedApprovalGateway.ScriptedDecision;
import io.github.marishasahay.agentic.core.artifact.ArtifactStore;
import io.github.marishasahay.agentic.core.engine.EngineConfig;
import io.github.marishasahay.agentic.core.engine.NodeState;
import io.github.marishasahay.agentic.core.engine.RunOutcome;
import io.github.marishasahay.agentic.core.engine.RunStatus;
import io.github.marishasahay.agentic.core.engine.WorkflowEngine;
import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.EventLog;
import io.github.marishasahay.agentic.core.event.Json;
import io.github.marishasahay.agentic.core.gate.CommandGate;
import io.github.marishasahay.agentic.core.gate.CommandSandbox;
import io.github.marishasahay.agentic.core.gate.DockerSandbox;
import io.github.marishasahay.agentic.core.gate.GateContext;
import io.github.marishasahay.agentic.core.gate.GateResult;
import io.github.marishasahay.agentic.core.graph.WorkflowGraph;
import io.github.marishasahay.agentic.core.metrics.MetricsCalculator;
import io.github.marishasahay.agentic.core.metrics.RunMetrics;
import io.github.marishasahay.agentic.core.util.Hashing;
import io.github.marishasahay.agentic.core.workspace.GitWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Command-line entry point.
 *
 * <pre>
 * agentic run &lt;scenario&gt; [--llm live|record|replay] [--approvals interactive|scripted]
 *                       [--sandbox host|docker] [--cassettes name] [--run-id id]
 *                       [--max-parallel n]
 * agentic resume &lt;run-id&gt; [--llm ...] [--approvals ...] [--reset-budgets who]
 * agentic check-evidence &lt;name&gt;...   verify saved evidence under docs/evidence
 * agentic publish-evidence &lt;run-id&gt; &lt;name&gt;  copy a run's evidence to docs/evidence
 * agentic warmup-sandbox         fill the Docker sandbox's dependency cache (network on)
 * agentic stop &lt;run-id&gt;        engage the kill switch of a running run
 * agentic report &lt;run-id&gt;      re-render the HTML report from the event log
 * agentic verify-log &lt;run-id&gt;  verify the hash chain of the audit log
 * agentic metrics              reliability metrics across all runs
 * agentic list                 list runs
 * </pre>
 */
public final class AgenticCli {

  private final Path repo;
  /** One quota per API key: primary and fallback clients share these permits. */
  private final Semaphore modelCallPermits = new Semaphore(Integer.parseInt(
      System.getenv().getOrDefault("AGENTIC_MAX_CONCURRENT_CALLS", "2")));

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
        case "check-evidence" -> cli.checkEvidence(parsed.targets());
        case "publish-evidence" -> cli.publishEvidence(parsed.targets());
        case "warmup-sandbox" -> cli.warmupSandbox();
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
    String cassetteSet;
    String sandboxName;
    if (resume) {
      runDir = runsDir().resolve(args.target());
      Map<?, ?> meta = Json.read(read(runDir.resolve("run.json")), Map.class);
      scenario = loadScenario((String) meta.get("scenario"));
      mode = args.option("llm").map(v -> LlmMode.valueOf(v.toUpperCase()))
          .orElse(LlmMode.valueOf((String) meta.get("llm")));
      approvalsMode = ApprovalModes.parse(args.option("approvals")
          .orElse((String) meta.get("approvals")));
      cassetteSet = (String) meta.get("cassettes");
      sandboxName = (String) meta.get("sandbox");
    } else {
      scenario = loadScenario(args.target());
      cassetteSet = args.option("cassettes").orElse(scenario.name());
      mode = args.option("llm").map(v -> LlmMode.valueOf(v.toUpperCase()))
          .orElse(hasCassettes(cassetteSet) ? LlmMode.REPLAY : LlmMode.LIVE);
      approvalsMode = ApprovalModes.resolve(args.option("approvals"), scenario.approvals(), mode,
          System.console() != null);
      sandboxName = args.option("sandbox").orElse("host");
      String runId = args.option("run-id").orElse(scenario.name() + "-"
          + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
      runDir = runsDir().resolve(runId);
      if (Files.exists(runDir)) {
        throw new IllegalStateException("Run " + runId + " already exists; use resume");
      }
    }
    String runId = runDir.getFileName().toString();
    CommandSandbox sandbox = sandbox(sandboxName);

    ModelSettings models = ModelSettings.fromEnvironment(System.getenv());
    Path cassettes = repo.resolve("cassettes").resolve(cassetteSet);
    AgentSupport primary = new AgentSupport(llm(models, models.primaryModel(), cassettes, mode));
    AgentSupport fallback = new AgentSupport(llm(models, models.fallbackModel(), cassettes, mode));
    if (!resume) {
      write(runDir.resolve("run.json"), Json.write(Map.of("scenario", scenario.name(),
          "llm", mode.name(), "approvals", approvalsMode, "cassettes", cassetteSet,
          "sandbox", sandboxName)));
    }
    WorkflowTemplates.BuildCommands build = new WorkflowTemplates.BuildCommands(
        sandbox instanceof DockerSandbox || dockerAvailable(), sandbox);
    Path baseline = repo.resolve(scenario.baseline());
    String baselineContract = scenario.startsFromExistingCode()
        ? readIfExists(baseline.resolve(ArchitectAgent.CONTRACT_PATH)) : "";
    WorkflowGraph graph = new WorkflowTemplates(primary, fallback, conventions(),
        baselineContract, build).build(scenario);
    String workflowSha = graph.fingerprint();

    Path workspaceDir = runDir.resolve("workspace");
    GitWorkspace workspace = resume ? GitWorkspace.open(workspaceDir)
        : GitWorkspace.createFrom(baseline, workspaceDir);
    EventLog log = new EventLog(runId, runDir.resolve("events.jsonl"), Clock.systemUTC());
    log.subscribe(new ProgressPrinter(System.out));
    ArtifactStore artifacts = new ArtifactStore(runDir.resolve("artifacts"));
    WorkflowEngine engine = WorkflowEngine.builder()
        .config(EngineConfig.defaults().withKillSwitch(runDir.resolve("STOP"))
            .withMaxParallelNodes(args.option("max-parallel").map(Integer::parseInt).orElse(4)))
        .events(log)
        .artifacts(artifacts)
        .workspace(workspace)
        .policy(GovernancePolicy.standard())
        .approvals(approvals(approvalsMode, scenario, runDir))
        .amendmentValidator(new AmendmentContracts(artifacts, baselineContract,
            scenario.kind() == Scenario.Kind.AMBIGUOUS, WorkflowTemplates.MAX_TASKS))
        .build();

    System.out.println(Ansi.bold("Scenario: ") + scenario.title());
    System.out.println(Ansi.bold("Run dir:  ") + runDir);
    System.out.println(Ansi.bold("Models:   ") + mode.name().toLowerCase() + " " + models);
    System.out.println(Ansi.bold("Verify:   ") + build.profile());
    System.out.println(Ansi.bold("Sandbox:  ") + sandbox.describe());
    System.out.println(Ansi.bold("Approvals:") + " " + approvalsLabel(approvalsMode, scenario)
        + "   (safe-stop anytime: agentic stop " + runId + ")\n");

    CountDownLatch finished = new CountDownLatch(1);
    Thread hook = new Thread(() -> {
      engine.stopController().requestStop("operator interrupt (SIGINT)");
      awaitQuietly(finished);
    });
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      RunOutcome outcome = resume
          ? engine.resume(graph, new WorkflowEngine.ResumeOptions(Map.of(
              "scenario", scenario.name(), "requirementSha", Hashing.sha256(
                  scenario.requirement()), "workflowSha", workflowSha),
              args.option("reset-budgets").orElse(null)))
          : engine.run(graph, scenario.name(), scenario.requirement(), Map.of(
              "llmMode", mode.name(), "primaryModel", String.valueOf(models.primaryModel()),
              "fallbackModel", String.valueOf(models.fallbackModel()),
              "verifyProfile", build.profile(), "sandbox", sandbox.describe(),
              "approvals", approvalsLabel(approvalsMode, scenario),
              "cassettes", cassetteSet, "baseline", scenario.baseline(),
              "workflowSha", workflowSha));
      List<EventEnvelope> events = log.events();
      String mermaid = graph.toMermaid(id -> HtmlReport.nodeClass(events, id,
          outcome.nodeStates().getOrDefault(id, NodeState.PENDING).name()))
          + HtmlReport.mermaidClasses();
      write(runDir.resolve("graph.mmd"), mermaid);
      RunMetrics metrics = renderReport(runDir, log.events(), mermaid);
      printSummary(outcome, metrics, runDir);
      return outcome.status() == RunStatus.SUCCEEDED ? 0 : 3;
    } finally {
      // On Ctrl-C the hook waits for this latch, so the log, report and summary are always
      // written before the JVM halts.
      finished.countDown();
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException alreadyShuttingDown) {
        // the hook is running and has just been released
      }
    }
  }

  private LlmClient llm(ModelSettings models, String model, Path cassettes, LlmMode mode) {
    LlmClient live = null;
    if (mode != LlmMode.REPLAY) {
      live = ThrottledLlmClient.withDefaults(models.client(model), modelCallPermits);
    }
    return new CassetteLlmClient(live, cassettes, mode);
  }

  private ApprovalGateway approvals(String mode, Scenario scenario, Path runDir) {
    if (mode.equals(ApprovalModes.INTERACTIVE)) {
      return new ConsoleApprovalGateway(runDir.resolve("edits"),
          "human:" + System.getProperty("user.name", "reviewer"));
    }
    String reviewer = "scripted-demo (scenarios/" + scenario.name() + ".yaml)";
    ScriptedApprovalGateway scripted = ScriptedApprovalGateway.failClosed();
    scenario.scriptedApprovals().forEach((node, decisions) -> scripted.script(node,
        decisions.stream().map(d -> new ScriptedDecision(
            ApprovalKind.valueOf(d.kind().toUpperCase()), Set.copyOf(d.covers()),
            new ApprovalDecision(d.approve(), reviewer, d.comment(), d.amendments())))
            .toArray(ScriptedDecision[]::new)));
    return scripted;
  }

  private static String approvalsLabel(String mode, Scenario scenario) {
    return mode.equals(ApprovalModes.INTERACTIVE) ? "interactive (human reviewer in the terminal)"
        : "scripted demonstration decisions from scenarios/" + scenario.name() + ".yaml "
            + "(not a human review; requests they do not cover pause the run)";
  }

  private static CommandSandbox sandbox(String name) {
    return switch (name) {
      case "host" -> CommandSandbox.host();
      case "docker" -> DockerSandbox.standard(DockerSandbox.Network.OFFLINE);
      default -> throw new IllegalArgumentException("Unknown sandbox '" + name
          + "' (use host or docker)");
    };
  }

  private int checkEvidence(List<String> names) {
    if (names.isEmpty()) {
      throw new IllegalArgumentException("Name the evidence sets to check, e.g. "
          + "check-evidence greenfield brownfield ambiguous");
    }
    int failures = 0;
    for (String name : names) {
      EvidenceCheck.Result result = EvidenceCheck.check(repo.resolve("docs/evidence")
          .resolve(name));
      if (result.ok()) {
        System.out.println(Ansi.green("OK   ") + name + "  model replies: "
            + result.provenance());
      } else {
        failures++;
        System.out.println(Ansi.red("FAIL ") + name + "  " + result.problems());
      }
    }
    return failures == 0 ? 0 : 5;
  }

  private int publishEvidence(List<String> targets) {
    if (targets.size() != 2) {
      throw new IllegalArgumentException("Usage: publish-evidence <run-id> <name>");
    }
    Path from = runsDir().resolve(targets.get(0));
    Path to = repo.resolve("docs/evidence").resolve(targets.get(1));
    try {
      if (Files.exists(to)) {
        try (Stream<Path> old = Files.walk(to)) {
          old.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
      }
      for (String item : List.of("events.jsonl", "report.html", "metrics.json", "graph.mmd",
          "release.patch", "artifacts")) {
        Path source = from.resolve(item);
        if (!Files.exists(source)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(source)) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            Path target = to.resolve(from.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    System.out.println("Published " + from + " -> " + to);
    return checkEvidence(List.of(targets.get(1)));
  }

  private int warmupSandbox() {
    Path copy = runsDir().resolve("sandbox-warmup");
    GitWorkspace.createFrom(repo.resolve("url-shortener"), copy.resolve("url-shortener"));
    GateResult result = CommandGate.of("sandbox-warmup", Duration.ofMinutes(30), "mvn", "-B",
        "-ntp", "verify").in(DockerSandbox.standard(DockerSandbox.Network.ONLINE))
        .evaluate(new GateContext(null, null, Map.of(), copy.resolve("url-shortener")));
    System.out.println(result.passed() ? Ansi.green("Dependency cache warmed (network was "
        + "enabled for this trusted baseline build only)") : Ansi.red(result.evidence()));
    return result.passed() ? 0 : 1;
  }

  private RunMetrics renderReport(Path runDir, List<EventEnvelope> events, String mermaid) {
    RunMetrics metrics = MetricsCalculator.compute(events);
    latestVersion(runDir.resolve("artifacts").resolve("release.patch"))
        .ifPresent(v -> write(runDir.resolve("release.patch"), read(v)));
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
    Path patch = runDir.resolve("release.patch");
    if (Files.exists(patch)) {
      System.out.println(Ansi.bold("Patch:  ") + patch
          + "  (reviewable outcome: git apply it onto the baseline)");
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

  private boolean hasCassettes(String cassetteSet) {
    Path dir = repo.resolve("cassettes").resolve(cassetteSet);
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

  private static Optional<Path> latestVersion(Path artifactDir) {
    if (!Files.isDirectory(artifactDir)) {
      return Optional.empty();
    }
    try (Stream<Path> versions = Files.list(artifactDir)) {
      return versions.max((a, b) -> Integer.compare(version(a), version(b)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int version(Path file) {
    return Integer.parseInt(file.getFileName().toString().replaceAll("\\D", ""));
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
                               [--sandbox host|docker] [--cassettes <set>] [--run-id <id>]
                               [--max-parallel <n>]
        agentic resume <run-id> [--llm ...] [--approvals ...] [--reset-budgets <who>]
        agentic check-evidence <name>...      verify saved evidence in docs/evidence/<name>
        agentic publish-evidence <run-id> <name>   copy a run's evidence to docs/evidence
        agentic warmup-sandbox       fill the Docker sandbox's dependency cache (network on)
        agentic stop <run-id>        engage the kill switch (safe stop, resumable)
        agentic report <run-id>      re-render report.html from the audit log
        agentic verify-log <run-id>  verify the audit log's hash chain
        agentic metrics              reliability metrics across all runs
        agentic list                 list runs

        Scenarios: greenfield, brownfield, ambiguous, drill (scenarios/*.yaml)
        Approvals never default to automatic: without a terminal pass --approvals explicitly.
        Live/record mode needs GEMINI_API_KEY and AGENTIC_MODEL (see .env.example).""";
  }

  /**
   * Parsed command line.
   *
   * @param command sub-command
   * @param target scenario or run id
   * @param options {@code --key value} options
   */
  record Args(String command, List<String> targets, Map<String, String> options) {

    static Args parse(String[] args) {
      Map<String, String> options = new LinkedHashMap<>();
      List<String> targets = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
        if (args[i].startsWith("--")) {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i]);
          }
          options.put(args[i].substring(2), args[++i]);
        } else {
          targets.add(args[i]);
        }
      }
      return new Args(args[0], List.copyOf(targets), options);
    }

    String target() {
      if (targets.isEmpty()) {
        throw new IllegalArgumentException(command + " needs a scenario or run id");
      }
      return targets.getFirst();
    }

    Optional<String> option(String key) {
      return Optional.ofNullable(options.get(key));
    }
  }
}
