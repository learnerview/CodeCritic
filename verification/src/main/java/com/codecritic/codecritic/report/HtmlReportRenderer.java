package com.codecritic.report;

import com.codecritic.change.MethodChange;
import com.codecritic.exec.BaselineExecution;
import com.codecritic.exec.DifferentialExecution;
import com.codecritic.exec.DifferentialVerdict;
import com.codecritic.exec.ExecutionStatus;
import com.codecritic.exec.TestFailure;
import com.codecritic.exec.TestSuiteResult;
import com.codecritic.impact.MethodImpact;
import com.codecritic.impact.RiskBand;

import java.util.List;
import java.util.Locale;

public final class HtmlReportRenderer {

    public String render(VerificationReport report) {
        StringBuilder html = new StringBuilder(32_768);
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>CodeCritic Change Impact Report</title>
                  <style>
                    :root {
                      color-scheme: dark;
                      --bg: #0b1020;
                      --panel: #121a2f;
                      --panel-2: #0f1628;
                      --border: #243047;
                      --text: #e7ecf7;
                      --muted: #9aa8c2;
                      --accent: #7c9cff;
                      --low: #35c48a;
                      --medium: #f0b74e;
                      --high: #ff6b7a;
                      --shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      background:
                        radial-gradient(900px 500px at 15% -10%, rgba(124, 156, 255, 0.18), transparent 60%),
                        radial-gradient(700px 400px at 100% 0%, rgba(53, 196, 138, 0.10), transparent 55%),
                        var(--bg);
                      color: var(--text);
                      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      line-height: 1.55;
                    }
                    .page { width: min(1180px, calc(100% - 40px)); margin: 0 auto; padding: 44px 0 60px; }
                    .hero { border: 1px solid var(--border); background: linear-gradient(135deg, rgba(124,156,255,0.12), rgba(18,26,47,0.85)); border-radius: 22px; padding: 30px 32px; box-shadow: var(--shadow); }
                    .eyebrow { color: var(--accent); font-size: 12px; font-weight: 800; letter-spacing: 0.16em; text-transform: uppercase; }
                    h1 { margin: 10px 0 8px; font-size: clamp(28px, 4vw, 44px); line-height: 1.1; letter-spacing: -0.03em; }
                    .subtitle { margin: 0; color: var(--muted); font-size: 16px; }
                    .meta { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-top: 18px; color: var(--muted); font-size: 13px; }
                    .meta code { color: var(--text); }
                    .cards { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
                    .card { border: 1px solid var(--border); background: var(--panel); border-radius: 16px; padding: 16px 18px; }
                    .card .label { color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; }
                    .card .value { margin-top: 6px; font-size: 26px; font-weight: 800; letter-spacing: -0.02em; }
                    .card.low .value { color: var(--low); }
                    .card.medium .value { color: var(--medium); }
                    .card.high .value { color: var(--high); }
                    .panel { border: 1px solid var(--border); background: rgba(18, 26, 47, 0.88); border-radius: 18px; padding: 22px 24px; margin-top: 16px; }
                    .panel h2 { margin: 0 0 12px; font-size: 18px; letter-spacing: -0.01em; }
                    .panel h3 { margin: 18px 0 8px; font-size: 14px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em; }
                    .summary { margin: 0; padding-left: 20px; color: var(--muted); }
                    .summary li { margin: 6px 0; }
                    .summary strong { color: var(--text); }
                    .files { display: flex; flex-wrap: wrap; gap: 8px; }
                    .file { border: 1px solid var(--border); background: var(--panel-2); border-radius: 999px; padding: 6px 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; color: var(--muted); }
                    .table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 12px; }
                    table { width: 100%; border-collapse: collapse; min-width: 820px; }
                    th, td { padding: 12px 14px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
                    th { background: var(--panel-2); color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; }
                    tr:last-child td { border-bottom: 0; }
                    code, .mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12.5px; }
                    .badge { display: inline-block; border-radius: 999px; padding: 3px 9px; font-size: 11px; font-weight: 800; letter-spacing: 0.04em; text-transform: uppercase; border: 1px solid currentColor; }
                    .badge.low { color: var(--low); background: rgba(53, 196, 138, 0.08); }
                    .badge.medium { color: var(--medium); background: rgba(240, 183, 78, 0.08); }
                    .badge.high { color: var(--high); background: rgba(255, 107, 122, 0.08); }
                    .badge.neutral { color: var(--muted); background: rgba(154, 168, 194, 0.08); }
                    .muted { color: var(--muted); }
                    pre.output { margin: 8px 0 0; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--panel-2); color: var(--text); white-space: pre-wrap; overflow: auto; max-height: 260px; }
                    .details { margin-top: 6px; color: var(--muted); font-size: 12.5px; }
                    .pill { display: inline-block; margin: 3px 4px 0 0; border-radius: 6px; padding: 2px 7px; background: var(--panel-2); border: 1px solid var(--border); font-size: 11.5px; }
                    .risk-factors { margin: 6px 0 0; padding-left: 18px; color: var(--muted); font-size: 12.5px; }
                    .risk-factors li { margin: 2px 0; }
                    .note { border-color: rgba(124, 156, 255, 0.35); background: rgba(124, 156, 255, 0.06); }
                    .note p { margin: 0; color: var(--muted); }
                    .warnings { border-color: rgba(240, 183, 78, 0.35); background: rgba(240, 183, 78, 0.06); }
                    footer { margin-top: 22px; color: var(--muted); font-size: 12px; text-align: center; }
                    @media (max-width: 900px) {
                      .cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                    }
                  </style>
                </head>
                <body>
                <main class="page">
                """);

        html.append("<header class=\"hero\">");
        html.append("<div class=\"eyebrow\">CodeCritic / change impact</div>");
        html.append("<h1>Prove the change.</h1>");
        html.append("<p class=\"subtitle\">Deterministic callable-level impact map for <code>")
                .append(esc(report.baseRef()))
                .append("</code> to <code>")
                .append(esc(report.headRef()))
                .append("</code>.</p>");
        html.append("<div class=\"meta\">");
        html.append("<span>Repository <code>").append(esc(report.repository())).append("</code></span>");
        html.append("<span>Generated <code>").append(esc(report.generatedAt())).append("</code></span>");
        html.append("<span>Tool <code>v").append(esc(report.toolVersion())).append("</code></span>");
        html.append("</div></header>");

        appendCards(html, report);
        appendSummary(html, report);
        appendBaseline(html, report.baseline());
        appendFiles(html, report);
        appendImpacts(html, report);
        appendEvidence(html, report);
        appendWarnings(html, report);

        html.append("""
                <section class="panel note">
                  <h2>Scope</h2>
                  <p>This report is the deterministic change-impact layer. When tests are enabled, it runs each revision's existing suite inside Docker. When a candidate test is supplied, it injects that test into both revisions and classifies the result as F2P, P2F, P2P, or F2F. It does not generate tests with an LLM yet, and it does not claim that the change is correct.</p>
                </section>
                <footer>CodeCritic impact map. Caller counts are name-based heuristics, not symbol-resolved reachability.</footer>
                </main>
                </body>
                </html>
                """);
        return html.toString();
    }

    private void appendCards(StringBuilder html, VerificationReport report) {
        long publicApi = report.methodImpacts().stream()
                .filter(impact -> impact.change().publicApi())
                .count();
        long affectedTests = report.methodImpacts().stream()
                .filter(impact -> !impact.affectedTests().isEmpty())
                .count();

        html.append("<section class=\"cards\">");
        appendCard(html, "Risk", report.riskBand() + " " + report.riskScore() + "/100", riskClass(report.riskBand()));
        appendCard(html, "Callable changes", String.valueOf(report.methodImpacts().size()), "");
        appendCard(html, "Public API", String.valueOf(publicApi), "");
        appendCard(html, "With affected tests", affectedTests + "/" + report.methodImpacts().size(), "");
        appendCard(html, "Java files", String.valueOf(report.changedFiles().size()), "");
        html.append("</section>");
    }

    private void appendCard(StringBuilder html, String label, String value, String tone) {
        html.append("<div class=\"card ").append(tone).append("\">");
        html.append("<div class=\"label\">").append(esc(label)).append("</div>");
        html.append("<div class=\"value\">").append(esc(value)).append("</div>");
        html.append("</div>");
    }

    private void appendSummary(StringBuilder html, VerificationReport report) {
        html.append("<section class=\"panel\"><h2>Decision summary</h2><ul class=\"summary\">");
        for (String line : report.summary()) {
            html.append("<li>").append(esc(line)).append("</li>");
        }
        html.append("</ul></section>");
    }

    private void appendBaseline(StringBuilder html, BaselineExecution baseline) {
        if (baseline == null) {
            return;
        }

        DifferentialExecution differential = baseline.differential();
        html.append("<section class=\"panel\"><h2>")
                .append(differential == null ? "Baseline test execution" : "Differential test execution")
                .append("</h2>");
        if (differential == null) {
            html.append("<p class=\"muted\">Existing suites only. Each revision runs in an isolated git worktree inside a Docker sandbox, with a timeout and cleanup. A baseline is not a differential oracle: the next layer must fail the same test on base and pass it on head.</p>");
        } else {
            html.append("<p class=\"muted\">The candidate test is copied into the same project-relative path in both revisions, then each revision runs only that test in an isolated Docker worktree.</p>");
            html.append("<div class=\"meta\"><span>Candidate <code>")
                    .append(esc(differential.testPath()))
                    .append("</code></span><span>Class <code>")
                    .append(esc(differential.testClass()))
                    .append("</code></span><span>Verdict <span class=\"badge ")
                    .append(verdictClass(differential.verdict()))
                    .append("\">")
                    .append(esc(differential.verdict().name()))
                    .append("</span></span><span>Stability check <code>")
                    .append(differential.stabilityChecked() ? "on" : "off")
                    .append("</code></span></div>");
        }

        if (baseline.setupError() != null) {
            html.append("<div class=\"panel warnings\"><p>")
                    .append(esc(baseline.setupError()))
                    .append("</p></div></section>");
            return;
        }

        html.append("<div class=\"table-wrap\"><table><thead><tr>");
        html.append("<th>Revision</th><th>Status</th><th>Tests</th><th>Failures</th>");
        html.append("<th>Errors</th><th>Skipped</th><th>Time</th><th>Adapter</th>");
        html.append("</tr></thead><tbody>");
        appendBaselineRow(html, "base", baseline.base());
        appendBaselineRow(html, "head", baseline.head());
        html.append("</tbody></table></div>");

        appendBaselineDetails(html, "base", baseline.base());
        appendBaselineDetails(html, "head", baseline.head());
        if (differential != null) {
            appendDifferentialDetails(html, differential);
        }
        html.append("</section>");
    }

    private void appendDifferentialDetails(StringBuilder html, DifferentialExecution differential) {
        if (differential.stabilityChecked()
                && (differential.baseRepeat() != null || differential.headRepeat() != null)) {
            html.append("<h3>Stability check</h3>");
            html.append("<div class=\"table-wrap\"><table><thead><tr>");
            html.append("<th>Revision</th><th>Status</th><th>Tests</th><th>Failures</th>");
            html.append("<th>Errors</th><th>Skipped</th><th>Time</th><th>Adapter</th>");
            html.append("</tr></thead><tbody>");
            appendBaselineRow(html, "base repeat", differential.baseRepeat());
            appendBaselineRow(html, "head repeat", differential.headRepeat());
            html.append("</tbody></table></div>");
        }

        if (!differential.warnings().isEmpty()) {
            html.append("<ul class=\"summary\">");
            for (String warning : differential.warnings()) {
                html.append("<li>").append(esc(warning)).append("</li>");
            }
            html.append("</ul>");
        }
    }

    private void appendBaselineRow(StringBuilder html, String label, TestSuiteResult result) {
        html.append("<tr>");
        html.append("<td><code>").append(esc(label)).append("</code>");
        if (result != null && !result.revision().isBlank()) {
            html.append("<div class=\"details\">").append(esc(result.revision())).append("</div>");
        }
        html.append("</td>");

        if (result == null) {
            html.append("<td colspan=\"7\" class=\"muted\">not run</td></tr>");
            return;
        }

        html.append("<td><span class=\"badge ").append(statusClass(result.status())).append("\">")
                .append(esc(result.status().name())).append("</span></td>");
        html.append("<td>").append(result.testsRun()).append("</td>");
        html.append("<td>").append(result.failures()).append("</td>");
        html.append("<td>").append(result.errors()).append("</td>");
        html.append("<td>").append(result.skipped()).append("</td>");
        html.append("<td>").append(esc(duration(result.durationMillis()))).append("</td>");
        html.append("<td><code>").append(esc(result.adapter())).append("</code>");
        if (!result.command().isBlank()) {
            html.append("<div class=\"details\">").append(esc(result.command())).append("</div>");
        }
        html.append("</td></tr>");
    }

    private void appendBaselineDetails(StringBuilder html, String label, TestSuiteResult result) {
        if (result == null) {
            return;
        }

        if (!result.failedTests().isEmpty()) {
            html.append("<h3>").append(esc(label)).append(" failures</h3>");
            html.append("<ul class=\"risk-factors\">");
            int shown = Math.min(5, result.failedTests().size());
            for (int i = 0; i < shown; i++) {
                TestFailure failure = result.failedTests().get(i);
                html.append("<li><code>")
                        .append(esc(failure.className()))
                        .append("#")
                        .append(esc(failure.testName()))
                        .append("</code> ")
                        .append(esc(failure.message()))
                        .append("</li>");
            }
            if (result.failedTests().size() > shown) {
                html.append("<li class=\"muted\">+")
                        .append(result.failedTests().size() - shown)
                        .append(" more</li>");
            }
            html.append("</ul>");
        }

        if (!result.warnings().isEmpty()) {
            html.append("<details><summary class=\"muted\">")
                    .append(esc(label))
                    .append(" warnings</summary><ul class=\"summary\">");
            for (String warning : result.warnings()) {
                html.append("<li>").append(esc(warning)).append("</li>");
            }
            html.append("</ul></details>");
        }

        if (!result.outputTail().isBlank()) {
            html.append("<details><summary class=\"muted\">")
                    .append(esc(label))
                    .append(" process output</summary><pre class=\"output\">")
                    .append(esc(result.outputTail()))
                    .append("</pre></details>");
        }
    }

    private static String statusClass(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "low";
            case TEST_FAILURES, COMPILE_FAILURE, BUILD_FAILURE, TIMEOUT, ERROR -> "high";
            case UNSUPPORTED -> "neutral";
        };
    }

    private static String verdictClass(DifferentialVerdict verdict) {
        return switch (verdict) {
            case F2P -> "low";
            case P2F, F2F -> "high";
            case FLAKY -> "medium";
            case P2P, INCONCLUSIVE -> "neutral";
        };
    }

    private static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private void appendFiles(StringBuilder html, VerificationReport report) {
        html.append("<section class=\"panel\"><h2>Changed Java files</h2>");
        if (report.changedFiles().isEmpty()) {
            html.append("<p class=\"muted\">No Java files changed.</p>");
        } else {
            html.append("<div class=\"files\">");
            for (String file : report.changedFiles()) {
                html.append("<span class=\"file\">").append(esc(file)).append("</span>");
            }
            html.append("</div>");
        }
        html.append("</section>");
    }

    private void appendImpacts(StringBuilder html, VerificationReport report) {
        html.append("<section class=\"panel\"><h2>Callable changes</h2>");
        if (report.methodImpacts().isEmpty()) {
            html.append("<p class=\"muted\">No callable changes detected.</p></section>");
            return;
        }

        html.append("<div class=\"table-wrap\"><table><thead><tr>");
        html.append("<th>Callable</th><th>Change</th><th>API</th><th>Complexity</th>");
        html.append("<th>Callers</th><th>Tests</th><th>Risk</th>");
        html.append("</tr></thead><tbody>");

        for (MethodImpact impact : report.methodImpacts()) {
            MethodChange change = impact.change();
            html.append("<tr>");
            html.append("<td><code>").append(esc(change.displayName())).append("</code>");
            html.append("<div class=\"details\">").append(esc(change.path()));
            if (change.headLine() > 0) {
                html.append(":").append(change.headLine());
            }
            html.append("</div>");
            if (!change.details().isEmpty()) {
                html.append("<div class=\"details\">")
                        .append(esc(String.join("; ", change.details())))
                        .append("</div>");
            }
            html.append("</td>");
            html.append("<td><span class=\"badge ").append(riskClass(impact.riskBand())).append("\">")
                    .append(esc(change.kind().name())).append("</span></td>");
            html.append("<td>").append(change.publicApi() ? "Yes" : "No").append("</td>");
            html.append("<td>").append(change.baseComplexity()).append(" -> ")
                    .append(change.headComplexity());
            int delta = change.complexityDelta();
            if (delta != 0) {
                html.append("<div class=\"details\">").append(delta > 0 ? "+" : "").append(delta)
                        .append("</div>");
            }
            html.append("</td>");
            html.append("<td>").append(impact.productionCallerCount()).append("</td>");
            html.append("<td>");
            if (impact.affectedTests().isEmpty()) {
                html.append("<span class=\"muted\">none detected</span>");
            } else {
                List<String> tests = impact.affectedTests();
                int shown = Math.min(3, tests.size());
                for (int i = 0; i < shown; i++) {
                    html.append("<span class=\"pill\">").append(esc(tests.get(i))).append("</span>");
                }
                if (tests.size() > shown) {
                    html.append("<div class=\"details\">+").append(tests.size() - shown)
                            .append(" more</div>");
                }
            }
            html.append("</td>");
            html.append("<td><span class=\"badge ").append(riskClass(impact.riskBand())).append("\">")
                    .append(esc(impact.riskBand().name())).append(" ")
                    .append(impact.riskScore()).append("</span>");
            if (!impact.riskFactors().isEmpty()) {
                html.append("<ul class=\"risk-factors\">");
                for (String factor : impact.riskFactors()) {
                    html.append("<li>").append(esc(factor)).append("</li>");
                }
                html.append("</ul>");
            }
            html.append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody></table></div></section>");
    }

    private void appendEvidence(StringBuilder html, VerificationReport report) {
        html.append("<section class=\"panel\"><h2>Evidence ledger</h2>");
        if (report.evidence().isEmpty()) {
            html.append("<p class=\"muted\">No evidence generated.</p></section>");
            return;
        }
        html.append("<div class=\"table-wrap\"><table><thead><tr>");
        html.append("<th>ID</th><th>Type</th><th>Location</th><th>Evidence</th>");
        html.append("</tr></thead><tbody>");
        for (Evidence evidence : report.evidence()) {
            html.append("<tr>");
            html.append("<td><code>").append(esc(evidence.id())).append("</code></td>");
            html.append("<td>").append(esc(evidence.type())).append("</td>");
            html.append("<td><code>");
            if (evidence.path() == null || evidence.path().isBlank()) {
                html.append("execution");
            } else {
                html.append(esc(evidence.path()));
                if (evidence.line() > 0) {
                    html.append(":").append(evidence.line());
                }
            }
            html.append("</code></td>");
            html.append("<td>").append(esc(evidence.description())).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendWarnings(StringBuilder html, VerificationReport report) {
        if (report.warnings().isEmpty()) {
            return;
        }
        html.append("<section class=\"panel warnings\"><h2>Warnings</h2><ul class=\"summary\">");
        for (String warning : report.warnings()) {
            html.append("<li>").append(esc(warning)).append("</li>");
        }
        html.append("</ul></section>");
    }

    private String riskClass(RiskBand band) {
        return band.name().toLowerCase();
    }

    private String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
