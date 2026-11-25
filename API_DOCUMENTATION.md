# ChangeImpact AI — REST API Documentation

Base URL: `http://localhost:8080/api/v1`  
Swagger UI: `http://localhost:8080/swagger-ui.html`  
OpenAPI JSON: `http://localhost:8080/api-docs` (Swagger UI and API docs are off in the `prod` profile)

**Response envelope.** Success: `{"success": true, "message"?, "data", "timestamp"}`. Failure:
`{"success": false, "code", "message", "errors"?, "timestamp"}`. `code` is machine-readable: the business codes
named in this document (e.g. `SAME_COMMIT`, `JOB_ALREADY_RUNNING`, `INVALID_REPORT`, `INVALID_COMMIT`), or
`VALIDATION_FAILED` (with `errors[]` per field), `NOT_FOUND`, `ACCESS_DENIED`, `INVALID_CREDENTIALS`,
`MALFORMED_REQUEST`, `MISSING_PARAMETER`, `INVALID_PARAMETER`, `INTERNAL_ERROR`. Rate-limited requests answer
429 with `Retry-After`.

---

## 1. Authentication Endpoints

### Register User
- **POST** `/auth/register`
- **Request Body**:
```json
{
  "email": "developer@example.com",
  "username": "developer",
  "password": "Password123",
  "displayName": "Alex Dev"
}
```
- **Response**: `201 Created` with `AuthResponse`.

### Login
- **POST** `/auth/login`
- **Request Body**:
```json
{
  "email": "developer@example.com",
  "password": "Password123"
}
```
- **Response**: `200 OK` with `accessToken`, `refreshToken`.

### Refresh Access Token
- **POST** `/auth/refresh`
- **Request Body**:
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

---

## 2. GitHub Account Endpoints
*Requires `Authorization: Bearer <token>`*

### Connect GitHub Account
- **POST** `/github/connect`
- **Request Body**:
```json
{
  "accessToken": "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

### Connect with GitHub (OAuth web flow)
- **GET** `/github/oauth/config` → `{configured, scopes, callbackUrl}`
- **GET** `/github/oauth/authorize` → `{url}`: GitHub's consent page; `state` is HMAC-signed, bound to the user, valid 10 minutes, single-use
- **POST** `/github/oauth/callback` `{code, state}` (posted by the frontend page GitHub redirects to) → **201** connected account.
  Errors: `OAUTH_NOT_CONFIGURED`, `OAUTH_INVALID_STATE` (forged, other user's or reused), `OAUTH_STATE_EXPIRED`, `OAUTH_EXCHANGE_FAILED` (GitHub's reason included).

### List Connected Accounts
- **GET** `/github/accounts`

### List Remote GitHub Repositories
- **GET** `/github/accounts/{accountId}/repos?page=1`

---

## 3. Repository Management Endpoints
*Requires `Authorization: Bearer <token>`*

### Connect Repository
- **POST** `/repositories/connect`
- **Body**: `{"fullName": "owner/repo" | "https://github.com/owner/repo(.git)", "branch": "main", "connectedAccountId": "<optional>"}`
- Public repositories can be connected without a GitHub account.

### List Repositories
- **GET** `/repositories?page=0&size=20`

### Get Repository Details
- **GET** `/repositories/{id}`

### Trigger Indexing Job
- **POST** `/repositories/{id}/index?branch=main` (branch optional, defaults to the repository's default branch)
- **202** `{jobId}`. **400** `JOB_ALREADY_RUNNING` if a QUEUED/RUNNING job younger than `app.ingestion.job-timeout-minutes` exists; **400** `INVALID_BRANCH` for unsafe branch names.
- Re-indexing an already-indexed commit replaces that commit's previous analysis (idempotent).
- Failed jobs are marked `FAILED` with an error message and the message is dead-lettered to `changeimpact.repository.index.dlq`.

### Get Indexing Status
- **GET** `/repositories/{id}/index-status`
- Stages are committed as they happen and are visible while the job runs: `QUEUED → CLONING → SCANNING → PARSING → GRAPH_BUILDING → ANALYZING → COMPLETED | FAILED`.

---

## 4. Code Intelligence & Dependency Graph Endpoints (Phase 2)
*Requires `Authorization: Bearer <token>`*

### Search AST Symbols
- **GET** `/code/repositories/{repoId}/symbols?symbolType=CLASS&query=Order&page=0&size=30`
- **Description**: Searches extracted AST classes, interfaces, records, methods, and constructors with 1-based source line ranges.

### Get Symbol Details
- **GET** `/code/symbols/{symbolId}`
- **Description**: Detailed metadata for an individual symbol including return types, parameters, visibility, and annotations.

### Get Forward Dependencies
- **GET** `/code/symbols/{symbolId}/dependencies?depth=2`
- **Description**: Returns all symbols that the specified symbol depends upon (direct or transitive up to `depth`).

### Get Reverse Dependents (Blast Radius)
- **GET** `/code/symbols/{symbolId}/dependents?depth=3` (depth clamped to 1..10)
- **Description**: Returns all symbols that directly or transitively depend on the specified symbol. For a class, the traversal starts from the class **and all of its members** (callers of `Owner#getPet(String)` are affected when `Owner` changes). Each node carries `ownerTypeId`, `packageName` and `testCode`.

### Get Interactive Dependency Graph Snapshot
- **GET** `/code/repositories/{repoId}/graph?view=CLASS&packageFilter=com.example`
- **view**: `PACKAGE` (packages, edges aggregated), `CLASS` (default: every member folded into its enclosing type), `MEMBER` (every method/constructor/field).
- **Node**: `id, label, type, qualifiedName, packageName, sourceFile, startLine, endLine, ownerId, memberCount, inDegree, outDegree, testCode`
- **Edge**: `id, source, target, type` (first relationship), `types` (all merged relationship kinds), `count` (number of code references), `line`
- One edge per (source, target) pair; parallel references are merged into `types`/`count`.

### Calculate Shortest Dependency Path
- **GET** `/code/repositories/{repoId}/paths?sourceSymbolId={s}&targetSymbolId={t}`
- **Description**: Calculates the shortest directed dependency path between two symbols using Dijkstra's algorithm.

### List Architecture Boundary Violations
- **GET** `/code/repositories/{repoId}/architecture/violations`
- **Description**: Evaluated on the class-level graph. Circular dependencies are reported once per strongly connected component (with a shortest example cycle). Layer rules (regex on qualified names) report one violation per class pair, with reference count and relationship kinds; test code is excluded from layer rules.

### Get Coupling & Instability Metrics
- **GET** `/code/repositories/{repoId}/metrics`
- **Description**: Per type, on the class-level graph: afferent coupling `Ca` (types depending on it), efferent coupling `Ce`, instability `I = Ce/(Ca+Ce)` and `hotspot` (heuristic: `Ca ≥ 5` or `Ce ≥ 10`).

### Get Graph Statistics
- **GET** `/code/repositories/{repoId}/graph/stats`
- **Description**: Symbol and edge counts (member and class level), symbols by type, member edges by relationship kind, circular-dependency groups, isolated types, and `referencesByResolution` (`RESOLVED` / `PARTIAL` / `EXTERNAL` / `UNRESOLVED`).

### List Unresolved References
- **GET** `/code/repositories/{repoId}/dependencies/unresolved?page=0&size=50`
- **Description**: References the symbol solver could not resolve (`sourceSymbol, sourceFile, line, dependencyType, unresolvedReference`). These are never turned into graph edges.

---

## 5. Change Impact Analysis Endpoints (Phase 3)
*Requires `Authorization: Bearer <token>`. Analyses are only visible to their owner.*

### Start an analysis
- **POST** `/analyses` → **202** `{analysisId, jobId}`
- **Body**: `{"repositoryId": "...", "baseRef": "<sha|branch|tag>", "headRef": "<sha|branch|tag>", "maxDepth": 3, "relationshipTypes": ["METHOD_CALL", ...], "useMergeBase": false}`
- **400**: `SAME_COMMIT`, `ANALYSIS_ALREADY_RUNNING`, `INVALID_DEPTH` (1–6), `INVALID_RELATIONSHIP`, invalid ref format.
- Runs asynchronously (RabbitMQ `changeimpact.analysis.job`); failures are stored on the analysis and dead-lettered.
  Unknown refs fail the analysis with `Commit or ref not found in repository`.

### Status / summary
- **GET** `/analyses/{id}` — status, `stage`, `progressPercent`, `progressMessage`, `riskScore`, `riskLevel`, `summary`
  (files by category, +/− lines, symbols changed, breaking changes, changes by kind, directly/total impacted,
  impacted tests, risk distribution, affected packages/modules, configuration key changes, cosmetic-only files).
- **GET** `/analyses?repositoryId=...&page=0&size=20` — newest first.

### Changes
- **GET** `/analyses/{id}/changes` → `{files[], symbols[]}`; each symbol has `changeKinds[]`, `breaking`,
  `previousQualifiedName`, `details` (before/after evidence), dependents counts and risk.

### Impacts
- **GET** `/analyses/{id}/impacts?changedSymbolId=&impactType=DIRECT|INDIRECT&includeTests=true&page=&size=`
- Each finding: affected symbol, location, `depth`, `strength` (0–1), `impactType`, `evidence`
  (`VERIFIED_REFERENCE` | `TRANSITIVE_PROPAGATION`), `alsoChanged`, `test`, `graphCommit`, and `dependencyPath`
  (`[{qualifiedName, simpleName, symbolType, relationship[], line}]`, from the changed symbol to the affected one).

### Risk
- **GET** `/analyses/{id}/risk` — overall score/level and, per changed symbol, every factor with
  `raw`, `normalized`, `weight`, `contribution` (points) and `explanation`. Heuristic, not a probability.

### Report
- **GET** `/analyses/{id}/report` — full JSON report incl. verification steps, architecture concerns and tests referencing the change.
- **GET** `/analyses/{id}/report.md` — the same report as Markdown (`text/markdown`).

### Commits and history
- **GET** `/repositories/{id}/commits?page=0&size=50` — commits collected during indexing (newest first) with +/− lines and heuristic `bugfix`/`revert`/`merge` flags.
- **GET** `/repositories/{id}/history?weeks=52` — weekly activity, most frequently changed files, revert/bug-fix counts.

---

## 5b. Testing, Security, GitHub App and Dashboard Endpoints (Phase 5)

### Upload a CI report
`POST /api/v1/repositories/{repositoryId}/reports` — `multipart/form-data`
- `type`: `JACOCO` | `JUNIT` | `PIT` | `DEPENDENCY_CHECK`
- `commitSha`: full SHA, or a unique abbreviation (7+) of an indexed commit
- `file`: the report; **JUNIT accepts several `file` parts** (one per Surefire `TEST-*.xml`, max 500) merged into one report
- 201 with the stored report and its summary; 400 `INVALID_REPORT` (wrong format, or XML with a DOCTYPE/entities),
  `TOO_MANY_FILES`, `INVALID_COMMIT` (malformed, unknown or ambiguous short SHA)
```bash
curl -H "Authorization: Bearer $TOKEN" -F type=JACOCO -F commitSha=$GITHUB_SHA \
     -F file=@target/site/jacoco/jacoco.xml $API/api/v1/repositories/$ID/reports
```
`GET /api/v1/repositories/{repositoryId}/reports` — uploaded reports, newest first.

### Test intelligence of an analysis
`GET /api/v1/analyses/{id}/tests[?category=DIRECT|INDIRECT|MISSING_TEST|SCENARIO]`
- `summary`: `byCategory`, `testClasses`, `executionResults` (from the JUnit report of the head commit, or
  `NO_RESULTS_UPLOADED`), `changedLineCoverage` (`changedLinesCovered`/`changedLinesExecutable`/`ratio`/`bySymbol`,
  or `NO_REPORT_FOR_COMMIT`), `mutation` (score of changed methods, weak methods, survivors, killing tests)
- `recommendations[]`: `category`, `priority`, `testClass`, `testMethod`, `targetSymbol`, `reason`,
  `executionStatus` (PASSED, FAILED, SKIPPED, NOT_EXECUTED, NO_RESULTS, NOT_APPLICABLE), `depth`, `evidence.path`

### Security impact of an analysis
`GET /api/v1/analyses/{id}/security`
- `summary`: `semgrep` (`status` COMPLETED/UNAVAILABLE/SKIPPED/TIMEOUT/FAILED, `filesScanned`, `findings`),
  `dependencyChanges[]`, `dependencyCheck` (report for the head commit or `NO_REPORT_FOR_COMMIT`), `bySeverity`, `byCategory`, `bySource`
- `findings[]`: `source`, `category`, `ruleId`, `severity`, `title`, `message`, `filePath`, `startLine`, `endLine`,
  `symbol`, `relation` (CHANGED = in modified lines, IMPACTED = in affected code, DEPENDENCY), `cwe`

The JSON report (`/report`) gains `tests`, `security` and `aiReview`; the Markdown report (`/report.md`, also the
Check Run summary) gains Tests and Security sections.

### GitHub App webhook (public, signature-verified)
`POST /api/v1/webhooks/github` — headers `X-GitHub-Event`, `X-GitHub-Delivery`, `X-Hub-Signature-256`; body `application/json`
| Status | Meaning |
|---|---|
| 202 | `{"status":"ACCEPTED"|"IGNORED"|"FAILED","detail":…,"analysisIds":[…]}` |
| 200 | `DUPLICATE` — this delivery id was already received |
| 401 | missing/invalid HMAC-SHA256 signature (nothing is stored) |
| 413 / 415 / 400 | body too large / not JSON / missing headers or invalid JSON |
| 503 | webhook secret not configured |

Handled events: `pull_request` (opened, synchronize, reopened, ready_for_review → analysis + Check Run; closed/edited → state),
`push` to the default branch (re-index), `installation`, `installation_repositories`, `ping`.

### GitHub App status and pull requests
- `GET /api/v1/github/app` — configuration checklist (App credentials, webhook secret, Check Runs, details links),
  install URL, required permissions/events, which of your repositories the App covers
- `GET /api/v1/github/app/deliveries` — last 50 deliveries for your repositories
- `GET /api/v1/repositories/{repositoryId}/pull-requests` — PRs with latest analysis, risk and Check Run state

### Team dashboard
`GET /api/v1/dashboard?days=30` (1–90) — `totals`, `openPullRequests`, `highRiskChanges`, `security` (by severity,
category, source, top findings), `riskTrend` (one point per day), `repositories` (index state, architecture violations,
latest coverage / test results / mutation score / known vulnerabilities, last analysis), `executionHistory`.
A pull request or commit range analysed several times counts once (its latest completed analysis).

---

## 5b2. Analysis report additions (Phase 6)

`GET /api/v1/analyses/{id}/report` (and `report.md`) now also contain `checklist` (items with `area`
REVIEW/TESTS/SECURITY/ARCHITECTURE, `action`, `evidence`, `moreEvidence`), `reviewers` (`suggestions[]` with `name`,
`email`, `score`, `changedFilesOwned`, `impactedFilesOwned`, `lastChange`, `evidence[]`; `changeAuthors`,
`historyCommits`, `filesWithoutHistory`) and `architecture` (drift: `findings`, `newCycles`, `addedDependencies`,
counts, `before`/`after` summaries). The same `reviewers` and `architecture` objects are in the analysis `summary`.

## 5c. Architecture Digital Twin (Phase 6)

All under `/api/v1/repositories/{repoId}/architecture`, owner-scoped (404 for other users' repositories). `commit` accepts a
full SHA or an unambiguous prefix (≥ 7) of an **analysed** commit; default: last indexed commit. Unknown commit → 404.

| Endpoint | Returns |
|---|---|
| `GET /commits` | analysed commits (sha, date, message, snapshot stored) |
| `GET /snapshot?commit=` | packages (files, types, LOC, complexity, Ca, Ce, I, A, D, cycle group, dependency order), package edges (references, file pairs, in cycle), package cycles, summary |
| `GET /hotspots?commit=` | production files: LOC, methods, complexity, churn (commits, lines, authors up to the commit), fan-in, coverage (latest JaCoCo report), hotspot score/flag |
| `GET /trend` | summaries of up to 40 analysed commits, oldest first |
| `GET /compare?base=&head=` | added/removed packages and dependencies, new/resolved cycles, per-package metric deltas, new/resolved rule violations, findings |
| `POST /backfill` `{"commits": 2–20}` | 202 + `jobId`: analyses commits spread evenly over the default branch (job type `ARCHITECTURE_HISTORY`, poll `GET /api/v1/jobs/{id}`) |

## 5c1. Languages (Phase 6)
- Symbols (`/code/...`, graphs, analyses) can have `symbolType` `MODULE` / `FUNCTION` and the names of every analysed
  language (`docs/MULTI_LANGUAGE.md`): Python `pkg.mod.Cls#method`, JavaScript/TypeScript `src.components.Button#Button`,
  PHP/C# `App.Models.User#save`, Go `internal.store.Server#Start`, Ruby `Billing.Invoice#charge!`, pages and stylesheets
  `templates.owners.ownersList.html`, `static.css.site.css#.card`, database objects `db:owners`, `db:owners#first_name`,
  `db:touch()`. External targets keep a prefix: `npm:react#useState`, `go:fmt#Println`, `gem:json`.
- `source_files.language` / file `language`: `java`, `python`, `javascript` (also TypeScript), `php`, `csharp`, `go`,
  `ruby`, `html`, `css`, `sql`. `summary.filesByCategory` has `JAVA_*`, `PYTHON_*` and `SOURCE` / `TEST` for the other
  languages.
- New change kinds (languages without overloading): `PARAMETERS_EXTENDED` (optional parameters added, not breaking),
  `PARAMETER_ANNOTATIONS_CHANGED` (type hints only, not breaking); `PARAMETERS_CHANGED` stays breaking.

## 5c2. Defect Model (Phase 6, experimental)
*Requires `Authorization: Bearer <token>`; repository owner only. Method and evaluation: `docs/DEFECT_MODEL.md`.*

- **POST** `/repositories/{id}/defect-model/train` → **202** `{jobId}` — `DEFECT_MODEL` job: dataset from up to 5,000
  commits, SZZ labels, time-ordered evaluation, gate; progress stages `FETCHING → DATASET → LABELING → TRAINING → COMPLETED`.
- **GET** `/repositories/{id}/defect-model` — `status` (`ACCEPTED` | `INSUFFICIENT_LABELS` | `NOT_BETTER_THAN_BASELINE`),
  `reason`, `current` (trained by the current pipeline version), `dataset` (commits, fixes, traced fixes,
  bug-introducing commits, timings), `evaluation` (split, positives, `model` {auc, brier, ece, threshold, atThreshold,
  atHalf, calibration[], coefficients}, `churnBaseline`, `priorBaseline`), `predictions` (accepted only: newest 300
  commits with `probability`, `flagged`, `drivers[]` = per-feature log-odds contributions). `{"status": "NOT_TRAINED"}`
  before the first training (404 only for repositories of other users).
- Analyses add `summary.defectModel` / `report.defectModel`: `status` (`NOT_TRAINED`, `OUTDATED`, or the model status)
  and, for an accepted model, the range's `commits` (as above, highest first), `notScored` (newer than the model),
  `withoutCodeChanges`, `threshold`, `testAuc`. The Markdown report adds a "History-based defect estimate
  (experimental)" table. Estimates never enter the deterministic risk score.

## 5d. Operational Endpoints (Phase 6)

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /actuator/health`, `GET /actuator/info` | public (prod: status only) | liveness/readiness for load balancers and compose health checks |
| `GET /actuator/prometheus` | `Authorization: Bearer <METRICS_SCRAPE_TOKEN>` or an admin JWT | Prometheus exposition: `changeimpact_*` platform metrics, JVM, HTTP (with latency histogram), HikariCP, RabbitMQ, Tomcat |
| `GET /actuator/metrics[/{name}]` | admin JWT | the same metrics as JSON |
| `POST /api/v1/admin/retention/run` | admin JWT | run the retention job now (see SECURITY.md) |

Platform metrics (Prometheus names; tag values are bounded):

| Metric | Tags | Meaning |
|---|---|---|
| `changeimpact_jobs_total` | `type` (indexing, analysis), `outcome` (completed, failed, discarded) | finished background jobs |
| `changeimpact_pipeline_stage_seconds` (histogram) | `pipeline`, `stage` (e.g. PARSING, PARSING_HEAD, IMPACT, TOTAL) | stage durations of every job |
| `changeimpact_jobs_active` (gauge) | `type`, `status` (QUEUED, RUNNING) | jobs waiting or running |
| `changeimpact_queue_messages`, `changeimpact_queue_consumers` (gauges) | `queue` | RabbitMQ job queues and dead-letter queues (refreshed every 15 s; -1 = unavailable) |
| `changeimpact_analyses_risk_total` | `level` | completed analyses by risk level |
| `changeimpact_parse_runs_total`, `changeimpact_parse_files_total` | `mode` (full, incremental, reused) / `action` (parsed, reused) | commit analyses and files parsed vs reused |
| `changeimpact_webhooks_total` | `event` (known events or `other`), `status` (ACCEPTED, IGNORED, FAILED, DUPLICATE, REJECTED) | GitHub webhook deliveries |
| `changeimpact_checkruns_total` | `outcome` (published, failed, skipped) | Check Run publications |
| `changeimpact_semgrep_seconds` | `outcome` (completed, failed, timeout, unavailable) | Semgrep runs |
| `changeimpact_watchdog_timeouts_total` | `kind` (running, queued, analyses, indexes, repositories) | records failed by the job watchdog |

## 5d1. Centrality, architecture rules, code browser, libraries (2026-11-23)
*Requires `Authorization: Bearer <token>`; repository endpoints are owner-scoped (other users get 404).*

| Endpoint | Purpose |
|---|---|
| `GET /code/repositories/{id}/centrality?rankBy=&limit=&includeTests=` | Types of the last indexed commit ranked by `betweenness` (default), `pagerank`, `indegree` or `outdegree`; each with in/out-degree, normalised betweenness (0–1) and PageRank (sums to 1). Test code excluded unless `includeTests=true`. `limit` ≤ 200. Errors: `NOT_INDEXED`, `INVALID_RANK_BY` |
| `GET /repositories/{id}/architecture/rules` | Global default rules and the repository's own: `scope` (GLOBAL/REPOSITORY), `ruleType`, `severity`, `configuration`, `enabled` (definition), `active` (in effect for this repository), `violationsAtLastCommit` |
| `POST /repositories/{id}/architecture/rules` | Create a rule `{ruleName, ruleType, severity, description, configuration}`. Types and configuration: `CIRCULAR_DEPENDENCY` `{}`; `FORBIDDEN_DEPENDENCY`/`LAYER_VIOLATION` `{sourcePattern, forbiddenTargetPattern}`; `LAYERED_ARCHITECTURE` `{layers: [{name, pattern}] (2–20, top to bottom), allowLayerSkipping}`; `ONLY_ACCESSED_BY` `{targetPattern, allowedSourcePattern}`. Patterns: case-insensitive regular expressions on qualified names (≤ 300 characters; patterns with catastrophic backtracking are refused). At most 50 rules per repository. Errors: `INVALID_RULE`, `TOO_MANY_RULES` |
| `PUT /repositories/{id}/architecture/rules/{ruleId}` · `DELETE …` | Update (omitted fields keep their value) or delete a repository rule; global rules → `GLOBAL_RULE_READ_ONLY` |
| `PUT /repositories/{id}/architecture/rules/{ruleId}/active` `{active}` | Switch a rule on/off for this repository (global rules: a per-repository override) |
| `POST /repositories/{id}/architecture/rules/preview` | Violations a draft rule would report at the last indexed commit (first 200, `total`); nothing stored |
| `POST /repositories/{id}/architecture/rules/evaluate` | Recompute the stored violations of the last indexed commit with the rules now in effect. Error: `REPOSITORY_BUSY` |
| `GET/POST/PUT/DELETE /admin/architecture/rules[/{ruleId}]` | Global default rules (ADMIN) |
| `GET /repositories/{id}/source/files?commit=` | Analysed source files of a commit (default: last indexed) |
| `GET /repositories/{id}/source/file?path=&commit=` | Text of one analysed file read from the Git object database at that commit (≤ 1 MB, text only), its `symbols` (with `dependents` counts) and `references` (line, target, resolution status, target file and line for in-repository targets). Only files the analysis knows can be read. Errors: `INVALID_COMMIT`, `COMMIT_NOT_ANALYSED`, `FILE_TOO_LARGE`, `FILE_NOT_TEXT`, `SOURCE_UNAVAILABLE`, 404 |
| `GET /code/repositories/{id}/graph/stats` | (existing) now also returns `libraries`: status (`RESOLVED`, `PARTIAL`, `FAILED`, `NO_BUILD_FILE`, `DISABLED`), build system, jar count, size, message ([docs/LIBRARY_RESOLUTION.md](docs/LIBRARY_RESOLUTION.md)) |

## 5e. AI review, code search and assistant (Phase 4, optional)
*Requires `Authorization: Bearer <token>`; the repository or analysis must belong to the caller (otherwise 404).
Without `GEMINI_API_KEY` the AI endpoints answer `AI_NOT_CONFIGURED` / report `aiEnabled: false`; every deterministic
endpoint is unaffected. Method and measurements: [docs/AI_REVIEW.md](docs/AI_REVIEW.md).*

| Endpoint | Purpose |
|---|---|
| `GET /repositories/{id}/rag/status` | `{aiEnabled, commit, chunks, candidates, embeddingModel}` — chunks searchable at the last indexed commit, code units that would be chunked |
| `POST /repositories/{id}/rag/index` | Build/update the search index of the last indexed commit (job `RAG_INDEX`; embeddings reused by content hash). → `{jobId}`; poll `/jobs/{jobId}` |
| `POST /repositories/{id}/rag/search` `{query, k ≤ 50, mode}` | `mode` = `auto` (default) \| `hybrid` \| `fts` \| `vector`. → `{mode, note, hits[]}`: `mode` actually used, `note` explains a fallback (no key, quota used up → full-text), each hit with `qualifiedName`, `filePath`, lines, `content`, `ftsRank`, `vectorRank`, `similarity`, fused `score`. Errors: `RAG_NOT_INDEXED`, `INVALID_QUERY`, `INVALID_MODE` |
| `POST /analyses/{id}/ai-review` | Start an AI review of a completed analysis (job `AI_REVIEW`). → `{reviewId, jobId}` |
| `GET /analyses/{id}/ai-review` | Latest review: `status` (`NOT_REQUESTED` with `aiEnabled`, `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`), `model`, `promptVersion`, tokens, `latencyMs`, `evidence` (id → reference), `result` (`summary`, `findings[]` with `status` VERIFIED/UNVERIFIED, `reason`, `unknownMentions`, `invalidCitations`; `testSuggestions[]`; `questionsForAuthor[]`), `validation` counts incl. `relatedCodeRetrieval` |
| `GET /analyses/{id}/report.md` | (existing) now ends with the latest completed AI review, labelled as language-model output with each finding's validation status |
| `POST /repositories/{id}/assistant/ask` `{question ≤ 1000 chars}` | Grounded answer: `answer`, `status` (VERIFIED, UNVERIFIED, INSUFFICIENT_EVIDENCE), `citations`, `unknownMentions`, `evidence[]` (K# code, G# dependency facts), `retrieval`, model, tokens, latency. Errors: `AI_NOT_CONFIGURED`, `RAG_NOT_INDEXED`, `INVALID_QUESTION` |
| `GET /repositories/{id}/assistant/history` | The caller's last 20 questions and answers for the repository |

## 6. Background Job Monitoring Endpoints
*Requires `Authorization: Bearer <token>`*

### Get Job Details & Logs
- **GET** `/jobs/{id}`

### List Jobs for Repository
- **GET** `/jobs/repository/{repositoryId}?page=0&size=20`
