# Phase 6 — Production Hardening, Advanced Intelligence and Deployment (plan)

**Status (2026-11-20): completed.** Workstreams 1–8 are done; the GitHub Actions CI in workstream 4 was dropped by
the owner. Results: `PROJECT_PROGRESS.md` §3b, `docs/EVALUATION.md`, `docs/LIMITATIONS.md`.

Acceptance criterion (master prompt): *a new developer can clone the project, configure the environment, start the
application, connect a supported repository and complete an end-to-end analysis using the documentation.*
Work is ordered so that this criterion is met first; the optional/conditional items follow.

Prerequisite: Phase 5 re-verified on 2026-11-18 (failure paths, concurrency, redeliveries; 110/110 tests).
Phase 4 (LLM) is still deferred by the owner — LLM latency/token metrics are prepared as names only.

## Research (2026-11-18, verified against Maven Central / registries from this environment)
| Topic | Finding | Decision |
|---|---|---|
| Metrics | `/actuator/prometheus` is configured but `micrometer-registry-prometheus` is missing → endpoint does not exist | add the registry (Boot-managed version) |
| Tracing | Boot 4.1.2 ships `spring-boot-starter-opentelemetry` (OTLP metrics + traces, Micrometer bridge); do not mix with the community OTel starter | use it; exporter disabled unless an OTLP endpoint is configured |
| Dashboards | `prom/prometheus:v3.5.0`, `grafana/grafana:12.1.1` pull here | compose `observability` profile with provisioned dashboard |
| Runtime image | `eclipse-temurin:21-jre-alpine`, `node:22-alpine` pull here (Docker Hub rate-limits occasionally) | multi-stage images, non-root |
| Rate limiting | Bucket4j `com.bucket4j:bucket4j_jdk17-core` 8.22.0 | per-user/IP token buckets (in-process) |
| Multi-language | official `jtreesitter` needs JDK 22 (final FFM API) → not usable on Java 21; JNI binding `io.github.bonede:tree-sitter` 0.26.6 + `tree-sitter-python` / `tree-sitter-javascript` 0.25.0 (no TypeScript/Go artifacts) | parser abstraction + experimental Python support, accuracy not claimed |
| ML | Smile 7.3.0 available; a logistic-regression baseline needs no library | own small, inspectable implementation; SZZ labels; train only if labels suffice |
| Scanning | GitHub release downloads are blocked here (403) | Trivy/dependency scans run in CI; locally via container if pullable |

## Workstreams (in order)
1. **Production configuration & security hardening**
   - production-safe defaults (no SQL bind logging, fail fast on missing secrets in `prod`), log secret redaction
   - SSRF guard for Git operations (https only, allowed hosts, no private/loopback addresses; local repos only behind an explicit dev flag)
   - rate limiting (auth + API), method security / admin-only operational endpoints
   - repository isolation tests (user A cannot read user B's repositories, analyses, reports, PRs)
   - data retention (analyses, webhook deliveries, workspaces/snapshots) with configurable periods
   - security assessment document (threats, controls, evidence)
2. **Performance & scalability**
   - job timeouts (watchdog for stuck QUEUED/RUNNING jobs), consumer concurrency limits, retry/backoff review
   - workspace storage cleanup, DB index review (`EXPLAIN` on hot queries), graph cache bounds
   - incremental indexing: unchanged commits reused (done in Phase 5 hardening); measure what full re-parse costs
   - benchmarks on repositories of different sizes (indexing + analysis time, symbols, memory)
3. **Observability** — Prometheus registry, OTLP tracing (optional), custom metrics (indexing/graph/analysis stage
   durations, job failures, queue depth, webhook deliveries, check-run publishing, Semgrep), Prometheus + Grafana
   provisioning, verified with real traffic
4. **Deployment** — backend/frontend Dockerfiles, `docker-compose` full stack, `.env.example`, health checks,
   ~~GitHub Actions CI~~ (dropped by the owner, 2026-11-19), backup/recovery guide; **acceptance test: start the
   full stack from the docs and run an end-to-end analysis of a public repository**
5. **Architecture digital twin** — versioned architecture snapshots per commit (package graph, metrics, cycles,
   violations), commit-to-commit comparison (new dependencies, new cycles, coupling changes, drift), trends, module health; UI
6. **Historical ML prototype (conditional)** — dataset from history (features listed in the master prompt), SZZ
   bug-introducing labels, time-ordered evaluation (precision, recall, F1, ROC-AUC, calibration, cross-repository);
   baseline first; trained/served only if labels are sufficient — otherwise a documented negative result
7. **Multi-language (optional)** — `LanguageParser` abstraction; experimental Tree-sitter Python parser
8. **Final documentation** — README, architecture/ER/sequence diagrams, env var reference, security, testing,
   evaluation methodology, limitations, demo instructions

## Verification approach
Every workstream ends with real execution here: tests, a running stack, real repositories and screenshots where UI
changes. Items that cannot be executed here (GitHub-hosted CI, live GitHub App) are stated as such.
