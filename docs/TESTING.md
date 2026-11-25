# Testing

Automated tests cover each analysis stage, with fixtures whose correct answer is known. They are backed by
verification runs on real public repositories and real infrastructure, recorded in `IMPLEMENTATION_NOTES.md` and
`docs/EVALUATION.md`.

## Running the tests

**Backend.** Needs Java 21, Maven 3.9 and Docker:

```bash
docker compose up -d rabbitmq redis   # the test profile expects RabbitMQ and Redis on localhost
cd backend
mvn test                              # 200 tests, about 7 minutes on 4 cores
mvn test -Dtest=SemanticChangeDetectorTest            # one class
```

* **PostgreSQL** (pgvector) comes from Testcontainers. Integration tests also start their own RabbitMQ container.
* **Without Docker**, the Testcontainers tests are skipped automatically (`disabledWithoutDocker = true`).
* **Without the local RabbitMQ and Redis**, `ObservabilityIntegrationTest` fails its health assertion with 503,
  because Redis is reported DOWN.
* **Semgrep:** `SemgrepRunnerTest` runs a real Semgrep scan when the CLI is installed (`SEMGREP_COMMAND`; it is in the Docker
  image).
* **Tree-sitter languages** (Python, JavaScript/TypeScript, PHP, C#, Go, Ruby, HTML, CSS): the parser tests need the
  tree-sitter native library (glibc Linux, macOS or Windows) and are skipped where it cannot load.
* **Real-repository statistics** (`RealRepoProbe`) run only with `PROBE_REPOS=<folder of clones> PROBE_OUT=<file>`;
  otherwise the class is skipped.

**Frontend:**

```bash
cd frontend
npm ci
npx tsc --noEmit     # type check
npm run build        # tsc + production build
```

There are no automated frontend tests. UI changes were verified in Chromium with Playwright scripts, run against the
dev stack and the Docker stack: console errors, layout at 1440 px and 390 px, and screenshots in `docs/images`.

## What the tests cover

Results of the last full run (2026-11-23): **200 passed, 0 failed, 0 skipped**. Feature-level verification on the
production stack (109 API checks, 62 browser checks, operations, image scan): [VERIFICATION.md](VERIFICATION.md).

| Area | Test classes (tests) | What is checked |
|---|---|---|
| Parsing (Java) | `SampleProjectAnalysisTest` (7), `JavaAstParserTest` (2), `OverloadResolutionTest` (4), `TypeParameterDeterminismTest` (2), `JavaFileScannerTest` (1) | Symbols, lines, resolution status on a sample project. Overload choice matches javac for 22 calls in 40 shuffled orders. Deterministic type-parameter resolution. Both of the latter fail on upstream JavaParser. |
| Library resolution | `LibraryResolutionTest` (5) | Offline: a library compiled at test time and published to a file-based Maven repository; two-module Maven reactor (parent, `${revision}`, module excluded, POM repository ignored); overload with a library-typed argument resolves only with the jar; Gradle Kotlin/Groovy DSL, version catalog, BOMs, Spring Boot plugin; NO_BUILD_FILE, DISABLED, FAILED. |
| Parsing (Python) | `PythonLanguageParserTest` (5), `IngestionLanguageTest` (2) | Modules, classes, functions, methods, parameters, complexity. Relative and aliased imports, `EXTENDS`, decorators, calls. Receiver inference only where certain. Hashes ignore comments and docstrings. The repository language comes from the analysed code (markup and SQL never label a repository with program code). |
| Parsing (other languages) | `JavaScriptLanguageParserTest` (3), `PhpLanguageParserTest` (2), `CSharpLanguageParserTest` (2), `GoLanguageParserTest` (1), `RubyLanguageParserTest` (1), `WebAndSqlParsersTest` (5), `JavaAstParserTest.jpaEntitiesAndEmbeddedSql`, `LanguageRegistryTest` (3), `FileCategoryTest` (1) | One realistic fixture project per language (React/TS with tsconfig paths and CSS Modules, Laravel, ASP.NET Core, a Go module, Rails, Thymeleaf/Jinja, SQL migrations): exact expected symbols and links per dependency kind, and links that must **not** be made (calls on values of unknown type). Formatting/comment-insensitive hashes; BOM, emoji and Latin-1 files; JPA entity/column and SQL-in-string links; `all`/aliases/exclusions in `ANALYSIS_LANGUAGES`. |
| Incremental analysis | `IncrementalAnalysisIntegrationTest` (1) | Four commits: every file, symbol and dependency row (including the linked declaration) equals a fresh full analysis. |
| Graph & architecture | `DependencyGraphServiceTest` (6), `ArchitectureRuleEngineTest` (2), `ArchitectureTwinAlgorithmsTest` (3), `ArchitectureTwinIntegrationTest` (1) | Views, blast radius, cycles, rule violations. Twin snapshot invariants (coupling = edges, dependency order), new cycle detected, comparison and trend. |
| Centrality, architecture rules, ArchUnit | `CentralityServiceTest` (3), `ArchitectureRuleTypesTest` (7), `PlatformArchitectureTest` (5) | Betweenness and PageRank against hand-computed values. Layered architecture (upward, strict skipping), only-accessed-by, forbidden dependency; regex budget; rule validation. Platform design rules: deterministic analysis independent of AI, only the Semgrep runner starts processes, entity/service layering, constructor injection. |
| Diff & history | `SemanticChangeDetectorTest` (8), `ConfigChangeAnalyzerTest` (2), `GitDiffServiceTest` (2), `HistoryLabelTest` (2), `ChangedLinesTest` (3) | The exact expected change set for shop v1 → v2. Comment-only edits are not reported. Deterministic pairing of same-named declarations. Python parameter compatibility. Configuration keys, rename detection, bug-fix/revert labels. |
| Impact & risk | `ImpactPropagationServiceTest` (3), `RiskScoringServiceTest` (6), `ShortSymbolTest` (1) | Strongest-path propagation, depth decay, relationship weights. Every risk factor's contribution. Coverage replacing the static proxy. |
| Change analysis (end to end) | `ChangeAnalysisIntegrationTest` (1), `PythonAnalysisIntegrationTest` (1) | Request → RabbitMQ → worker → JGit → parser → semantic diff → impact → risk → report, on real Git repositories (Java and Python). Uses PostgreSQL + RabbitMQ containers. |
| Tests & quality data | `ReportParsersTest` (7), `TestSelectionServiceTest` (5), `MutationQualityServiceTest` (1) | Real JaCoCo, JUnit, PIT and Dependency-Check reports (XXE payload rejected). Test selection on a real graph. Mutation scores. |
| Security analysis | `SemgrepRunnerTest` (4), `SecuritySensitivityClassifierTest` (4), `BuildDependencyDiffTest` (4), `SecurityAnalysisServiceTest` (2) | A real Semgrep scan (9 Java rules hit, 3 safe variants not); a second scan over Python, JS/TS/TSX, PHP, C#, Go and Ruby fixtures whose lines carry `expect:`/`ok` markers: every multi-language rule hit where marked, never on a safe variant, nothing unmarked. Sensitive-code rules. Maven/Gradle dependency diff joined with CVEs. |
| GitHub | `WebhookSignatureVerifierTest` (3), `GitHubWebhookControllerTest` (4), `GitHubWebhookServiceTest` (2), `GitHubAppAuthServiceTest` (4), `CheckRunPublisherTest` (6), `CheckRunOutputBuilderTest` (6), `TokenEncryptionServiceTest` (4) | GitHub's documented HMAC test vector. Redelivery and de-duplication. App JWT and installation tokens against a mock server. Check Run lifecycle, limits, annotations. AES-GCM token encryption. |
| Security & operations | `AccessIsolationIntegrationTest` (6), `GitUrlGuardTest` (3), `WorkspaceManagerTest` (5), `ProductionSafetyCheckTest` (3), `SecretRedactorTest` (2), `AiAvailabilityTest` (3), `GlobalExceptionHandlerTest` (3), `JwtServiceTest` (3), `AuthServiceTest` (3), `ObservabilityIntegrationTest` (2), `JobWatchdogIntegrationTest` (1) | Tenant isolation (11 read endpoints → 404), admin-only operations, rate limiting. SSRF guard, path traversal. Unsafe production configuration refused. Secrets redacted in logs and stack traces. AI optional. Error responses carry machine-readable codes. Metrics endpoint protected. Stuck jobs failed. |
| AI review, search, assistant | `AiGroundingTest` (14) | Citation and code-name validation (backticks and code-shaped plain text, literals accepted, invented names flagged). `auto` query classification. Full-text query building. Daily-quota detection and retry delays. An index build never blocks a search. pgvector literals. Markdown export keeps each finding's status. |
| GitHub OAuth | `GitHubOAuthServiceTest` (3) | State bound to the user, HMAC-signed, single use, 10-minute expiry. Code exchange and account connection against a mock server. |
| Developer workflow | `ReviewerSuggestionServiceTest` (3) | Ownership arithmetic, recency, exclusion of the change's authors (by e-mail and name) and of bots, checklist rules. |
| Defect model | `DefectPredictionTest` (5), `DefectModelIntegrationTest` (1) | Features from prior history only. SZZ links a fix to the introducing commit. Known-value AUC/Brier/ECE. Deterministic logistic regression that recovers a signal. Gate refuses too few labels. Full pipeline on a generated repository with a planted pattern. |

### Fixtures

* `backend/src/test/resources/sample-projects/shop` and `shop-v2` form a small Spring-style Java application. Its two
  versions contain a signature change, a removal, a move, a comment-only edit and a new caller.
* Other tests build Git repositories in temporary directories with JGit (Python projects, the defect-model history,
  incremental-analysis commits).

## Verification beyond unit tests

| Run | Where it is recorded |
|---|---|
| Change analysis, test intelligence, Semgrep, GitHub App flow (signed webhooks against a fake GitHub API) | `IMPLEMENTATION_NOTES.md` (Phase 3 and Phase 5) |
| Failure paths: unreachable GitHub, missing PR head, redeliveries, concurrent jobs, browser upload | `IMPLEMENTATION_NOTES.md` (deep re-verification) |
| Parser correctness and determinism on commons-lang (128,054 rows identical across JVMs), benchmarks | `IMPLEMENTATION_NOTES.md` (P6-2) |
| Architecture twin on commons-lang and spring-petclinic-rest, checked against `git log` and the sources | `IMPLEMENTATION_NOTES.md` (P6-5) |
| Full Docker stack acceptance run from the deployment guide, backup/restore, monitoring, image scan | `docs/DEPLOYMENT.md` |
| Defect model on four repositories with leakage (permutation) check | `docs/DEFECT_MODEL.md` |
| Python analysis of `psf/requests`, spot-checked against the source | `docs/MULTI_LANGUAGE.md` |
| Retrieval (labelled queries), AI reviews checked against the diffs, assistant answers (real Gemini API) | `docs/AI_REVIEW.md`, `docs/verification/ai` |

## Offline evaluation harness

`DefectEvaluationRun` runs only when repositories are passed, and writes a JSON report:

```bash
mvn test -Dtest=DefectEvaluationRun -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddefect.eval.repos=commons-lang=/path/to/commons-lang,guava=/path/to/guava \
  -Ddefect.eval.out=/tmp/defect-evaluation.json
```

## Not covered by automated tests

* **The live GitHub round trip** (a real App registration and a public webhook URL) was verified against a local
  fake GitHub API that checks signatures, scopes and limits.
* **CI.** There is no CI pipeline; the owner decided it was not required. Run the commands above before a release,
  plus the image scan from `docs/DEPLOYMENT.md`.
* **Frontend.** Component behaviour is verified manually or with ad-hoc Playwright scripts, not a committed suite.
