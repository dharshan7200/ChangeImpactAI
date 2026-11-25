# Phase 5 Plan — Intelligent Testing, Security Analysis and GitHub Automation

Status: in implementation. **Phase 4 (AI agents) is deferred** by the project owner (no Gemini key yet); the one
Phase 5 step that depends on it ("run the AI analysis workflow") is a visible hook reporting `AI review not available`.

## Objective
Turn the analysis platform into a developer workflow tool: recommend the right tests, show test quality, flag
security-relevant changes, and analyse pull requests automatically with results published as GitHub Check Runs.

## Research and feasibility (2026-11-17)
| Item | Finding | Decision |
|---|---|---|
| JaCoCo | 0.8.16 current; XML report has DOCTYPE | StAX parser with DTD/external entities disabled (XXE-safe) |
| PIT | pitest-maven 1.30.2, pitest-junit5-plugin 1.2.3 | ingest `mutations.xml`; PIT added to our own build for real data |
| Semgrep | 1.182.1 pinned (≥ 2 weeks old); registry (semgrep.dev) unreachable here | run offline with **bundled Java rules**; reported as "unavailable" if not installed |
| OWASP Dependency-Check | 13.0.0; NVD unreachable here and needs an NVD API key | **ingest** its JSON report from CI; build-file dependency diff computed here |
| GitHub App | RS256 JWT (iat −60 s, exp ≤ 10 min, iss = client ID recommended or app ID) → installation token | implemented; tested against a local fake GitHub server (live App needs your registration + a public webhook URL) |
| Checks API | summary ≤ 65,535 chars; ≤ 50 annotations per request (further annotations appended by updates) | batching + truncation |

## Features
1. **Report ingestion** (`POST /repositories/{id}/reports`): JaCoCo XML, JUnit/Surefire XML, PIT `mutations.xml`, Dependency-Check JSON, per commit.
2. **Test selection** from the dependency graph: test methods reached from each changed symbol, including through `@BeforeEach`-style setup and test helpers;
   categories DIRECT (only test code between) / INDIRECT (through production code) / MISSING_TEST / SCENARIO (rule-based scenarios per change kind).
3. **Coverage**: changed executable lines covered (exact commit match only); used as a risk factor when available.
4. **Recommended vs executed**: JUnit results mark each recommended test PASSED / FAILED / SKIPPED / NOT_EXECUTED; without results: NO_RESULTS.
5. **Test quality** (PIT): killed / survived / no-coverage mutants on changed and directly impacted methods.
6. **Security impact**: deterministic sensitivity classifier (authentication, authorization, sensitive data, cryptography, SQL, input handling) using
   names, annotations and resolved external call targets; Semgrep findings mapped to changed vs impacted code; dependency changes from build files joined with Dependency-Check vulnerabilities.
   Tools are analysis sources, not proof of security.
7. **GitHub App**: webhook endpoint with HMAC-SHA256 verification (constant-time), delivery de-duplication, installation tracking,
   pull_request (opened/synchronize/reopened/ready_for_review) → merge-base analysis of the PR head, push to default branch → re-index,
   Check Run created when queued and completed with conclusion, summary, details and annotations.
8. **Team dashboard**: open PR risk, high-risk changes, repository health, coverage, security findings, risk trend, architecture violations, execution history.

## Database (V7)
`quality_reports`, `coverage_files`, `coverage_methods`, `test_executions`, `mutation_results`, `dependency_vulnerabilities`,
`test_recommendations`, `security_findings`, `github_app_installations`, `webhook_deliveries`, `pull_requests`;
`change_analyses` gains `trigger` and `pull_request_id`.

## Minimum GitHub App permissions
Checks: read & write · Pull requests: read · Contents: read · Metadata: read. Events: Pull request, Push, Installation (automatic).

## Acceptance criteria
A supported pull request opened or updated produces an analysis and a completed Check Run with risk, summary, affected modules,
annotations, suggested tests and a link to the full report — verified here with signed webhook deliveries and a fake GitHub API;
live GitHub verification requires the owner to register the App.

## Status (2026-11-18)
Implemented and verified as planned; see `PROJECT_PROGRESS.md` §3a and `IMPLEMENTATION_NOTES.md` → "Phase 5 Guide".
Additions found necessary during verification: `fail-on-security` threshold, coverage superseding the static
test-reference proxy, dashboard de-duplication of re-analysed changes, multi-file JUnit uploads.
