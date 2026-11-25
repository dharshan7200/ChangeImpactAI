# ChangeImpact AI — Technology Stack & Compatibility Matrix

## Backend
| Component | Technology | Version | Purpose |
|---|---|---|---|
| Runtime | Java | 21 LTS | Primary language runtime (virtual-thread ready) |
| Framework | Spring Boot | 4.1.2 | Enterprise application framework (Spring Framework 7, modular starters) |
| AI Integration | Spring AI | 2.0.2 | LLM abstraction, tool calling, RAG (Google GenAI / Gemini starter) |
| Database | PostgreSQL + pgvector | 16 | Relational & vector embeddings store |
| Migration | Flyway | 12.4.0 (via `spring-boot-starter-flyway`) | Versioned SQL schema migrations |
| Cache & Rate Limiting | Redis | 7-alpine | Session caching and API rate limiting |
| Message Broker | RabbitMQ | 3.13-management | Decoupled background job processing |
| Git Engine | JGit | 7.5.0 | In-process Git repository inspection and cloning |
| AST & Symbol Solver | JavaParser | 3.28.2 | Abstract Syntax Tree and symbol resolution; five classes patched in-tree for a hot spot, primitive-array overloads and determinism (`backend/src/main/java/com/github/javaparser/`, see IMPLEMENTATION_NOTES → P6-2) |
| Graph Engine | JGraphT | 1.5.3 | Directed symbol dependency graph algorithms; betweenness centrality and PageRank |
| Library resolution | Maven Resolver (`maven-resolver-supplier`) + Maven model builder | 1.9.27 / 3.9.12 | Dependency jars of analysed Java projects for `JarTypeSolver` (docs/LIBRARY_RESOLUTION.md) |
| Architecture tests | ArchUnit (test scope) | 1.5.1 | The platform's own design rules |
| Code browser | Monaco Editor (bundled, read-only) + `@monaco-editor/react` | 0.52.2 / 4.7.0 | Code tab; loaded on demand |
| Routing (frontend) | react-router-dom | 7.18.6 | Upgraded from 6.x (two moderate advisories) |
| Security | Spring Security + JJWT | 7.1.2 / 0.12.6 | JWT authentication and RBAC |
| Documentation | SpringDoc OpenAPI | 3.2.0 (built against Boot 4.1.2) | Automated Swagger 3 documentation |
| JSON | Jackson | 3.1.7 (`tools.jackson`) | HTTP and AMQP serialization; annotations stay `com.fasterxml.jackson.annotation` |
| ORM | Hibernate | 7.4 | JPA provider |
| Messaging client | Spring AMQP | 4.1.2 | RabbitMQ listeners, `JacksonJsonMessageConverter` |
| Testing | Testcontainers | 2.0.5 | PostgreSQL (pgvector) + RabbitMQ integration tests |
| LLM provider | Google Gemini API (REST, own `GeminiClient` on Spring `RestClient`) | `gemini-3.5-flash` (fallback `gemini-3.1-flash-lite`), `gemini-embedding-001` @ 768 dims | AI review, assistant, code-search embeddings (Phase 4, optional). JSON output via `responseSchema`; Spring AI stays only for the availability switch |
| Vector search | pgvector (HNSW, cosine) | extension in `pgvector/pgvector:pg16` | `code_chunks.embedding vector(768)`; with PostgreSQL full-text, fused by RRF |
| HTTP client | Spring `RestClient` (`spring-boot-starter-restclient`) | Boot 4.1.2 | GitHub App / Checks API (JDK HttpClient, 5 s connect / 10 s read, buffered bodies) |
| Static security analysis | Semgrep CE (external CLI, optional) | 1.182.1 verified | Bundled offline rules `security/semgrep-java-rules.yml`; `--metrics=off` |
| Mutation testing | PIT (pitest-maven) + pitest-junit5-plugin | 1.30.2 / 1.2.3 | Reports ingested per commit; also run on this project's own risk/impact/diff code |
| Coverage | JaCoCo | 0.8.16 (this build) — any 0.8.x XML report is ingested | Changed-line coverage, coverage risk factor |
| Dependency vulnerabilities | OWASP Dependency-Check | JSON report format (13.x) | Ingested from CI; joined with build-file dependency changes |
| Metrics | Micrometer + `micrometer-registry-prometheus` | 1.17.2 (Prometheus client 1.7.0) | `/actuator/prometheus` (scrape token or admin), platform metrics `changeimpact_*` |
| Tracing | `spring-boot-starter-opentelemetry` (Micrometer Tracing 1.7.2, OpenTelemetry SDK 1.62.0) | Boot 4.1.2 | Trace/span ids in every log line; OTLP/HTTP export of traces (and optionally metrics) when enabled |
| Monitoring (optional) | Prometheus / Grafana | v3.5.0 / 12.1.1 | `docker compose --profile monitoring`; provisioned datasource, dashboard and 7 alert rules (`monitoring/`) |

## Frontend
| Component | Technology | Version | Purpose |
|---|---|---|---|
| Library | React | 18.3.1 | Component-based user interface |
| Build Tool | Vite | 6.0.3 | Fast frontend dev server and bundler |
| Language | TypeScript | 5.6.3 | Type-safe client development |
| Styling | Tailwind CSS | 3.4.17 | Utility-first CSS styling |
| Icons | Lucide React | 0.468.0 | Clean SVG developer icons |
| Querying | TanStack Query | 5.62.7 | Server state caching and background fetching |
| Routing | React Router DOM | 6.28.0 | Client-side SPA routing |
| Charts | Recharts | 3.10.1 | Phase 3 dashboards (risk distribution, history); Phase 5 team dashboard risk trend |


## Compatibility notes (upgrade of 2026-11-17)
- Spring Boot 3.5 reached end of life on 2026-06-30; Spring AI 2.0.x is the supported line and targets Spring Boot 4.0/4.1.
- Versions verified on Maven Central on 2026-11-17: Spring Boot 4.1.2, Spring AI 2.0.2, springdoc 3.2.0 (parent: Boot 4.1.2).
- Boot 4 migration changes made: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-webclient`,
  Testcontainers 2 artifact names (`testcontainers-postgresql`, ...), `JacksonJsonMessageConverter` (AMQP 4),
  `DaoAuthenticationProvider(UserDetailsService)` (Security 7), removed `write-dates-as-timestamps` (Jackson 3 default),
  removed explicit Hibernate dialect and obsolete Prometheus property.

## Phase 5 notes (2026-11-18)
- Semgrep and Dependency-Check are **not** bundled in the JAR. Semgrep runs when `app.security.semgrep.command`
  points to an installed binary (otherwise the analysis reports `UNAVAILABLE`, never "no findings").
  Dependency-Check needs NVD access/an NVD API key, so its JSON report is uploaded from CI instead of run here.
- GitHub App auth uses only the JDK (`SHA256withRSA`, PKCS#1 → PKCS#8 conversion); no extra JWT library.
- Gemini (Phase 4): `gemini-2.5-flash` is no longer offered to new API keys. Chosen: `gemini-3.5-flash` (served as
  `gemini-3.6-flash` in November 2026) with `gemini-3.1-flash-lite` as fallback when it is overloaded (503 was frequent
  on 2026-11-22; the fallback then wrote the reviews). A small REST client is used instead of Spring AI's ChatClient to
  get Gemini's `responseSchema` JSON output, per-model retries and daily-quota detection. Free tier: 100 embedded
  texts/minute, 1,000 embedding requests/day (`docs/AI_REVIEW.md`).

## Deployment (2026-11-19)

| Component | Version | Notes |
|---|---|---|
| Backend build image | `maven:3.9.11-eclipse-temurin-21-alpine` | BuildKit cache for `~/.m2` |
| Backend runtime | `python:3.13-slim` (Debian 13, glibc) + Temurin JRE 21.0.13 (from `eclipse-temurin:21-jre`) | Semgrep 1.182.1 in a venv (optional), Debian security updates at build, non-root uid 10001; glibc because of the tree-sitter natives |
| Frontend | `node:22-alpine` build → `nginx:1.30-alpine` | envsubst templates, CSP/security headers |
| Data services | `pgvector/pgvector:pg16`, `rabbitmq:3.13-management-alpine`, `redis:7-alpine` | |
| Monitoring | `prom/prometheus:v3.5.0`, `grafana/grafana:12.1.1` | compose profile `monitoring` |
| Image scanning | Trivy 0.76.2 | manual (`docs/DEPLOYMENT.md`) |
| Security upgrades | Guava 33.8.0-jre, RabbitMQ amqp-client 5.38.0 | over the Boot 4.1.2-managed / previous versions |

## Multi-language (2026-11-19)

| Component | Version | Notes |
|---|---|---|
| tree-sitter (JNI binding) | `io.github.bonede:tree-sitter` 0.26.6 | bundled natives: glibc Linux / macOS / Windows, x86-64 + arm64; the official `jtreesitter` needs JDK 22 |
| Python grammar | `io.github.bonede:tree-sitter-python` 0.25.0 | Python analysis |
| JavaScript / TypeScript / TSX grammars | `tree-sitter-javascript` 0.25.0, `tree-sitter-typescript` 0.23.2, `tree-sitter-tsx` 0.23.2 | JavaScript, TypeScript, React |
| PHP, C#, Go, Ruby grammars | `tree-sitter-php` 0.24.2, `tree-sitter-c-sharp` 0.23.1, `tree-sitter-go` 0.25.0, `tree-sitter-ruby` 0.23.1 | |
| HTML, CSS grammars | `tree-sitter-html` 0.23.2, `tree-sitter-css` 0.25.0 | pages/templates, stylesheets |
| SQL | own statement reader (`SqlLanguageParser`) | no tree-sitter SQL grammar is published on Maven Central |
