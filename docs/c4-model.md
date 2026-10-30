# C4 model

The system at the four levels of the [C4 model](https://c4model.com): **context**, **containers**,
**components**, and **dynamic** views of the workflow. The diagrams use C4 notation (element type
in brackets, standard C4 colours, boundaries as dashed groups) drawn with Mermaid flowcharts, which
lay out far more legibly than Mermaid's experimental C4 renderer. Dynamic views use the
sequence-diagram form the C4 model allows.

Legend: <span style="color:#08427b">■</span> person ·
<span style="color:#1168bd">■</span> the system ·
<span style="color:#438dd5">■</span> container ·
<span style="color:#85bbf0">■</span> component ·
<span style="color:#999999">■</span> external system

## Level 1: System context

Who uses the system and what it depends on.

```mermaid
flowchart TB
  engineer(["<b>Engineer</b><br/>[Person]<br/><small>Starts, stops and resumes runs;<br/>reads reports</small>"])
  stakeholder(["<b>Stakeholder</b><br/>[Person]<br/><small>States the requirement;<br/>answers clarifying questions</small>"])
  reviewer(["<b>Reviewer</b><br/>[Person]<br/><small>Signs off design, high-impact<br/>changes and release</small>"])

  agentic["<b>Agentic SDLC Orchestrator</b><br/>[Software system]<br/><small>Turns a requirement into a reviewable,<br/>tested patch under governance</small>"]

  llm["<b>Gemini API</b><br/>[External system]<br/><small>LLM used by the agents</small>"]
  toolchain["<b>Build toolchain</b><br/>[External system]<br/><small>Maven, JDK 21, Docker,<br/>Testcontainers PostgreSQL</small>"]
  codebase[("<b>Target codebase</b><br/>[External system]<br/><small>url-shortener baseline or<br/>greenfield scaffold</small>")]

  engineer -- "Runs scenarios<br/>[agentic CLI]" --> agentic
  stakeholder -- "Requirement;<br/>clarification answers" --> agentic
  reviewer -- "Approve / reject / amend<br/>[console or scripted]" --> agentic
  agentic -- "Structured prompts<br/>[HTTPS, JSON schema]" --> llm
  agentic -- "Runs gates<br/>[mvn compile / test / verify]" --> toolchain
  agentic -- "Copies baseline;<br/>emits release patch" --> codebase

  classDef person fill:#08427b,stroke:#073b6f,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#999999,stroke:#6b6b6b,color:#fff
  class engineer,stakeholder,reviewer person
  class agentic system
  class llm,toolchain,codebase external
```

| Element | Responsibility |
|---|---|
| Engineer | Operates runs: `agentic run / stop / resume / report / metrics / verify-log` |
| Stakeholder | Owns the requirement; answers the open questions at the clarification checkpoint |
| Reviewer | Human authority at checkpoints; can reject with feedback or amend any artifact |
| Agentic SDLC Orchestrator | Orchestrates SDLC agents under policy, gates, approvals and an audit log |
| Gemini API | Model behind every agent in live/record mode; replaced by cassettes in replay |
| Build toolchain | Deterministic gates: compiler, unit and integration tests, Checkstyle, coverage |
| Target codebase | Read-only input; each run works on a private copy and outputs `release.patch` |

## Level 2: Containers

One JVM process plus file-based stores per run. "Container" is meant in the C4 sense (a runtime
unit or data store), not a Docker container.

```mermaid
flowchart TB
  user(["<b>Engineer / Reviewer</b><br/>[Person]"])

  subgraph sys["Agentic SDLC Orchestrator"]
    direction TB
    cli["<b>agentic CLI</b><br/>[Container: Java 21 JVM process]<br/><small>Workflow templates, graph engine, agents,<br/>console approvals, live progress, reporting</small>"]
    scenarios[("<b>Scenario definitions</b><br/>[YAML + scaffold]<br/><small>requirement, baseline,<br/>scripted reviewer decisions</small>")]
    cassettes[("<b>Cassette store</b><br/>[JSON files]<br/><small>recorded / hand-authored<br/>model replies</small>")]
    eventlog[("<b>Event log</b><br/>[JSONL, hash-chained]<br/><small>source of truth: audit,<br/>lineage, metrics, resume</small>")]
    artifacts[("<b>Artifact store</b><br/>[versioned files]<br/><small>specs, design, plan;<br/>SHA-256 per version</small>")]
    workspace[("<b>Run workspace</b><br/>[private git repo]<br/><small>baseline tag + one checkpoint<br/>per accepted change</small>")]
    report[("<b>Run report</b><br/>[HTML, metrics.json, patch]<br/><small>projections of the log<br/>and workspace</small>")]
  end

  llm["<b>Gemini API</b><br/>[External system]"]
  toolchain["<b>Maven + Docker</b><br/>[External system]<br/><small>gate execution,<br/>Testcontainers PostgreSQL</small>"]

  user -- "Commands, approvals<br/>[terminal]" --> cli
  cli -- "Loads" --> scenarios
  cli -- "Records / replays" --> cassettes
  cli -- "Live calls<br/>[LangChain4j, HTTPS]" --> llm
  cli -- "Appends every<br/>state change" --> eventlog
  cli -- "Stores / reads<br/>versions" --> artifacts
  cli -- "Apply, checkpoint,<br/>rollback [git]" --> workspace
  cli -- "Runs gates in<br/>the workspace" --> toolchain
  cli -- "Renders" --> report

  classDef person fill:#08427b,stroke:#073b6f,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef external fill:#999999,stroke:#6b6b6b,color:#fff
  class user person
  class cli,scenarios,cassettes,eventlog,artifacts,workspace,report container
  class llm,toolchain external
  style sys fill:none,stroke:#1168bd,stroke-dasharray:6 4,color:#1168bd
```

| Container | Technology | Notes |
|---|---|---|
| agentic CLI | Java 21; shaded jar of `orchestrator-app` + `orchestrator-agents` + `orchestrator-core` | Single process; virtual threads for parallel nodes |
| Scenario definitions | `scenarios/*.yaml`, `scenarios/scaffolds/` | Versioned with the code |
| Cassette store | `cassettes/<scenario>/*.json` | Keyed by `node/e<execution>/attempt-<n>/<strategy>/<agent>` |
| Event log | `.runs/<id>/events.jsonl` | `hash = SHA-256(prev, seq, runId, time, event)`; checked by `verify-log` |
| Artifact store | `.runs/<id>/artifacts/<key>/v<n>.txt` | Content-addressed; drives re-planning |
| Run workspace | `.runs/<id>/workspace/` (git) | The developer's repository is never touched |
| Run report | `.runs/<id>/report.html`, `metrics.json`, `release.patch` | Pure projections of the log and workspace |

## Level 3: Components of the agentic CLI

Inside the process, by module. Dependencies point one way:
`orchestrator-app → orchestrator-agents → orchestrator-core`.

```mermaid
flowchart TB
  subgraph app["orchestrator-app"]
    direction LR
    cliMain["<b>AgenticCli</b><br/>[Component: composition root]<br/><small>commands, wiring, Ctrl-C</small>"]
    templates["<b>WorkflowTemplates</b><br/>[Component: graph factory]<br/><small>greenfield / brownfield /<br/>ambiguous graphs</small>"]
    policyCfg["<b>GovernancePolicy</b><br/>[Component: policy config]<br/><small>write scope, protected files,<br/>change-control rules</small>"]
    approvals["<b>Console / scripted approvals</b><br/>[Component: ApprovalGateway]<br/><small>approve, reject with feedback,<br/>amend artifacts</small>"]
    reporting["<b>HtmlReport, ProgressPrinter</b><br/>[Component: observers]<br/><small>live log and run report</small>"]
  end

  subgraph core["orchestrator-core"]
    direction LR
    engine["<b>WorkflowEngine</b><br/>[Component: scheduler]<br/><small>parallel dispatch, joins, skips,<br/>expansion, re-planning, budgets,<br/>resume</small>"]
    runner["<b>NodeRunner</b><br/>[Component: node lifecycle]<br/><small>entry gates, snapshot, policy,<br/>authorization, stale check, apply,<br/>exit gates, acceptance,<br/>checkpoint / rollback</small>"]
    dag["<b>WorkflowGraph</b><br/>[Component: validated DAG]"]
    policy["<b>PolicyEngine + rules</b><br/>[Component: guardrails]<br/><small>BLOCK / REQUIRE_APPROVAL,<br/>fail-closed</small>"]
    gates["<b>Gates</b><br/>[Component]<br/><small>command, required-artifact</small>"]
    gitws["<b>GitWorkspace</b><br/>[Component: checkpoints]"]
    store["<b>ArtifactStore</b><br/>[Component: versions]"]
    audit["<b>EventLog + MetricsCalculator</b><br/>[Component: audit + metrics]"]
  end

  subgraph agents["orchestrator-agents"]
    direction LR
    sdlc["<b>SDLC agents</b><br/>[Component: NodeTasks]<br/><small>requirements, impact, architect,<br/>planner, coder, tests, docs,<br/>security, verify, release</small>"]
    agentGates["<b>Agent gates</b><br/>[Component: validators]<br/><small>contract, plan validity, grounding,<br/>API compatibility, docs coverage</small>"]
    indexer["<b>CodebaseIndexer</b><br/>[Component: JavaParser]"]
    seam["<b>AgentSupport + CassetteLlmClient</b><br/>[Component: model seam]<br/><small>prompts, feedback,<br/>record / replay</small>"]
    l4j["<b>LangChain4jLlmClient</b><br/>[Component: LangChain4j]<br/><small>Gemini ChatModel +<br/>JSON schema</small>"]
  end

  gemini["<b>Gemini API</b><br/>[External system]"]

  cliMain -- "builds graph" --> templates
  templates -- "nodes, gates" --> dag
  cliMain -- "run / resume" --> engine
  policyCfg -- "rules" --> policy
  engine -- "schedules" --> dag
  engine -- "executes node<br/>[virtual thread]" --> runner
  runner -- "execute(context)" --> sdlc
  runner -- "evaluate proposal" --> policy
  runner -- "exit gates" --> gates
  runner -- "exit gates" --> agentGates
  runner -- "request decision" --> approvals
  runner -- "apply / commit /<br/>rollback" --> gitws
  runner -- "publish artifacts" --> store
  runner -- "append events" --> audit
  sdlc -- "ask()" --> seam
  sdlc -- "index codebase" --> indexer
  seam -- "live / record" --> l4j
  l4j -- "HTTPS" --> gemini
  audit -. "subscribe" .-> reporting

  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef external fill:#999999,stroke:#6b6b6b,color:#fff
  class cliMain,templates,policyCfg,approvals,reporting,engine,runner,dag,policy,gates,gitws,store,audit,sdlc,agentGates,indexer,seam,l4j component
  class gemini external
  style app fill:none,stroke:#438dd5,stroke-dasharray:6 4
  style core fill:none,stroke:#438dd5,stroke-dasharray:6 4
  style agents fill:none,stroke:#438dd5,stroke-dasharray:6 4
```

## Dynamic view 1: one run, end to end (container level)

The SDLC workflow as it executes: the order in which containers and people interact during a
brownfield run.

```mermaid
sequenceDiagram
  autonumber
  actor E as Engineer
  participant CLI as agentic CLI<br/>[container]
  participant S as Scenarios +<br/>cassettes [stores]
  participant W as Run workspace<br/>[git]
  participant L as Event log +<br/>artifacts [stores]
  participant M as Gemini API<br/>[external]
  participant T as Maven + Docker<br/>[external]
  actor R as Reviewer

  E->>CLI: agentic run brownfield
  CLI->>S: load scenario, workflow template inputs
  CLI->>W: copy baseline, git init, tag baseline
  CLI->>L: RunStarted (settings, requirement hash)
  par requirements
    CLI->>M: requirements agent (structured prompt)
  and codebase scan
    CLI->>W: JavaParser index (no model)
  end
  CLI->>M: impact analysis, grounded against the index
  CLI->>M: architect: design + OpenAPI contract
  CLI->>R: design sign-off (diff, gate evidence)
  R-->>CLI: approve / reject with comment / amend
  CLI->>M: planner: tasks with dependencies
  Note over CLI: graph expands: one node per task
  par each implementation task
    CLI->>M: coder proposal
    CLI->>T: mvn compile (exit gate)
    CLI->>W: checkpoint commit, or rollback + retry
  end
  par tests then verify then security review
    CLI->>M: test author (mvn test), bounded repair (mvn verify), security reviewer
  and docs
    CLI->>M: tech writer (docs coverage; may only write docs)
  end
  CLI->>T: final validation on tree T: mvn verify, scanner, contract, docs
  CLI->>R: release sign-off on tree T (patch, traceability, notes, evidence)
  R-->>CLI: approve
  CLI->>L: RunFinished, every step above was logged as events
  CLI->>E: report.html, metrics, release.patch
```

## Dynamic view 2: one governed node (component level)

What happens, in order, inside one implementation node. This is where the controls act; the full
lifecycle (fallback, timeouts, safe stop) is in
[architecture §3.3](architecture.md#33-one-attempt).

```mermaid
sequenceDiagram
  autonumber
  participant EN as WorkflowEngine<br/>[component]
  participant NR as NodeRunner<br/>[component]
  participant CA as CoderAgent<br/>[component]
  participant AS as ArtifactStore<br/>[component]
  participant GM as Gemini API<br/>[external]
  participant PE as PolicyEngine<br/>[component]
  participant GW as GitWorkspace<br/>[component]
  participant MV as Maven<br/>[external]
  actor RV as Reviewer
  participant EL as EventLog<br/>[component]

  EN->>NR: dependencies succeeded: run(node) on a virtual thread
  NR->>CA: execute(context: feedback, snapshot of base revision B)
  CA->>AS: read plan, design, contract (versions recorded)
  CA->>GM: structured prompt, reply must match CodeProposal
  CA-->>NR: proposal P: file changes, decisions
  NR->>PE: evaluate P against B (nothing written yet)
  alt BLOCK
    NR->>EL: PolicyEvaluated, AttemptFailed
    NR->>NR: retry with findings as feedback
  else build file changed (REQUIRE_AUTHORIZATION)
    NR->>RV: authorize P (preview diff; nothing written or built)
    RV-->>NR: authorize / refuse
  end
  NR->>GW: lock; files of P changed since B? reject as stale
  NR->>GW: apply P, tree hash T
  NR->>MV: mvn compile on T
  alt gate fails
    NR->>GW: rollback to checkpoint
    NR->>EL: GateEvaluated (FAIL, T), RollbackPerformed, AttemptFailed
    NR->>NR: retry with compiler output as feedback
  else gate passes
    opt REQUIRE_APPROVAL (e.g. new migration)
      NR->>RV: accept P on T (reasons, diff, gate evidence)
      RV-->>NR: approve / reject / amend (amendments validated)
    end
    NR->>GW: still T and inputs current? checkpoint commit, unlock
    NR->>EL: CheckpointCreated, ArtifactProduced, NodeSucceeded (commit point)
  end
  NR-->>EN: outcome + consumed input hashes
  EN->>EN: expand graph, invalidate stale consumers, schedule next
```

## Supplementary: the target service (url-shortener)

```mermaid
flowchart LR
  client(["<b>API client / browser</b><br/>[Person]"])
  subgraph svc["url-shortener"]
    direction LR
    app["<b>Spring Boot service</b><br/>[Container: Java 21, Spring Boot 4.1]<br/><small>link API, 302 redirects, URL and alias<br/>policies, rate limiting, ProblemDetail</small>"]
    cache[("<b>Redirect cache</b><br/>[Caffeine, in process]<br/><small>code to target + expiry</small>")]
    db[("<b>PostgreSQL 17</b><br/>[Container: Flyway, JdbcClient]<br/><small>links; unique code; soft delete</small>")]
  end
  client -- "POST /api/v1/links<br/>GET /{code} [HTTPS/JSON]" --> app
  app -- "resolve on redirect" --> cache
  app -- "SQL [JDBC]" --> db

  classDef person fill:#08427b,stroke:#073b6f,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  class client person
  class app,cache,db container
  style svc fill:none,stroke:#1168bd,stroke-dasharray:6 4,color:#1168bd
```
