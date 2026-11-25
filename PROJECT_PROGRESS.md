# ChangeImpact AI — Project Progress & Implementation Status

## 1. Project Identity
- **Project Name**: ChangeImpact AI
- **Domain**: Developer Productivity, Static Analysis, Change Impact Analysis, Hybrid RAG, Multi-Agent Engineering
- **Tech Stack**: Java 21 LTS, Spring Boot 4.1.2, Spring AI 2.0.2 (Google Gemini), PostgreSQL 16 + pgvector, Redis 7, RabbitMQ 3.13, React 18, Vite 6, Tailwind CSS, @xyflow/react, JavaParser 3.28.2, JGraphT 1.5.3, ArchUnit 1.5.1

---

## 2. Phase Status Summary

| Phase | Description | Status |
|---|---|---|
| **Phase 1** | **Foundation, Backend Architecture and GitHub Ingestion** | **Done; re-verified on the production stack 2026-11-23** (`docs/VERIFICATION.md`) |
| **Phase 2** | **Static Code Intelligence and Dependency Graph Engine** | **Done, including the optional spec items (2026-11-23): library resolution from dependency jars, centrality metrics, architecture rule management + ArchUnit, Monaco code browser** |
| **Phase 3** | **Change Detection, Impact Analysis and Risk Engine** | **Done (2026-11-17); re-verified 2026-11-23** |
| **Phase 4** | **AI review, code search (RAG) and repository assistant** | **Done and evaluated (2026-11-22, `docs/AI_REVIEW.md`); re-verified 2026-11-23; optional (Gemini key); held-out retrieval evaluation done** |
| **Phase 5** | **Intelligent Testing, Security Analysis and GitHub Automation** | **Done; re-verified 2026-11-23. The live GitHub App round trip needs your App registration (verified against a fake GitHub API)** |
| **Phase 6** | **Production Hardening, Advanced Intelligence and Deployment** | **Completed (2026-11-20, `PHASE6_PLAN.md`): security, performance, observability, deployment, architecture twin, defect model, multi-language, final documentation; CI pipeline dropped by the owner**; re-verified 2026-11-23** |

---

## 3b. Phase 6 Deliverables (completed)

| Feature | Status | Where |
|---|---|---|
| Production profile: no secret defaults, fail-fast safety check before startup, no Swagger/SQL binds/error details | Completed and verified (live prod starts) | `application-prod.yml`, `ProductionSafetyCheck` |
| Secret redaction in log messages and stack traces | Completed and tested | `common/logging/SecretRedactor`, `logback-spring.xml` |
| SSRF guard for Git URLs, rate limiting, admin role, 403 for denials, tenant isolation tests, retention job | Completed and tested | `GitUrlGuard`, `RateLimitFilter`, `admin/*`, `AccessIsolationIntegrationTest` |
| Job watchdog, consumer concurrency, per-repository lock, stage timings, remote default branch | Completed and tested | `admin/JobWatchdog`, `InfrastructureConfig`, `StageClock`, `WorkspaceManager` |
| Incremental analysis (unaffected files copied, links re-resolved; equal to a full parse) | Completed and tested (fixtures + commons-lang) | `IncrementalAnalysisPlanner`, `CodeAnalysisService` |
| JavaParser fixes: statement lookup hot spot, primitive-array overloads, deterministic overload order and type-parameter hashing | Completed and tested (regression tests fail on upstream) | `backend/src/main/java/com/github/javaparser/**` |
| Deterministic analysis order and same-name pairing (files, roots, duplicate declarations, semantic diff) | Completed and tested | `JavaFileScanner`, `JavaAstParser`, `CodeAnalysisService`, `SemanticChangeDetector` |
| Index review (V8), benchmarks on three repositories | Completed | `V8__drop_redundant_indexes.sql`, `IMPLEMENTATION_NOTES.md` |
| Observability: Prometheus endpoint (scrape token/admin), platform metrics, OTLP traces (opt-in), Prometheus + Grafana provisioning with dashboard and alert rules | Completed and verified with real traffic | `common/metrics/*`, `monitoring/`, `docker-compose.yml` (profile `monitoring`) |
| Architecture digital twin: per-commit snapshots (package coupling, cycles, dependency order), file hotspots (churn × complexity), trends, commit comparison, history backfill job | Completed and verified (commons-lang, petclinic) | `architecture/twin/*`, V10 |
| Visualizations: code city (treemap), hotspot matrix, dependency structure matrix, main sequence, trend small multiples, comparison with coupling-delta chart | Completed and verified in Chromium | `frontend/src/components/architecture/*` |
| Developer workflow: suggested reviewers (ownership), pre-merge checklist (report + Check Run), architecture drift per analysis, blast-radius sunburst, analysis timeline | Completed and verified (petclinic, commons-lang) | `analysis/review/*`, `ImpactReportService`, `components/analysis/*` |
| Deployment: production images (backend non-root, Semgrep optional; nginx frontend with security headers), full-stack compose, `.env` generator, backup/restore scripts, deployment guide + configuration reference; AI optional (blank key no longer blocks start-up); image scan 0 fixable HIGH/CRITICAL | Completed — acceptance run from the guide passed (2026-11-19) | `backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.full.yml`, `scripts/*`, `docs/DEPLOYMENT.md`, `docs/CONFIGURATION.md` |
| CI pipeline (GitHub Actions) | Dropped by the owner (not required) | — |
| Historical defect model: JIT features from Git history, SZZ labels, time-ordered evaluation with baselines/calibration/cross-repository runs, gate; served only when accepted (commons-lang: AUC 0.773 vs churn 0.729 → accepted; guava: not better than churn; petclinic ×2: too few labels) | Completed and verified on 4 real repositories (permutation check ≈ 0.5) | `defect/*`, V11, `docs/DEFECT_MODEL.md`, `DefectModelPanel.tsx` |
| PostgreSQL `shm_size: 256mb` (64 MB default broke parallel queries on large repositories) | Fixed | both compose files |
| **All languages on by default (2026-11-23):** Python (no longer experimental), JavaScript/TypeScript incl. React (JSX/TSX), PHP, C#, Go, Ruby (tree-sitter, two-pass declared-type resolution), HTML/templates (Thymeleaf, Jinja/Django, Twig), CSS, SQL (own statement reader); cross-language links (pages → scripts/styles/fragments, `class`/CSS Modules → selectors, JPA/EF/Rails/SQL strings → tables defined by the repository's SQL); `ANALYSIS_LANGUAGES=all` with aliases and `-name` exclusions; Monaco syntax for every language; graph node clicks tolerant of hand movement | Completed — per-language fixture tests, 6 real repositories parsed and indexed through the platform (bulletproof-react, eShopOnWeb, go-chi/chi, sinatra, laravel/pulse, spring-petclinic), production-stack verification | `parser/language/ts/*`, `parser/java/JpaTables.java`, `docs/MULTI_LANGUAGE.md` |
| Multi-language: `LanguageParser` abstraction, experimental Python (tree-sitter; modules/classes/functions/methods, imports, inheritance, decorators, calls with conservative receiver inference), language-versioned re-analysis, Python-aware semantic diff (`PARAMETERS_EXTENDED`, `PARAMETER_ANNOTATIONS_CHANGED`) | Completed — unit + pipeline integration tests, real run on psf/requests (Session.request calls 5/5, no false links) | `parser/language/*`, V12, `docs/MULTI_LANGUAGE.md` |
| Final documentation: README, architecture (system, modules, sequence diagrams), ER diagram, configuration reference, testing, evaluation methodology, consolidated limitations, demo script; diagrams validated with mermaid-cli, links checked | Completed (2026-11-20) | `README.md`, `ARCHITECTURE.md`, `DATABASE_SCHEMA.md`, `docs/*.md` |
| Repository language from the analysed code (public repositories were labelled "Java"), language-neutral UI and progress texts | Fixed while documenting | `IngestionService`, `RepositoryService`, UI |
| Full feature verification on the production stack (98 API + 58 browser checks, monitoring, backup/restore, restart); fixed: error codes missing from error responses, defect-model 404 before training | Completed (2026-11-21) | `docs/VERIFICATION.md`, `docs/verification/*` |
| Final re-verification of all phases on the production stack (2026-11-23): 108/109 API checks (1 outdated expectation, fixed), 62/62 browser checks, operations, image scan 0/0, 180 tests; fixed: validator false alarm on long names, frontend libxml2 CVE (Alpine updates at build), `restore.sh --yes` order | Completed | `docs/VERIFICATION.md`, `docs/verification/api-results-2026-11-23.json` |
| Optional Phase 2 items built (2026-11-23): library resolution from Maven/Gradle dependency jars, centrality metrics, architecture rule management API + UI with ArchUnit platform tests, read-only Monaco code browser; react-router 7 (audit clean); release verification 132/132 API, 68/68 browser, operations, image scan 0/0, 200 tests; fixed: plexus-utils CVE-2025-67030 (pinned 3.6.1) | Completed | `docs/LIBRARY_RESOLUTION.md`, `docs/VERIFICATION.md` |
| GitHub OAuth "Connect with GitHub" for private repositories (signed single-use state, server-side code exchange, encrypted token) | Completed — verified end to end against a fake GitHub (replay/forgery refused) | `GitHubOAuthService`, `GitHubOAuthCallbackPage.tsx`, `docs/verification/oauth` |
| Phase 4: code search index (per-symbol chunks, pgvector + name-weighted full-text, RRF, `auto` mode, full-text fallback), AI review with numbered evidence and deterministic validation, repository assistant, Markdown export, UI (analysis card, AI Assistant tab, search inspector) | Completed — retrieval evaluated on petclinic + held-out requests, 6 AI reviews checked against the diffs, assistant answers checked | `rag/*`, `ai/*`, V13–V14, `docs/AI_REVIEW.md`, `docs/verification/ai` |

## 3a. Phase 5 Deliverables (Intelligent Testing, Security Analysis, GitHub Automation)

| Feature | Status | Where |
|---|---|---|
| CI report ingestion per commit: JaCoCo XML, JUnit/Surefire XML (multi-file), PIT `mutations.xml`, Dependency-Check JSON; XXE-safe | Completed and tested (real reports) | `testing/reports/*`, migration V7 |
| Test selection: reverse graph walk to `@Test` methods, lifecycle-method expansion, direct vs indirect, priorities, changed tests themselves | Completed and tested | `testing/recommendations/service/TestSelectionService` |
| Executed vs recommended tests (PASSED/FAILED/SKIPPED/NOT_EXECUTED/NO_RESULTS) | Completed and tested | same |
| Changed-line coverage (exact commit) + coverage risk factor (static proxy becomes informational) | Completed and tested | `testing/coverage/CoverageService`, `RiskScoringService` |
| Missing-test gaps with JaCoCo evidence; rule-based test scenarios | Completed and tested | `TestSelectionService` |
| Mutation quality of changed methods (score, survivors, killing tests) | Completed and tested (real PIT data) | `testing/mutation/MutationQualityService` |
| Security impact: sensitive-code classification (authN/authZ, crypto, SQL, sensitive data, input handling) of changed and directly impacted code | Completed and tested | `securityanalysis/service/SecuritySensitivityClassifier` |
| Semgrep (12 bundled offline rules) on changed + directly impacted files, CHANGED vs IMPACTED mapping | Completed and tested (real Semgrep 1.182.1) | `SemgrepRunner`, `resources/security/semgrep-java-rules.yml` |
| Build-file dependency diff (Maven properties, Gradle) joined with Dependency-Check CVEs | Completed and tested | `BuildDependencyDiff`, `SecurityAnalysisService` |
| GitHub App: JWT (RS256, PKCS#1/8), scoped cached installation tokens | Completed and tested (fake GitHub API) | `github/app/GitHubAppAuthService` |
| Webhooks: HMAC-SHA256 verification, de-duplication, PR / push / installation handling, fork PR heads | Completed and tested | `github/webhook/*` |
| Check Runs: queued → in_progress → completed, conclusion policy, Markdown summary, annotations (batches of 50) | Completed and tested (fake GitHub API) | `CheckRunPublisher`, `CheckRunOutputBuilder` |
| Team dashboard: open PR risk, high-risk changes, security, risk trend, repository health, execution history | Completed and verified in Chromium | `dashboard/*`, `pages/DashboardPage.tsx` |
| UI: Tests and Security panels, Pull Requests and Quality Reports tabs, GitHub App setup/status/deliveries, responsive shell | Completed and verified in Chromium | `components/analysis/*`, `components/repository/*`, `components/github/*` |
| AI review of the change | Phase 4 (2026-11-22): on request, separate from the deterministic report | `ai/review/*`, `docs/AI_REVIEW.md` |

Verification details: `IMPLEMENTATION_NOTES.md` → "Phase 5 Guide".

## 3. Phase 3 Deliverables (Change Detection, Impact Analysis, Risk Engine)

| Feature | Status | Where |
|---|---|---|
| Git change detection (added/modified/deleted/renamed/copied, line counts, hunks, whitespace-insensitive) | Completed and tested | `diff/service/GitDiffService` |
| Ref resolution (SHA, short SHA, branch, tag) and optional merge-base | Completed and tested | `GitDiffService.resolveRange` |
| Analysis of any commit via isolated snapshot export (working tree untouched) | Completed and tested | `analysis/service/CommitAnalysisService` |
| Comment/formatting-insensitive symbol fingerprints, annotation text, cyclomatic complexity | Completed and tested | `parser/java/SymbolFingerprints`, migration V6 |
| Semantic change classification: parameters, return/field type, visibility, modifiers, hierarchy, moved types, REST mapping, security, JPA mapping, transactions, config binding, body | Completed and tested | `diff/semantic/SemanticChangeDetector`, `ChangeKind` |
| Configuration key changes (`application*.yml/properties`) linked to `@Value`/`@ConfigurationProperties` | Completed and tested | `diff/semantic/ConfigChangeAnalyzer` |
| Impact propagation: configurable depth and relationship types, weighted strongest path, direct vs indirect, packages/modules | Completed and tested | `impact/service/ImpactPropagationService` |
| Removed symbols analysed on the base graph, others on the head graph | Completed and tested | `ChangeAnalysisService` |
| Explainable weighted risk heuristic (10 factors, configurable weights/thresholds, N/A factors excluded) | Completed and tested | `risk/service/RiskScoringService`, `app.risk.*` |
| Historical intelligence: per-commit file churn, merge/revert/bug-fix flags (heuristic labels) | Completed and tested | `history/service/HistoryService` |
| Impact report (JSON + Markdown) with rule-based verification steps | Completed and tested | `reporting/service/ImpactReportService` |
| Async execution via RabbitMQ with progress, failure persistence and DLQ | Completed and tested (integration test) | `analysis/jobs/*` |
| APIs: analyses, changes, impacts, risk, report, report.md, commits, history | Completed and tested (manual E2E) | `ChangeAnalysisController`, `HistoryController` |
| Dashboard: commit pickers, risk distribution, changes by kind, affected packages, risk factor breakdown, impact graph, dependency path viewer, history charts | Completed and verified in Chromium | `pages/AnalysisPage.tsx`, `components/analysis/*` |
| ML-based risk prediction | Intentionally not started (no reliable labels yet; data is being collected) | — |

### Phase 3 verification on spring-petclinic (real commits)
- `c5af32d → 3a93108` ("Remove unused findAll method…"): `OwnerRepository#findAll(Pageable)` REMOVED, 0 dependents; test setup BODY_CHANGED.
- `9a5d50c → 500158f` (8 commits, 21 files): `processFindForm` and `showVetList` detected as PARAMETERS_CHANGED
  (new `RedirectAttributes` parameter, paired with their old signatures), scored HIGH as changed HTTP endpoints;
  7 body changes, 21 additions; 7 test classes referencing the changes; 4 architecture concerns.
- Error paths: identical refs and unsafe refs → HTTP 400; unknown ref → analysis FAILED with message; foreign analysis → 404.

## 3. Phase 2 Detailed Deliverables

### A. Java AST Parsing & Symbol Resolution
- [x] JavaParser 3.28.2 AST traversal (`JavaAstParser.java`, `SymbolExtractionVisitor.java`)
- [x] Extracted code elements: Packages, Classes, Interfaces, Records, Enums, Methods, Constructors, Fields
- [x] Exact 1-based source line range and relative path recording
- [x] Deterministic `JavaSymbolSolver` integration with `CombinedTypeSolver` (`ReflectionTypeSolver` + `JavaParserTypeSolver`)
- [x] Resolution status tracking (`RESOLVED`, `PARTIAL`, `UNRESOLVED`, `EXTERNAL`) — unresolved symbols are never fabricated
- [x] Typed dependency extraction (`EXTENDS`, `IMPLEMENTS`, `METHOD_CALL`, `CONSTRUCTOR_CALL`, `FIELD_ACCESS`, `RETURN_TYPE`, `PARAMETER_TYPE`, `ANNOTATION`)

### B. Relational Storage & Schema Migrations
- [x] Flyway migration `V3__symbols_and_dependencies.sql`:
  - `source_files`: File paths, package names, LOC, size, test flags
  - `symbols`: Relational registry of all AST symbols with visibility, modifier flags, and parent-child hierarchy
  - `dependencies`: Directed dependency links with typed relationships and external flags
  - `architecture_rules`: Configurable boundary rules and constraints
  - `architecture_violations`: Detected architectural anomalies and circular loops

### C. JGraphT Directed Graph Engine & Algorithms
- [x] Directed graph with typed edges: one edge per symbol pair, all relationship kinds and reference counts merged into it (`DependencyGraphBuilder.java`)
- [x] Class-level and package-level projections of the member graph (`DependencyGraphService.projectToTypes`)
- [x] Direct dependency and reverse dependent lookups
- [x] Transitive forward dependency traversal with configurable depth limit
- [x] Transitive reverse dependent traversal (blast radius analysis)
- [x] Shortest dependency path calculation via Dijkstra algorithm (`DependencyPathFinder.java`)
- [x] Cycle detection via strongly connected components (Kosaraju) with one shortest example cycle per group — bounded, unlike enumerating all simple cycles (`CycleDetectorService.java`)
- [x] Coupling and Instability metrics ($Ca, Ce, I$) and architectural hotspot identification (`CouplingMetricsCalculator.java`)

### D. Architecture Rule Engine
- [x] Architecture boundary validator (`ArchitectureRuleEngine.java`)
- [x] Circular dependency detection between classes and packages
- [x] Layer violation detection (e.g. Controller directly accessing Repository without Service layer)
- [x] Full integration into background repository ingestion pipeline (`IngestionService.java`)

### E. Code Exploration APIs & Interactive Frontend
- [x] REST endpoints in `CodeIntelligenceController.java`:
  - `GET /api/v1/code/repositories/{repoId}/symbols` (search with type/query filter)
  - `GET /api/v1/code/symbols/{symbolId}` (individual symbol details)
  - `GET /api/v1/code/symbols/{symbolId}/dependencies` (forward dependencies)
  - `GET /api/v1/code/symbols/{symbolId}/dependents` (blast radius)
  - `GET /api/v1/code/repositories/{repoId}/graph` (React Flow snapshot)
  - `GET /api/v1/code/repositories/{repoId}/paths` (shortest dependency path)
  - `GET /api/v1/code/repositories/{repoId}/architecture/violations` (architecture audit)
  - `GET /api/v1/code/repositories/{repoId}/metrics` (coupling hotspots)
- [x] Interactive Graph Explorer (`DependencyGraphExplorer.tsx`) using `@xyflow/react`:
  - Pan, zoom, node selection, mini-map, package filter
  - Live "Calculate Blast Radius" highlighting all affected dependents in red
  - Symbol Inspector showing line numbers, file paths, and type badges
- [x] Architecture & Hotspots dashboard tab on repository details page

---

## 4. Test Verification Results

**Current (2026-11-21): 163/163 backend tests passing** (`mvn test`, 50 classes, ~5 min); feature verification on
the production stack: 98/98 API checks, 58/58 browser checks, operations — `docs/VERIFICATION.md`, frontend `tsc && vite build`
passing. Coverage by area and how to run: `docs/TESTING.md`; evaluation of each claim: `docs/EVALUATION.md`.

### Phase 5 snapshot (2026-11-18)
- **Backend tests: 100/100 passing, 0 skipped** (`mvn test`). Phase 5 suites: `TestSelectionServiceTest` (5, real graph),
  `CheckRunOutputBuilderTest` (6), `GitHubAppAuthServiceTest` (4), `GitHubWebhookControllerTest` (4),
  `WebhookSignatureVerifierTest` (3, GitHub's documented vector), `SemgrepRunnerTest` (3, real Semgrep scan),
  `BuildDependencyDiffTest` (4), `SecuritySensitivityClassifierTest` (4), `ReportParsersTest` (7, real reports),
  `RiskScoringServiceTest` (+2 coverage), `ChangedLinesTest` (3) and helpers. Earlier suites:
  - `ChangeAnalysisIntegrationTest` — **real PostgreSQL (pgvector) + RabbitMQ via Testcontainers**, a real Git
    repository built from the shop v1/v2 fixtures; covers request → RabbitMQ → worker → JGit → JavaParser →
    semantic diff → impact → risk → report (skipped automatically when Docker is unavailable)
  - `SemanticChangeDetectorTest` (6) — exact expected change set for shop v1 → v2, incl. "comment-only edits not reported"
  - `ImpactPropagationServiceTest` (3), `RiskScoringServiceTest` (4), `GitDiffServiceTest` (1), `ConfigChangeAnalyzerTest` (2), `HistoryLabelTest` (2)
  - Phase 1–2 suites: `SampleProjectAnalysisTest` (7), `WorkspaceManagerTest` (4), `DependencyGraphServiceTest` (6),
    `ArchitectureRuleEngineTest` (2), auth/JWT/encryption/scanner tests
- **Frontend**: `tsc && vite build` passes; Phase 3 and Phase 5 pages exercised in Chromium with 0 console errors/warnings,
  no horizontal overflow at 390 px.
- **Migration upgrade path**: V6 applied to an existing V5 database, V7 to an existing V6 database (with the V2
  `webhook_deliveries` table); Hibernate schema validation passed.

## 5. Architectural Decisions (ADRs)
1. **Deterministic Code Analysis Before AI**: True code relationships are extracted strictly via AST parsing and symbol resolution. LLMs are never used to invent or guess dependencies.
2. **Explicit Resolution Status**: If a method invocation or external type cannot be resolved, it is marked `UNRESOLVED` or `EXTERNAL`, preserving integrity.
3. **JGraphT for Graph Algorithms**: Strongly connected components for cycle groups, BFS for blast radius, Dijkstra for shortest paths.
4. **Robert C. Martin's Instability Metric**: $I = Ce / (Ca + Ce)$ provides a normalized, mathematically grounded metric for component stability.
5. **@xyflow/react for Graph Visualization**: Utilizes React Flow v12 for interactive node-based exploration and instant blast-radius highlighting.
6. **Semantic diff on symbols, not lines**: changes are classified by comparing persisted symbols of both commits
   (fingerprints exclude comments/formatting); line hunks are kept only as evidence.
7. **Base graph for removals, head graph otherwise**: dependents of a removed symbol only exist in the old code.
8. **Risk = transparent heuristic**: weighted mean of 10 normalised factors, every contribution reported, never
   presented as a probability. ML is deferred until labelled outcomes exist (Phase 6).
9. **CI produces quality data, the platform analyses it**: coverage, test results, mutations and CVE scans are uploaded
   per commit; the platform never builds or runs repository code.
10. **Enrichment is non-fatal**: test and security stages record their own failures; the impact report always completes.
11. **Check Run policy separates vulnerabilities from hints**: only introduced vulnerabilities (Semgrep in changed lines,
    CVEs in changed dependencies) or the configured risk level fail a check; tool failures are `neutral`.
12. **Measured evidence supersedes inferred evidence**: JaCoCo changed-line coverage replaces the static test-reference
    proxy in the score (the proxy stays visible with weight 0).

---

## 6. Known Gaps Against the Phase 1–2 Specification
| Item | Status |
|---|---|
| Java 21 target | Done (Java 21, Spring Boot 4.1.2, Spring AI 2.0.2 — see TECH_STACK.md) |
| Testcontainers integration tests | Change analysis (Java + Python), incremental analysis, twin, defect model, observability, isolation, watchdog |
| Role-based authorization (`@PreAuthorize`), rate limiting, Redis usage | Done in Phase 6 (P6-1: admin role, Bucket4j limits; Redis cache) |
| Commit history / commit compare APIs | Done in Phase 3 (`/commits`, `/history`, `/analyses`) |
| ArchUnit integration, architecture rule management API | Done (2026-11-23): rule API + UI (create, edit, switch global rules off per repository, preview, re-evaluate; layered architecture and only-accessed-by rule types; regex step budget); ArchUnit checks the platform's own design rules in the test suite |
| Centrality metrics, DFS traversal API | Done: centrality (in/out-degree, betweenness, PageRank) API + Architecture → Centrality view (2026-11-23); traversal: depth-limited transitive dependencies/dependents and shortest paths |
| Library resolution via Maven/Gradle dependency jars (`JarTypeSolver`) | Done (2026-11-23, `docs/LIBRARY_RESOLUTION.md`): unresolved references petclinic 897 → 6, commons-lang 30,171 → 659 |
| Monaco Editor | Done (2026-11-23): read-only code browser (Code tab) with go-to-declaration, hovers and callers/callees from the analysis |

## 7. Known Limitations (Phase 3)
- Calls into libraries are resolved against the project's dependency jars since 2026-11-23; see `docs/LIBRARY_RESOLUTION.md` for what stays unresolved.
- HTTP-level tests (MockMvc/RestAssured) are not linked to controller methods by static analysis.
- A signature change with several overloads changing at once is reported as REMOVED + ADDED (not guessed).
- When an annotation and the body change together, only the annotation kind is reported.
- (Fixed in Phase 5: indexing and change analysis on one repository are serialised by a per-repository lock.)
- Current, consolidated list: `docs/LIMITATIONS.md`.

## 7a. Known Limitations (Phase 5)
See `IMPLEMENTATION_NOTES.md` → "Known limitations (Phase 5)": MockMvc tests are linked only via coverage, reports
count only for their exact commit, Semgrep scope is changed + directly impacted files, Gradle catalogs are not parsed,
the live GitHub round trip needs the owner's App registration.

## 8. Next Implementation Tasks
1. Owner actions: register the GitHub App and run the live pull-request round trip; choose a license; push the
   branch.
2. Phase 4 follow-up: a paid Gemini plan for regular indexing (the free tier allows 1,000 embedding requests a day).
3. Optional follow-ups: library resolution from build dependencies (`JarTypeSolver`), horizontal scaling (shared
   locks/limits/caches), measured accuracy of Python analysis, more languages through `LanguageParser`.
