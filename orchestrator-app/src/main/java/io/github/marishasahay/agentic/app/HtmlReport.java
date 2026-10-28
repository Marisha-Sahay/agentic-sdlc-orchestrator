package io.github.marishasahay.agentic.app;

import io.github.marishasahay.agentic.core.event.EventEnvelope;
import io.github.marishasahay.agentic.core.event.Json;
import io.github.marishasahay.agentic.core.event.RunEvent;
import io.github.marishasahay.agentic.core.metrics.RunMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Self-contained HTML report of one run, rendered purely from its event log (plus the final
 * graph shape). It is the reviewable record of what the agents did, why, and under which
 * controls.
 */
final class HtmlReport {

  private HtmlReport() {}

  static String render(List<EventEnvelope> log, String mermaid, RunMetrics m) {
    String status = m.status();
    StringBuilder html = new StringBuilder(8_192);
    html.append("""
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Run Report</title><style>
        :root{--bg:#f7f7f5;--card:#fff;--fg:#1d1d1f;--muted:#6b6b70;--line:#e3e3e0;
        --ok:#1a7f4b;--bad:#c0362c;--warn:#a86400;--info:#2f5bb7;--chip:#f0f0ec}
        @media (prefers-color-scheme:dark){:root{--bg:#141416;--card:#1d1d20;--fg:#ececef;
        --muted:#9a9aa2;--line:#2e2e33;--ok:#4cc38a;--bad:#ff6b5e;--warn:#e0a040;--info:#7aa2ff;
        --chip:#2a2a2f}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
        font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Inter,sans-serif}
        main{max-width:1180px;margin:0 auto;padding:24px 16px 64px}
        h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:32px 0 10px}
        .muted{color:var(--muted)}.card{background:var(--card);border:1px solid var(--line);
        border-radius:10px;padding:16px;overflow-x:auto}
        .kpis{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:10px}
        .kpi{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px}
        .kpi b{display:block;font-size:20px;font-variant-numeric:tabular-nums}
        .kpi span{color:var(--muted);font-size:12px}
        table{width:100%;border-collapse:collapse;font-size:13px}
        th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);
        vertical-align:top}th{color:var(--muted);font-weight:600}
        .badge{display:inline-block;padding:1px 8px;border-radius:99px;font-size:12px;
        font-weight:600;background:var(--chip)}
        .ok{color:var(--ok)}.bad{color:var(--bad)}.warn{color:var(--warn)}.info{color:var(--info)}
        pre{white-space:pre-wrap;word-break:break-word;font-size:12px;margin:4px 0 0;
        max-height:260px;overflow:auto}details summary{cursor:pointer;color:var(--muted)}
        code{font-size:12px}</style></head><body><main>
        """);
    html.append("<h1>Agentic SDLC run: ").append(esc(m.scenario())).append("</h1>")
        .append("<div class=muted>").append(esc(m.runId())).append(" &middot; status ")
        .append("<span class=\"badge ").append(statusClass(status)).append("\">")
        .append(esc(status)).append("</span> &middot; audit chain ")
        .append(m.auditChainValid() ? "<span class=ok>verified</span>"
            : "<span class=bad>BROKEN</span>")
        .append(finishReason(log)).append("</div>");

    html.append("<h2>Reliability metrics</h2><div class=kpis>");
    kpi(html, pct(m.nodeSuccessRate()), "node success rate");
    kpi(html, pct(m.firstPassYield()), "first-pass yield");
    kpi(html, m.retries() + " (" + pct(m.retryRate()) + ")", "retries / rate");
    kpi(html, String.valueOf(m.rollbacks()), "rollbacks");
    kpi(html, String.valueOf(m.fallbacks()), "fallback activations");
    kpi(html, m.mttrMs() < 0 ? "n/a" : dur(m.mttrMs()), "MTTR (fail to recover)");
    kpi(html, dur(m.endToEndMs()), "end-to-end latency");
    kpi(html, dur(m.machineMs()), "machine time");
    kpi(html, dur(m.humanWaitMs()), "human wait");
    kpi(html, m.approvalsRequested() + " / " + m.approvalsRejected(), "approvals / rejections");
    kpi(html, String.valueOf(m.policyBlocks()), "policy blocks");
    kpi(html, String.valueOf(m.invalidations()), "re-plan invalidations");
    kpi(html, m.agentCalls() + "", "model calls");
    kpi(html, (m.inputTokens() + m.outputTokens()) + "", "tokens");
    html.append("</div>");

    html.append("<h2>Workflow graph</h2><div class=card><pre class=mermaid>")
        .append(esc(mermaid)).append("</pre><div class=muted style=\"font-size:12px\">")
        .append("green: accepted first time &middot; green with amber border: accepted after ")
        .append("retries or fallback &middot; red: failed &middot; grey: skipped &middot; ")
        .append("amber: not run</div></div>");

    html.append("<h2>Human checkpoints</h2><div class=card><table><tr><th>Node</th>"
        + "<th>Why</th><th>Decision</th><th>Comment</th><th>Amended</th></tr>");
    String pendingReasons = "";
    for (EventEnvelope e : log) {
      if (e.event() instanceof RunEvent.ApprovalRequested r) {
        pendingReasons = String.join("<br>", r.reasons().stream().map(HtmlReport::esc).toList());
      } else if (e.event() instanceof RunEvent.ApprovalResolved r) {
        html.append("<tr><td>").append(esc(r.nodeId())).append("</td><td>")
            .append(pendingReasons).append("</td><td class=")
            .append(r.approved() ? "ok>approved" : "bad>rejected").append(" by ")
            .append(esc(r.reviewer())).append("</td><td>").append(esc(r.comment()))
            .append("</td><td>").append(esc(String.join(", ", r.amendedArtifacts())))
            .append("</td></tr>");
      }
    }
    html.append("</table></div>");

    html.append("<h2>Decisions and lineage</h2><div class=card><table><tr><th>Node</th>"
        + "<th>Decision</th><th>Rationale</th><th>Alternatives</th><th>Based on</th></tr>");
    for (EventEnvelope e : log) {
      if (e.event() instanceof RunEvent.DecisionRecorded d) {
        html.append("<tr><td>").append(esc(d.nodeId())).append("</td><td><b>")
            .append(esc(d.title())).append("</b><br>").append(esc(d.choice()))
            .append("</td><td>").append(esc(d.rationale())).append("</td><td>")
            .append(esc(String.join("; ", d.alternatives()))).append("</td><td><code>")
            .append(esc(String.join(" ", d.basedOn()))).append("</code></td></tr>");
      }
    }
    html.append("</table></div>");

    html.append("<h2>Gates, policy and recovery</h2><div class=card><table><tr><th>Time (UTC)</th>"
        + "<th>Node</th><th>Event</th><th>Detail</th></tr>");
    for (EventEnvelope e : log) {
      String row = switch (e.event()) {
        case RunEvent.GateEvaluated g -> cell(g.passed() ? "ok" : "bad",
            (g.passed() ? "PASS " : "FAIL ") + g.phase() + " gate " + g.gate(), g.evidence());
        case RunEvent.PolicyEvaluated p -> cell("warn", "policy findings",
            String.join("\n", p.findings().stream().map(f -> f.severity() + " [" + f.rule()
                + "] " + f.message() + (f.path() == null ? "" : " " + f.path())).toList()));
        case RunEvent.AttemptFailed f -> cell("bad", "attempt " + f.attempt() + " rejected: "
            + f.reason(), f.feedback());
        case RunEvent.RollbackPerformed r -> cell("warn", "rollback", r.reason() + " -> "
            + r.toCheckpoint());
        case RunEvent.FallbackActivated f -> cell("warn", "fallback", f.reason());
        case RunEvent.NodeInvalidated i -> cell("info", "re-planned", i.cause());
        case RunEvent.GraphExpanded g -> cell("info", "graph expanded", String.join(", ",
            g.added()));
        case RunEvent.StopRequested s -> cell("bad", "safe stop", s.reason());
        default -> null;
      };
      if (row != null) {
        html.append("<tr><td class=muted>").append(e.at().toString(), 11, 19).append("</td><td>")
            .append(esc(e.event().nodeId() == null ? "run" : e.event().nodeId())).append("</td>")
            .append(row).append("</tr>");
      }
    }
    html.append("</table></div>");

    html.append("<h2>Artifacts</h2><div class=card><table><tr><th>Artifact</th><th>Version"
        + "</th><th>Produced by</th><th>SHA-256</th></tr>");
    for (EventEnvelope e : log) {
      if (e.event() instanceof RunEvent.ArtifactProduced a) {
        html.append("<tr><td><a href=\"artifacts/").append(esc(a.path())).append("\">")
            .append(esc(a.key())).append("</a></td><td>v").append(a.version())
            .append("</td><td>").append(esc(a.producedBy())).append("</td><td><code>")
            .append(a.sha256(), 0, 16).append("</code></td></tr>");
      }
    }
    html.append("</table></div>");

    html.append("<h2>Stage durations</h2><div class=card><table><tr><th>Stage</th>"
        + "<th>Total node time</th></tr>");
    for (Map.Entry<String, Long> s : m.stageDurationsMs().entrySet()) {
      html.append("<tr><td>").append(esc(s.getKey().toLowerCase().replace('_', ' ')))
          .append("</td><td>").append(dur(s.getValue())).append("</td></tr>");
    }
    html.append("</table></div>");

    html.append("<h2>Audit log</h2><details class=card><summary>").append(log.size())
        .append(" hash-chained events (events.jsonl)</summary><pre>");
    for (EventEnvelope e : log) {
      html.append(e.seq()).append(' ').append(e.hash(), 0, 12).append(' ')
          .append(esc(Json.write(e.event()))).append('\n');
    }
    html.append("</pre></details>");
    html.append("""
        </main><script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js">
        </script><script>
        mermaid.initialize({startOnLoad:true,securityLevel:'strict',
          theme:matchMedia('(prefers-color-scheme: dark)').matches?'dark':'default'});
        </script></body></html>
        """);
    return html.toString();
  }

  /**
   * Mermaid class per node: its final state, or {@code recovered} for nodes that were accepted
   * only after a retry or fallback, so the graph shows where the run had to recover.
   *
   * @param log run events
   * @param id node id
   * @param state final state name
   * @return Mermaid class name
   */
  static String nodeClass(List<EventEnvelope> log, String id, String state) {
    if (!state.equals("SUCCEEDED")) {
      return state.toLowerCase();
    }
    boolean recovered = log.stream().map(EventEnvelope::event)
        .anyMatch(e -> e instanceof RunEvent.AttemptFailed f && f.nodeId().equals(id));
    return recovered ? "recovered" : "succeeded";
  }

  static String mermaidClasses() {
    return """
          classDef succeeded fill:#d6f5e3,stroke:#1a7f4b,color:#0b3d22
          classDef recovered fill:#d6f5e3,stroke:#d08700,stroke-width:3px,color:#0b3d22
          classDef failed fill:#fde0dc,stroke:#c0362c,color:#5c120c
          classDef skipped fill:#eeeeee,stroke:#999,color:#555,stroke-dasharray:4 3
          classDef pending fill:#fff4d6,stroke:#a86400,color:#4d2e00
          classDef running fill:#dfe8ff,stroke:#2f5bb7,color:#12275a
        """;
  }

  private static String finishReason(List<EventEnvelope> log) {
    for (int i = log.size() - 1; i >= 0; i--) {
      if (log.get(i).event() instanceof RunEvent.RunFinished f && !f.reason().isBlank()) {
        return " &middot; " + esc(f.reason());
      }
    }
    return "";
  }

  private static String cell(String cssClass, String title, String detail) {
    String body = detail == null ? "" : detail;
    String rendered = body.length() > 160 || body.contains("\n")
        ? "<details><summary>" + esc(body.lines().findFirst().orElse("")) + "</summary><pre>"
            + esc(body) + "</pre></details>"
        : esc(body);
    return "<td class=" + cssClass + ">" + esc(title) + "</td><td>" + rendered + "</td>";
  }

  private static void kpi(StringBuilder html, String value, String label) {
    html.append("<div class=kpi><b>").append(esc(value)).append("</b><span>").append(esc(label))
        .append("</span></div>");
  }

  private static String statusClass(String status) {
    return switch (status) {
      case "SUCCEEDED" -> "ok";
      case "FAILED" -> "bad";
      default -> "warn";
    };
  }

  private static String pct(double value) {
    return Math.round(value * 100) + "%";
  }

  static String dur(long millis) {
    Duration d = Duration.ofMillis(millis);
    return d.toMinutes() > 0 ? d.toMinutes() + "m " + d.toSecondsPart() + "s"
        : d.toSeconds() > 0 ? d.toSeconds() + "." + (d.toMillisPart() / 100) + "s" : millis + "ms";
  }

  static String esc(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
