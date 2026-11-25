# ChangeImpact AI — Implementation Notes & Manual Verification Guide

## Local Environment Setup

### 1. Prerequisites
- **Java 21 LTS** (the platform was upgraded from 17 to 21 in Phase 3)
- **Maven 3.9+**
- **Node.js 22** (the frontend image builds with `node:22-alpine`; Node 20 also works)
- **PostgreSQL 16**, **Redis 7**, **RabbitMQ 3.13** (run via Docker Compose)

---

### 2. Starting Infrastructure Services
```bash
docker compose up -d
```
Spins up PostgreSQL 16 (pgvector), Redis 7, and RabbitMQ 3.13 Management (`http://localhost:15672`).

---

### 3. Running Backend
```bash
cd backend
mvn spring-boot:run
```
- Flyway automatically runs database migrations `V1`–`V12`.
- All languages are analysed by default (`ANALYSIS_LANGUAGES=all`); restrict with a list or `-name` (`docs/MULTI_LANGUAGE.md`).
- Swagger UI available at: `http://localhost:8080/swagger-ui.html`
- Actuator health check: `http://localhost:8080/actuator/health`

---

### 4. Running Frontend
```bash
cd frontend
npm run dev
```
Open `http://localhost:5173` in your browser.

---

### 5. Verification Checklist

#### Phase 1 Verification
1. Register/Login: `http://localhost:5173/login`
2. Connect GitHub Account: Add a GitHub Personal Access Token at `/github`.
3. Connect a Java Repository: Import a Java repository at `/repositories`.
4. Trigger Ingestion: Click "Index" and verify status transitions to `INDEXED`.

#### Phase 2 Verification (Static Code Intelligence & Dependency Graph)
1. **Interactive Dependency Graph Explorer**:
   - Open repository details at `http://localhost:5173/repositories/{id}`.
   - Click on the **Interactive Dependency Graph** tab.
   - Verify nodes represent extracted Java classes, interfaces, records, and methods.
   - Test zoom, pan, and mini-map controls.
   - Filter by package prefix (e.g., `com.example`) and click **Apply**.
2. **Blast Radius Analysis**:
   - Click any symbol node in the canvas to open the **Symbol Inspector**.
   - Click **Calculate Blast Radius**.
   - Verify that all direct and transitive dependents up to depth 3 glow red and edges animate.
3. **Architecture Boundaries & Hotspots**:
   - Click on the **Architecture & Hotspots** tab.
   - Review detected circular dependencies and layer violations (e.g. Controllers directly calling Repositories).
   - Review Robert C. Martin's instability metrics ($I = Ce / (Ca + Ce)$) and identified coupling hotspots.

---

## Verification Run — 2026-11-17 (real infrastructure, not mocks)

Environment: Docker Compose (PostgreSQL 16 + pgvector, Redis 7, RabbitMQ 3.13), backend jar, Vite dev server,
Playwright (Chromium). Test repository: `spring-projects/spring-petclinic` @ `500158f7` (50 Java files).

| Check | Before fixes | After fixes |
|---|---|---|
| Register → connect public repo → index | Works | Works |
| Progress stages visible while running | No (QUEUED → COMPLETED jump; single DB transaction) | Yes: CLONING → SCANNING → PARSING → ANALYZING → COMPLETED |
| Re-index same commit | **Fails**: duplicate key on `source_files`, `UnexpectedRollbackException`, repo stuck `INDEXING` forever | Idempotent: identical counts before/after |
| Re-index after new commits | Re-analysed stale code (JGit `setStartPoint` ignored without `setCreateBranch`) | Fetch + hard reset to `origin/<branch>` (unit-tested with a local origin) |
| Non-existent branch | n/a | `FAILED` with clear message, message dead-lettered |
| `GET .../symbols`, symbol search, `GET /symbols/{id}`, violations | **HTTP 500** (Hibernate proxy serialization; `lower(bytea)`) | HTTP 200 (DTOs; non-null search parameter) |
| Callers of `Owner#getPet(String)` | **0** (method calls only matched no-arg methods) | **4**, identical to grep ground truth; other overloads correctly excluded |
| Member-level edges | 247 | 641 |
| Layer violations | 10 incl. test classes | 5 (production code only) |

### Known limitations (documented, not hidden)
- Library types not on the analyser's classpath are `UNRESOLVED` (petclinic: 914, all in `src/test` — JUnit/AssertJ).
  Resolving Maven/Gradle dependencies with `JarTypeSolver` is a future enhancement.
- Lombok-generated members, reflection, Spring proxies and dependency injection wiring are not visible to static analysis.
  Calls into generated members resolve at best to the type (`PARTIAL`) or not at all.
- Members of anonymous and local classes are not stored as symbols, so their references are skipped.
- References from static/instance initializer blocks and field initializers are attributed to the enclosing type.
- Hotspot thresholds (`Ca ≥ 5`, `Ce ≥ 10`) are a heuristic, not a calibrated measure.
- The graph cache is in-memory per backend instance (LRU, 16 graphs); a multi-instance deployment needs Redis or rebuild-on-demand.

---

## Phase 3 Verification Guide

1. Index a Java repository (history is collected during indexing: up to `app.history.max-commits`, default 300).
2. Repository page → **Change Impact** tab → choose base and head commits (or type a SHA/branch/tag) → **Analyse change impact**.
3. Watch the stages: FETCHING → RESOLVING → DIFF → PARSING_BASE → PARSING_HEAD → SEMANTIC_DIFF → HISTORY → IMPACT → PERSISTING → COMPLETED.
4. On the analysis page check: risk badge and distribution, changes by kind, recommended verification, the
   per-symbol risk factor breakdown, the impact graph (direct = orange, indirect = grey) and the dependency path
   of a finding (click a row).
5. **History** tab: weekly activity and most frequently changed files.
6. API: `GET /api/v1/analyses/{id}/report.md` returns the Markdown report.

Configuration (`application.yml`, prefix `app`): `impact.default-max-depth`, `impact.max-depth-limit`,
`impact.depth-decay`, `impact.relationship-weights.*`, `risk.weights.*`, `risk.*-threshold`,
`risk.critical-modules[]`, `history.max-commits`.

Integration test (needs Docker): `mvn test -Dtest=ChangeAnalysisIntegrationTest`.


## Phase 5 Guide — Test Intelligence, Security Impact, GitHub App

### Configuration
| Variable / property | Purpose |
|---|---|
| `app.security.semgrep.command` (default `semgrep`) | Semgrep CLI used for security scans. Install with `pipx install semgrep` (verified with 1.182.1). Without it the security summary says `UNAVAILABLE`. |
| `GITHUB_APP_CLIENT_ID` or `GITHUB_APP_ID` | GitHub App identity (client ID is GitHub's recommended JWT issuer) |
| `GITHUB_APP_PRIVATE_KEY_PATH` or `GITHUB_APP_PRIVATE_KEY` | App private key (PEM as downloaded from GitHub; PKCS#1 and PKCS#8 accepted, `\n` escapes accepted) |
| `GITHUB_WEBHOOK_SECRET` | Webhook secret; without it the webhook endpoint answers 503 |
| `GITHUB_APP_SLUG` | Shows the "install" link in the UI |
| `CHANGEIMPACT_PUBLIC_URL` | Frontend URL; Check Runs link to `<url>/analyses/<id>` |
| `GITHUB_CHECKS_FAIL_ON` (default `CRITICAL`) | Overall risk level that fails the Check Run (`LOW`…`CRITICAL`, `NEVER`) |
| `GITHUB_CHECKS_FAIL_ON_SECURITY` (default `HIGH`) | Severity of an introduced vulnerability that fails the Check Run |
| `GITHUB_API_BASE_URL` | GitHub API base (GitHub Enterprise Server: `https://<host>/api/v3`) |

### GitHub App registration (owner action)
1. GitHub → Settings → Developer settings → GitHub Apps → New. Webhook URL `https://<backend>/api/v1/webhooks/github`,
   content type `application/json`, a random secret.
2. Repository permissions: **Checks: read & write**, **Contents: read**, **Pull requests: read**, Metadata: read.
   Subscribe to events **Pull request** and **Push** (installation events are always sent).
3. Generate a private key; set the variables above; install the App on the repositories that are connected in ChangeImpact AI.
4. The GitHub page of the UI shows a checklist, the repositories the App covers and the last webhook deliveries.

### CI uploads (per commit)
```bash
API=https://<backend>; ID=<repositoryId>; SHA=$GITHUB_SHA
curl -H "Authorization: Bearer $TOKEN" -F type=JACOCO -F commitSha=$SHA -F file=@target/site/jacoco/jacoco.xml $API/api/v1/repositories/$ID/reports
curl -H "Authorization: Bearer $TOKEN" -F type=JUNIT  -F commitSha=$SHA $(for f in target/surefire-reports/TEST-*.xml; do printf -- '-F file=@%s ' "$f"; done) $API/api/v1/repositories/$ID/reports
curl -H "Authorization: Bearer $TOKEN" -F type=PIT    -F commitSha=$SHA -F file=@target/pit-reports/mutations.xml $API/api/v1/repositories/$ID/reports
curl -H "Authorization: Bearer $TOKEN" -F type=DEPENDENCY_CHECK -F commitSha=$SHA -F file=@target/dependency-check-report.json $API/api/v1/repositories/$ID/reports
```
PIT needs `<timestampedReports>false</timestampedReports>` (or the timestamped directory) and `outputFormats` including XML.

### Verification run — 2026-11-18 (real tools and data; GitHub replaced by a local fake API)
**Test intelligence on spring-petclinic `9a5d50c → 500158f`** with real reports produced at `500158f`
(petclinic's 77 tests run with JaCoCo 0.8.15; PIT 1.30.2 on the owner/vet packages: 160 mutants):
- JaCoCo 94.0 % lines / 82 % branches, 25 files; JUnit 77 tests uploaded as 16 Surefire files in one request; PIT 160 mutants
  (120 killed, 32 survived, 8 no coverage), commit given as short SHA `500158f`.
- 45 tests selected (43 direct, 2 indirect, 10 classes), all `PASSED` according to the uploaded run; changed-line
  coverage 37/41 (90.2 %); mutation score of the 10 changed methods 80.3 % with 5 below 70 % —
  `PetController#updatePetDetails` 14.3 % (5 of 7 mutants survived), a real test-quality gap.
- 8 MISSING_TEST gaps are controller methods tested over MockMvc (not visible to the static graph); JaCoCo shows
  them executed, which lowers their priority and is stated in the reason.

**Security + GitHub App on a demo repository** (`demo-org/demo-service`, local bare repo; the PR head exists only as
`refs/pull/7/head`, like a fork PR): the PR switches a JDBC query to string concatenation, SHA-256 to MD5 and
downgrades commons-text 1.10.0 → 1.9. A Dependency-Check JSON *fixture* (CVE-2022-42889 for commons-text 1.9)
was uploaded for the head commit because NVD is not reachable here.
- Findings: Semgrep `cia.java.sql-injection.string-concat-query` (UserRepository.java:19, CWE-89) and
  `cia.java.crypto.weak-hash` (UserService.java:26, CWE-328) in changed lines; build diff DOWNGRADED; CVE-2022-42889
  joined to the downgrade (pom.xml:17); sensitive-code hints for cryptography/SQL/sensitive data.
- Webhooks (signed with the real HMAC scheme): bad/missing signature → 401; ping → pong; installation tracked;
  unknown repository and draft PR → IGNORED; PR opened/synchronize → analysis queued; redelivery → 200 DUPLICATE;
  push to `main` → re-index to the new head; push to another branch → IGNORED; PR closed → state `merged`.
- Fake GitHub API verified the App JWT (RS256 signature with the public key, `iat` back-dated, `exp − iat ≤ 600`,
  `iss` = client ID) and recorded: token request scoped to `demo-service` with `checks:write, contents:read`;
  check run `queued` (head SHA, `external_id`, details URL) → `in_progress` → `completed`, conclusion `failure`,
  9 annotations (CVE and SQL injection at `failure` level), summary 3.3 k chars; one installation token for all calls.

**Automated tests**: 100/100 (`mvn test`), incl. GitHub's documented HMAC test vector, JWT/PKCS#1 with a generated key,
`MockRestServiceServer` for the token exchange, a real Semgrep scan of the vulnerable fixture (9 rules hit, 3 safe
variants not), test selection on a real graph with the real propagation service, and the Testcontainers pipeline test.

### Problems found and fixed during verification
- `webhook_deliveries` already existed (V2 scaffold) → V7 extends it instead of creating it (would have broken existing databases).
- Spring's JDK request factory streamed request bodies chunked → bodies are now buffered and sent with `Content-Length`.
- Check Runs concluded `success` for a PR introducing a SQL injection and a CRITICAL CVE (risk was MEDIUM) → separate
  `fail-on-security` threshold for introduced vulnerabilities.
- Measured coverage contradicted the static "no test references" factor (MockMvc) → the proxy has weight 0 when coverage is measured.
- The axios client's JSON default would have serialised `FormData` uploads to JSON → multipart header on uploads.
- Dashboard counted re-analyses of the same PR/range several times → latest analysis per PR or range.
- UI: verification evidence mangled by `shortName`, clipped test table, light React Flow controls on the dark canvas,
  sidebar consuming phone screens (now a top navigation below 768 px) — all fixed and re-checked in Chromium (0 console errors).

### Deep re-verification — 2026-11-18 (failure paths, concurrency, browser paths)
| Scenario | Result |
|---|---|
| PR whose head commit cannot be fetched (`refs/pull/99/head` missing) | analysis FAILED with a readable reason; Check Run completed `neutral` with the reason (after fix 1) |
| GitHub API unreachable when the PR event arrives | webhook 202; analysis completes; PR shows risk + `FAILED_TO_PUBLISH` with the error (after fix 2) |
| GitHub App not configured (secret only) | PR analysed, risk recorded, check `NOT_CONFIGURED`; status page shows the missing credentials |
| No webhook secret | 503 over HTTP |
| Delivery whose processing failed, then redelivered by GitHub (same id) | 500 first (visible in GitHub's delivery log), redelivery processed again (fix 3) |
| Form-encoded webhook (signed) | 415 with the configuration hint |
| `installation_repositories` added/removed, `installation` deleted/re-created | coverage of the repository follows each event |
| Re-index and change analysis started at the same moment on one repository | both complete; work on the workspace is serialised (fix 4) |
| Re-index of an already analysed commit | symbols reused; existing reports keep their symbol links (fix 5) |
| JUnit upload of 16 files through the browser | multipart with boundary; "Ingested: 77 tests · 77 passed" |
| Phase 3 analysis without Phase 5 data, failed PR analysis, brand-new user | correct "not computed" panels, failure banner with PR badge, empty dashboard; no other user's deliveries visible; 0 console errors |

Fixes from this pass (each with a regression test):
1. `resolveCommit`: a full SHA missing from the object database raised JGit's "Missing unknown …"; now `COMMIT_NOT_FOUND`,
   and PR analyses say which `refs/pull/N/head` could not be fetched.
2. The analysis-completed listener cleared the webhook-time publishing error when no check run existed; errors now
   also name their root cause (`ConnectException`) instead of ": null".
3. Failed or stuck (>10 min PROCESSING) deliveries are processed again on redelivery and answer 500 instead of 202.
4. Indexing and change analysis on the same repository are serialised with a per-repository lock (concurrent fetches
   failed on Git ref locks, and a re-index could rewrite data an analysis was reading). In-process lock: run one
   backend instance per workspace volume until Phase 6 scaling.
5. Re-indexing a commit that is already analysed reused nothing: it deleted and recreated its symbols, which set the
   symbol links of existing reports to NULL (`ON DELETE SET NULL`; 66 links in this database had already been lost
   during Phase 3 testing). Indexing now reuses an analysed commit (immutable) and only re-evaluates architecture rules.

Noted for Phase 6 (production hardening): the default profile is `dev`, which logs SQL bind values at TRACE.

### Known limitations (Phase 5)
- MockMvc/RestAssured tests are not linked to controller methods statically; JaCoCo coverage is the evidence for them.
- Coverage, test results and mutation data are used only for the exact head commit they were produced for.
- Semgrep scans changed and directly impacted files only, with 12 bundled rules; it is an analysis aid, not a security guarantee.
- Gradle dependency diff understands string notation (`group:artifact:version`), not version catalogs or `platform()` BOMs.
- The live GitHub round trip (real App, public webhook URL) needs the owner's App registration; it was verified against
  a local fake GitHub API that checks signatures, scopes and payload limits.
- Phase 4 (AI review) was deferred at the time: every Phase 5 result is deterministic. Phase 4 was implemented on
  2026-11-22 as an on-request addition (`aiReview: ON_REQUEST`); see the Phase 4 notes at the end.

---

## Phase 6 Guide — Production Hardening

Security hardening (P6-1) is documented with its evidence in `SECURITY.md`.

### P6-2 Performance, robustness and analysis correctness

**Operational limits** (`app.ingestion.*`, see `application.yml`):
- `JobWatchdog` (every 5 min, first run 1 min after start): jobs RUNNING longer than `job-timeout-minutes` (30) or
  QUEUED longer than `queued-timeout-minutes` (720) are marked FAILED, their change analyses follow, FAILED events are
  published, and repositories left in INDEXING without an active job are set to FAILED. A completion that arrives after
  the watchdog gave up is ignored.
- RabbitMQ consumers: 1 to `max-concurrent-jobs` (3) per queue — every job holds a parse in memory.
- One lock per repository (`WorkspaceManager.withRepositoryLock`) serialises fetch, checkout and parse of a workspace.
- Stage timings: every indexing job and change analysis logs `stage timings (ms)` (FETCH, SCANNING, HISTORY, PARSING,
  GRAPH_BUILDING, …) and hands them to the `stageTimings` hook (wired to metrics in P6-3).
- Default branch of public repositories is read from the remote (`ls-remote` HEAD symref) instead of assuming `main`;
  an unreachable repository is refused at connect time (`REPOSITORY_NOT_FOUND`).
- V8 drops five indexes no query used (`DATABASE_SCHEMA.md`).

**Incremental analysis** (`IncrementalAnalysisPlanner`, `CodeAnalysisService.analyze(.., plan)`): a commit is analysed
from the closest already analysed commit (fewest changed Java files among the last 8). Re-parsed: added/modified/
renamed files, unchanged files with a dependency on a type declared in a changed or removed file (incl. members and
nested types), and every file in a package that gains, loses or changes a file. All other files' source-file, symbol and
dependency rows are copied in SQL, and their dependency targets are re-linked against the new commit's symbols. The type
solver always sees the whole checkout. If more than `incremental-max-fraction` (0.5) of the files would be re-parsed, a
full parse is cheaper and is used. Switch: `INCREMENTAL_ANALYSIS_ENABLED`.
Only commits analysed by the current analyser version (`source_files.analyzer_version` = `ANALYZER_VERSION`) can be a
base — found on real data: a base analysed before the JavaParser fixes carried 1,765 old UNRESOLVED rows into the new
commit. Increase `ANALYZER_VERSION` with every change that alters the rows produced for the same source.
Test: `IncrementalAnalysisIntegrationTest` (four commits incl. a changed return type that changes resolution in unchanged
callers, and a copied enum with same-named declarations) compares every file, symbol and dependency row — including
which declaration a call is linked to — with a fresh full analysis of each commit.

**JavaParser fixes.** Patched copies of JavaParser 3.28.2 classes live in `backend/src/main/java/com/github/javaparser/`
(they shadow the jar's classes; each file starts with a header and marks its changes `PATCH (ChangeImpact AI)`):

| File | Problem found | Effect |
|---|---|---|
| `BlockStmtContext`, `StatementContext` (+ `IdentityIndex`) | statement position looked up with `NodeList.indexOf`, i.e. structural `equals` over whole subtrees, for every name lookup | commons-lang parse 255–281 s → 79–83 s (idle machine), identical rows |
| `MethodResolutionLogic` (1) | array arguments were checked with primitive widening/boxing of their components: `byte[]` was "applicable" to `m(int[])`, `m(long[])`, `m(float[])`, `m(double[])` | overloads such as `ArrayUtils.clone(byte[])`/`clone(long[])` were reported as ambiguous (unresolved) or linked to the wrong overload, depending on order |
| `MethodResolutionLogic` (2) | the most-specific-method selection is order-sensitive and the candidate order came from hash sets | overloads of one type are now compared in signature order |
| `JavaParserTypeParameter`, `ReflectionTypeParameter` | `hashCode()` mixed in identity hashes (type solver / `Class`), so maps keyed by type parameters iterated differently in every analysis; substitution with two type variables of the same name (`equals` is structural) then varied, e.g. `new FailableStream<>(stream.map(Functions.asFunction(mapper)))` in commons-lang | the same code resolves the same way in every analysis and JVM |

Effect on apache/commons-lang (commit `1af74dd`, 128,054 dependency rows; upstream JavaParser vs patched, otherwise
identical code): RESOLVED 60,419 → 62,163, EXTERNAL 35,288 → 35,319, UNRESOLVED 31,924 → 30,150. Per call site:
1,315 became resolved, none became unresolved, and 53 that pointed at a wrong overload now point at the right one
(`removeAll(double[],int[])` → `removeAll(float[],int[])` ×13, `removeElements(double[],double[])` →
`removeElements(float[],float[])` ×13, `clone(double[])` → `clone(float[])` ×6, `join(Object[],…)` → `join(double[],…)` …).
Regression tests: `OverloadResolutionTest` (javac's choice for 22 calls, each also resolved with the candidates in 40
shuffled orders; fails on upstream), `TypeParameterDeterminismTest` (fails on upstream: 6 of 12 fresh analyses
resolved the call, 6 did not).

**Determinism of our own code.** Java files and source roots are processed in sorted order (the file-system order
differs between clones and decided which of two same-named types the type solver found first, and which files were
kept beyond `max-files-per-repository`). Several declarations can share a qualified name (an enum whose constants
override the same method, local classes): the first declaration in (path, line) order is the dependency source and link
target, in the full and the incremental path alike. `SemanticChangeDetector` pairs same-named declarations of two
commits by identical content first and then in source order; before, the first database row won, and the same
comparison reported different changes (8 different outcomes for the same edit in `SemanticChangeDetectorTest`).

Tried and rejected: strong (non-soft) caches for the type solver's parsed files. They did not change any result (the
nondeterminism came from the hash codes above) and indexing guava ran out of memory at `-Xmx3g`; JavaParser's
default caches are kept.

**Measurements** (this environment: 4 vCPU container, PostgreSQL/RabbitMQ/Redis in Docker on the same host,
`-Xmx3g`; each run: fresh JVM with `--app.ingestion.incremental-enabled=false`, the commit's rows deleted,
`POST /api/v1/repositories/{id}/index`, numbers from `repository_indexes`, the `stage timings` log line and `ps` RSS):

| Repository | Java files | Symbols | Dependencies | Indexing time | Peak RSS |
|---|---|---|---|---|---|
| spring-petclinic-rest | 109 | 805 | 6,909 | 7.1 s ¹ | — |
| apache/commons-lang (`1af74dd`) | 646 | 15,429 | 128,054 | 110 s (parse 105 s) | 2.2 GB |
| google/guava (`773f631`) | 3,267 | 42,047 | 478,328 | 203 s (parse 189 s) | 3.8 GB |

Repository fetch is not included (workspaces were cloned before; a first guava clone took 131 s). Parse times vary
with machine load: commons-lang parsed in 79–83 s on an idle machine and in 105–125 s during these runs (load average
2–4). ¹ petclinic was measured before the P6-2 fixes.

**Determinism check**: commons-lang `1af74dd` fully parsed in two separate JVMs gives identical rows (0 of 128,054
differ, every column incl. linked target). Before the fixes, upstream JavaParser differed by 113 rows between two runs
(overload choice), and with only the overload fixes 2 rows still differed (the `FailableStream` constructor above).

**Incremental vs full on real data** (commons-lang, change analysis `2626360..90c16f1`, the same analysis in two
repositories): full parses of both commits 224.5 s; incremental 42.8 s (base planned from an analysed neighbour
commit, head from the incrementally analysed base; ~22 s / ~18 s per commit instead of ~117 s / ~103 s). Every
dependency row (127,948 and 127,993, including the linked declaration), every symbol (15,458 / 15,461) and the
analysis report (ids and timestamps excluded) are identical. The first attempt differed in 5,080 rows of the base
commit, which led to the analyser-version rule above.

### P6-3 Observability

**What is exposed**
- `GET /actuator/prometheus`: Micrometer's Prometheus registry. Readable with `Authorization: Bearer <METRICS_SCRAPE_TOKEN>`
  or as an administrator; without a token only admins can read it. All other actuator endpoints except health/info
  are admin-only. (Found while testing: the JWT filter skipped `/actuator/**`, so `/actuator/metrics` could not be read
  by anyone; fixed.)
- Platform metrics (`common/metrics/PlatformMetrics`, list in `API_DOCUMENTATION.md` §5d): job outcomes, stage
  durations as histograms (from the existing `stage timings`), active jobs and queue depths (refreshed in the
  background every 15 s, so a scrape never waits on RabbitMQ), analyses by risk level, full/incremental/reused parses
  and files, webhooks by status, Check Run publications, Semgrep runs, watchdog timeouts. All known series are created
  at 0 on startup — otherwise Prometheus' `increase()` cannot see the first event of a series.
- Standard metrics: JVM, GC, CPU, HTTP server requests (with a latency histogram), HikariCP, RabbitMQ, Tomcat; every
  series carries `application="changeimpact-ai"`.
- Tracing: every log line carries `[traceId,spanId]`. Trace export over OTLP/HTTP is off by default; enable it with
  `OTEL_TRACING_ENABLED=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (e.g. an OpenTelemetry Collector, Jaeger or Tempo
  at `http://collector:4318/v1/traces`). Sampling: `TRACING_SAMPLING_PROBABILITY` (dev 1.0, prod 0.1). Metrics can
  additionally be pushed with `OTEL_METRICS_EXPORT_ENABLED=true`.

**Running Prometheus and Grafana** (backend started on the host):
```bash
export METRICS_SCRAPE_TOKEN=$(openssl rand -hex 24)    # same value for the backend and for compose
docker compose --profile monitoring up -d prometheus grafana
```
Prometheus: http://localhost:9090 (targets, alerts) — Grafana: http://localhost:3001 (admin / `GRAFANA_ADMIN_PASSWORD`),
dashboard *ChangeImpact AI → ChangeImpact AI — Overview*. Files: `monitoring/prometheus/prometheus.yml` (full stack,
target `backend:8080`), `prometheus-host.yml` (backend on the host), `alerts.yml` (backend down, job failures,
queue backlog, dead letters, watchdog timeouts, heap pressure, 5xx rate), `monitoring/grafana/**` (provisioned
datasource and dashboard, read-only in the UI).

**Verification (2026-11-18, real traffic)**: `promtool check config/rules` passes (7 rules). With the stack above,
Prometheus scraped the backend with the token (target `up`; an unauthenticated scrape gets 401). Traffic: petclinic
indexed (full parse, then reused), a commons-lang change analysis twice (incremental: 24 files parsed + 622 reused), a
failing analysis (unknown commit), signed, duplicate and forged webhooks, API calls. Prometheus then reported exactly
that: jobs completed 4 / failed 2, parse runs full 1 / incremental 2 / reused 1, files parsed 157 = 109 + 24 + 24 and
reused 1,353 = 622 + 622 + 109, webhooks ping ACCEPTED / DUPLICATE and push REJECTED, 2 MEDIUM analyses, HTTP p95
≈ 40 ms; the dead-letter gauge showed 5 + 5 messages left from earlier failure tests (the corresponding alert rule
would fire). Grafana rendered all 26 panels without errors (`docs/images/grafana-overview.png`). Trace export was
checked against a local OTLP/HTTP receiver: the backend posted protobuf batches to `/v1/traces`, including HTTP server
spans. Tests: `ObservabilityIntegrationTest` (access rules, platform and standard metrics, bounded tags).

### P6-5 Architecture digital twin and visualizations

**Model** (`architecture/twin`): for every analysed commit, a snapshot of the production code (test files excluded)
built only from parsed symbols and resolved dependencies, stored in `architecture_snapshots` (first build: 4.7 s on
commons-lang, then 48 ms) and rebuilt when the commit is re-analysed or the algorithm version changes.
- Packages: files, types, LOC, methods, cyclomatic complexity; Ca/Ce (distinct packages), I = Ce/(Ca+Ce),
  A = abstract types/types, D = |A + I − 1|; package cycles = strongly connected components (JGraphT); a
  dependencies-first order (Kahn on the cycle-condensed graph) used to lay out the matrix.
- Files ("hotspots"): churn = commits/lines/authors from the scanned history **up to the commit's date**, fan-in
  (other files depending on it), coverage from the commit's latest JaCoCo report, hotspot score = churn percentile ×
  complexity percentile; a hotspot is in the top quarter of both with ≥ 2 changes (a documented heuristic).
- Compare: added/removed packages and dependencies, new/resolved cycles, per-package Ca/Ce/I/D/LOC deltas, rule
  violations, plain-language findings.
- History backfill (`POST .../architecture/backfill`): analyses up to 20 commits spread evenly over the default
  branch's first-parent history, oldest first (each planned incrementally from its predecessor), under the repository
  lock, as an `ARCHITECTURE_HISTORY` job; it records each commit's metadata even beyond the scanned history window.

**UI** (Repository → Architecture & Hotspots): Code city (treemap: package blocks, file tiles sized by LOC, coloured by
hotspot score / change frequency / complexity / coverage, hotspots outlined), Hotspots (churn × complexity bubble
chart on square-root axes + ranked refactoring candidates), Dependency matrix (DSM in dependency order, cycle cells
outlined, cycle list), Main sequence (A × I with zones), Trends (four small multiples, one scale each, plus the
backfill control with live job progress), Compare (findings, before → after tiles, dependency lists, diverging Ce-delta
bars, rule violations); the earlier rules/coupling view is kept as the last tab. Colours: validated palette —
sequential blue for magnitudes, status red only for hotspots/cycles (always with a text label), blue↔red diverging.
Screenshots: `docs/images/twin-*.png`.

**Verification (2026-11-19, real repositories)**
- commons-lang `33a58e5`: 18 production packages, 264 files, 51 package dependencies, one 13-package cycle around
  `lang3` (the root package and its sub-packages reference each other). `StringUtils.java` ranks first: 16 changes by 6
  authors — `git log` on the same window gives exactly 16 commits and 6 authors (lines 433 vs 443: the platform's diff
  ignores whitespace-only changes).
- petclinic-rest: backfill of 6 and 8 commits (2.5–9.5 s per commit, incremental) → 12 points 2016–2026. The twin shows
  a 4-package cycle appearing at `9545f8c` (2017) and disappearing at `de7dbb3` (2021); compare names the new
  `model → rest` dependency. Checked in the sources: `Owner`, `Pet`, `Visit` import the Jackson serializers from `rest`
  in exactly those commits.
- Browser (Chromium via Playwright, both repositories): all seven views render without console errors. Found and
  fixed while testing: Recharts repeated ticks on square-root axes (explicit ticks), hotspot guide lines drawn at the
  75th percentile instead of where hotspots start, smoothed trend lines inventing values between commits, overflowing
  dependency lists, size growth coloured as "good", sampled commits without dates.
- Tests: `ArchitectureTwinIntegrationTest` (a commit adding domain → service: cycle detected, Ca/Ce = edges,
  dependencies-first order, stored snapshot reads back identically, comparison findings, trend, hotspots),
  `ArchitectureTwinAlgorithmsTest` (percentile ranks, sampling, layer order).

### Developer workflow: reviewers, pre-merge checklist, architecture drift, blast radius, timeline

Every change analysis now also produces (new stage `REVIEW`, non-fatal like tests and security):
- **Suggested reviewers** (`analysis/review/ReviewerSuggestionService`): for each changed file (weight 1) and each
  production file impacted within 2 hops (weight ½), a person's share of that file's history *before the base commit*,
  each commit weighted by recency (half-life 365 days); top 5 with evidence (files, commits, share, last change). The
  change's own authors are excluded by e-mail **and name** (found on real data: Gary Gregory and Antoine Rey committed
  under several addresses and were suggested to review their own change), bots are excluded. Checked against `git log`:
  commons-lang `MethodUtilsTest.java` — 2 commits by the change author, 1 by "alhuda" → alhuda suggested with 33%.
- **Architecture drift**: the twin comparison of base and head (new cycles, new/removed package dependencies, new rule
  violations, before → after numbers). petclinic `72fa510..9545f8c` reports the new model ↔ repository ↔ rest ↔
  service cycle and the `model → rest` dependency.
- **Pre-merge checklist** (`ImpactReportService.checklist`): rule-based items with evidence — contract-breaking changes
  to review, failing tests that reach the change, tests to run, untested changed symbols, high/critical security
  findings, new cycles / dependencies / violations to confirm, reviewers to ask. It is the first section of the
  Markdown report and therefore of the pull request's Check Run (GitHub renders it as a task list); reviewer
  e-mails are not written into it (names only).
- UI: checklist (ticks remembered per analysis in the browser), reviewers with ownership bars, drift card, and the
  **blast-radius sunburst** — centre = change, ring 1 = changed symbols, each further ring one dependency hop, arc
  width = impacted symbols reached through it, shade = propagation strength; hover shows the path, click zooms
  (up to 2,000 strongest findings). The repository's Change Impact tab starts with the **analysis timeline** (risk
  score over time, colour = risk level with labels, size = changed symbols, triangle = pull request; click opens it).
- Found and fixed while testing: wrong column name (`is_test`) silently disabled the suggestions (non-fatal stage,
  visible as `status: FAILED`), epoch-seconds dates shown as 1970, size changes coloured as good/bad, checklist
  evidence lists of 64 entries (now 8 + "…and N more"), sunburst limited to 500 findings.
- Tests: `ReviewerSuggestionServiceTest` (ranking arithmetic, recency, exclusion by e-mail and name, bots, checklist
  rules). Screenshots: `docs/images/review-checklist-reviewers-drift.png`, `blast-radius*.png`, `analysis-timeline.png`.

### Known limitations (P6-2)
- Some generic calls that combine type variables of the same name from different declarations stay unresolvable for
  JavaParser (e.g. the minimal reproduction in `TypeParameterDeterminismTest`); they are recorded as PARTIAL (constructor
  calls, linked to the type) or UNRESOLVED — now the same way in every analysis.
- Same-named declarations (constant-specific enum bodies) share one link target, the first declaration.
- Memory grows with repository size (guava: 3.8 GB RSS at `-Xmx3g`); `max-concurrent-jobs` bounds how many parses
  run at once.
- The repository lock, rate limits and caches are per process (see `SECURITY.md`).


## Deployment (P6-4) — 2026-11-19

Full guide: `docs/DEPLOYMENT.md`; every variable: `docs/CONFIGURATION.md`.

* **Images.** `backend/Dockerfile` (context = repository root): Maven build stage → runtime on `python:3.13-alpine`
  (since P6-7: `python:3.13-slim` + `eclipse-temurin:21-jre`, glibc for tree-sitter, Debian security updates via
  HTTPS mirrors at build time, Python health check)
  with the Temurin 21 JRE copied from `eclipse-temurin:21-jre-alpine` (no OS package manager needed at build time),
  Semgrep in `/opt/semgrep` (`INSTALL_SEMGREP=false` skips it), pip removed afterwards, user `app` (uid 10001),
  health check with busybox `wget`. `frontend/Dockerfile`: Vite build → `nginx:1.30-alpine` with envsubst templates
  (`BACKEND_UPSTREAM`, `CLIENT_MAX_BODY_SIZE`), `/api/` proxy, immutable `/assets/`, SPA fallback with `no-cache`,
  CSP and other security headers (`frontend/nginx/security-headers.conf`).
* **Builds behind a TLS-inspecting proxy.** `docker/proxy-env.sh` reads `HTTPS_PROXY` (Maven proxy properties) and an
  optional BuildKit secret `ca_cert` (all certificates of the bundle imported into the JDK truststore, `NODE_EXTRA_CA_CERTS`,
  `PIP_CERT`); the runtime image removes the imported certificates. `dependency:go-offline` does not fetch every
  transitive artifact, so the package step runs online with the shared `~/.m2` cache mount.
* **AI is optional.** Compose passes `GEMINI_API_KEY=""` when unset; Spring AI's Gemini client throws on a blank key
  and the embedding connection details on a missing project id — the backend did not start. `AiAvailability`
  (listener registered in `ChangeImpactApplication`) now excludes the Gemini and pgvector-store auto-configurations
  and sets `app.ai.enabled=false` when the key is blank (`AiAvailabilityTest`).
* **Image scan** (Trivy 0.76.2): fixed Guava 33.3.1 → 33.8.0, RabbitMQ amqp-client 5.30.0 → 5.38.0
  (`rabbit-amqp-client.version`), nginx 1.27 → 1.30 (70 Alpine findings), urllib3/msgpack/setuptools upgraded in the
  Semgrep venv (`pip check` clean), vendored copies removed with pip. Result: 0 fixable HIGH/CRITICAL in both images.
  Full backend suite after the upgrades: 145 tests passing.
* **Tests need the dev RabbitMQ and Redis** on localhost (PostgreSQL comes from Testcontainers):
  `docker compose up -d rabbitmq redis` before `mvn test`, otherwise the health assertion in
  `ObservabilityIntegrationTest` sees Redis DOWN (503).
* **Backups.** Only PostgreSQL and `.env` hold state (clones are re-created, Redis/RabbitMQ are transient):
  `scripts/backup.sh` (pg_dump custom format + sha256, optional `KEEP_BACKUPS`), `scripts/restore.sh` (checksum,
  confirmation, drop/recreate, restart; Flyway migrates forward).

## Multi-language analysis (P6-7) — 2026-11-19

Design, Python model, verification and limitations: `docs/MULTI_LANGUAGE.md`.

* `parser/language/*`: `LanguageParser` (+ `Session`), `JavaLanguageParser` (wraps the existing scanner/JavaParser),
  `PythonLanguageParser` (tree-sitter, two-pass name resolution, conservative receiver inference), `LanguageRegistry`
  (`app.analysis.languages`, Java always on, unavailable parsers skipped with a warning).
* `CodeAnalysisService` iterates the enabled parsers; the incremental plan applies only to `incremental()` parsers and
  the planner only copies Java rows (`language = 'java'`). Each analysed commit records `language@version`
  (`analysed_commit_languages`); `CommitAnalysisService.ensureAnalyzed` and indexing re-analyse commits missing an
  enabled language/version (found when the Python parameter format changed: old rows would have shown every
  function as changed).
* `SymbolType.MODULE/FUNCTION` with `isTypeLike()` / `isCallable()` used by `GraphNode`, `DependencyGraphBuilder`,
  `SymbolSnapshot`, `CodeAnalysisService`, the twin's SQL and test selection.
* Semantic diff: same-name callables without a signature in the name compare parameter lists →
  `PARAMETERS_CHANGED` (breaking) / `PARAMETERS_EXTENDED` / `PARAMETER_ANNOTATIONS_CHANGED`. Found on `psf/requests`:
  the first version flagged hint-only and optional-parameter changes as breaking.
* `FileCategory.PYTHON_SOURCE/PYTHON_TEST`, `isSource()`; `GitDiffService.exportSources(…, extensions)`; indexing
  counts source files of all enabled languages (a Python-only repository is indexed).
* Docker runtime moved to Debian slim because the tree-sitter natives need glibc (`UnsatisfiedLinkError` on musl);
  verified in the container: `Experimental language analysis enabled: python`, health `healthy` (on an isolated port —
  a first check had wrongly passed because the health probe hit a dev backend on the host's port 8080; the probe now
  follows `SERVER_PORT`).
* Image security: Debian security updates are applied at build (`APPLY_OS_UPDATES`, strict: the build fails if the
  mirrors are unreachable — before, `apt-get update` failures were only warnings and the step silently did nothing;
  apt fetches as `_apt`, which cannot read the root-only BuildKit CA secret, so a temporary readable copy is used).
  Trivy 0.76.2: zlib1g fixed; the only remaining finding, CVE-2026-115563 in `mcp` 1.29.0 (pinned exactly by every
  Semgrep release up to 1.186.0), affects MCP OAuth *clients* only — never executed (only `semgrep scan` runs) — and
  is recorded with justification and an expiry date in `docker/trivyignore.yaml`. Frontend: 0 findings.

## Phase 4 notes (2026-11-22): AI review, code search, assistant

Full description, measurements and limits: `docs/AI_REVIEW.md`. Implementation decisions:

- **Own Gemini REST client** instead of Spring AI's ChatClient. It gives `responseSchema` JSON output, per-model
  retries with a fallback model, daily-quota detection from the 429 body (`...PerDay...`), and a sliding-window
  embedding throttle. The throttle waits with `wait()`, so it releases its lock; index builds keep 10 % of the budget
  for searches.
- **One model call per review or question, inside a deterministic pipeline**, instead of the planned six agents.
  Evidence building, retrieval and validation are Java code.
- **Measured, then changed:**
  - V14 weights name words above body words in full-text search;
  - `auto` retrieval uses vector search for questions and hybrid search for code names;
  - the validator also checks plain-text code names;
  - Git rename facts go into the review evidence, because the semantic diff reports a class renamed together with its
    file as REMOVED + ADDED.
- **Found during the evaluation runs:**
  - The free tier allows only 1,000 embedding requests per day. Building the requests index plus the evaluation
    queries used it up, and it is the reason the held-out vector evaluation is still open.
  - `gemini-3.5-flash` was overloaded (503) for most of the day, so the fallback model wrote most reviews.
