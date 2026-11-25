# Configuration reference

All settings are environment variables. For the Docker deployment they go in `.env` (template: `.env.example`,
generator: `scripts/generate-env.sh`) and are passed to the containers by `docker-compose.full.yml`. For local
development, `application.yml` supplies development defaults and no `.env` is needed.

With `APP_PROFILE=prod` (always the case in the Docker image) the backend **refuses to start** when a required
secret is missing, shorter than required or a published development default (`ProductionSafetyCheck`); the log
lists every problem.

## Required (production)

| Variable | Description |
|---|---|
| `FRONTEND_URL` | Public URL of the web UI; the only allowed CORS origin; used in links |
| `DB_PASSWORD` | PostgreSQL password (not `changeimpact`) |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ credentials (not `guest`/`guest`) |
| `REDIS_PASSWORD` | Redis password (compose starts Redis with `--requirepass`) |
| `JWT_SECRET` | HS256 signing key, ≥ 32 bytes |
| `TOKEN_ENCRYPTION_KEY` | AES-256-GCM key for stored GitHub tokens: Base64 of exactly 32 bytes (`openssl rand -base64 32`). Back it up |

## General

| Variable | Default | Description |
|---|---|---|
| `CHANGEIMPACT_PUBLIC_URL` | `FRONTEND_URL` | Base URL used in PR comments / Check Runs |
| `HTTP_BIND`, `HTTP_PORT` | `0.0.0.0`, `8088` | Published web port (compose) |
| `ADMIN_EMAILS` | — | Comma-separated e-mails granted the ADMIN role |
| `BCRYPT_STRENGTH` | `12` | Password hashing cost (≥ 10 in prod) |
| `ALLOWED_GIT_HOSTS` | `github.com` | Hosts repositories may be cloned from |
| `ALLOW_PRIVATE_GIT_ADDRESSES` | `false` | Allow Git hosts resolving to private IPs (SSRF guard) |
| `ALLOW_LOCAL_REPOSITORIES` | `false` | Allow `file://` repositories (rejected in prod) |
| `MAX_CONCURRENT_JOBS` | `3` | Parallel indexing/analysis jobs |
| `JOB_TIMEOUT_MINUTES` | `30` | Watchdog: RUNNING longer → FAILED |
| `JOB_QUEUED_TIMEOUT_MINUTES` | `720` | Watchdog: QUEUED longer → FAILED |
| `INCREMENTAL_ANALYSIS_ENABLED` | `true` | Reuse unchanged files of an already analysed commit |
| `LIBRARY_RESOLUTION_ENABLED` | `true` | Resolve the dependency jars of analysed Java projects (Maven/Gradle builds) so library calls get typed; jars are read, never executed ([LIBRARY_RESOLUTION.md](LIBRARY_RESOLUTION.md)) |
| `LIBRARY_REPOSITORY_URL` | `https://repo.maven.apache.org/maven2` | The only repository contacted (builds' own repositories are mirrored to it); point it at an internal mirror if needed |
| `LIBRARY_MAX_ARTIFACTS`, `LIBRARY_MAX_TOTAL_MB`, `LIBRARY_TIMEOUT_SECONDS` | `400`, `1024`, `300` | Limits per commit; exceeding one → status `FAILED` (analysis continues without libraries) |
| `LIBRARY_CACHE_PATH` | `/var/lib/changeimpact/libraries` | Shared jar cache (compose volume `libraries`) |
| `ANALYSIS_LANGUAGES` | `all` | `all` = Java, Python, JavaScript/TypeScript (React), PHP, C#, Go, Ruby, HTML, CSS, SQL; or a list (`java,python,javascript`); `-name` removes one (`all,-sql`); Java is always on ([MULTI_LANGUAGE.md](MULTI_LANGUAGE.md)) |
| `BACKEND_MEMORY_LIMIT` | `4g` | Container memory limit (compose); JVM uses 75 % |
| `BACKEND_JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM options (compose) |
| `INSTALL_SEMGREP` | `true` | Build-time: include the Semgrep CLI in the backend image |
| `APPLY_OS_UPDATES` | `true` | Build-time: apply OS security updates to both images (Debian for the backend, needs `deb.debian.org`; Alpine for the frontend, needs `dl-cdn.alpinelinux.org`; the build fails if it is unreachable — `false` skips the step) |
| `IMAGE_PREFIX`, `IMAGE_TAG` | `changeimpact`, `latest` | Image names built/used by compose |

## Rate limiting and retention

| Variable | Default | Description |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | Per-client request limits |
| `RATE_LIMIT_AUTH_PER_MINUTE` | `10` | Login/registration requests |
| `RATE_LIMIT_API_PER_MINUTE` | `300` | Other API requests |
| `RATE_LIMIT_WEBHOOK_PER_MINUTE` | `600` | GitHub webhook deliveries |
| `RETENTION_ENABLED` | `true` | Daily clean-up job |
| `RETENTION_ANALYSES_DAYS` | `180` | Delete analyses older than this |
| `RETENTION_REPORTS_DAYS` | `90` | Delete uploaded test/coverage reports older than this |
| `RETENTION_WEBHOOK_DAYS` | `30` | Delete webhook delivery records older than this |

## GitHub

| Variable | Description |
|---|---|
| `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` | OAuth App for "Connect with GitHub" (private repositories). Callback URL of the App: `<FRONTEND_URL>/github/oauth/callback`. Without it, users paste a personal access token |
| `GITHUB_OAUTH_SCOPES` | `repo read:user user:email` — `repo` is what grants private-repository access |
| `GITHUB_OAUTH_REDIRECT_URI` | default `<FRONTEND_URL>/github/oauth/callback`; must equal the App's callback URL |
| `GITHUB_WEB_BASE_URL` | `https://github.com` — GitHub Enterprise: `https://<host>` (with `GITHUB_API_BASE_URL=https://<host>/api/v3`) |
| `GITHUB_APP_ID`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_SLUG` | GitHub App: automatic PR analysis and Check Runs |
| `GITHUB_APP_PRIVATE_KEY` | App private key (PEM contents; outside Docker `GITHUB_APP_PRIVATE_KEY_PATH` may point to a file instead) |
| `GITHUB_WEBHOOK_SECRET` | Required when the App is configured |
| `GITHUB_API_BASE_URL` | `https://api.github.com` (GitHub Enterprise: `https://<host>/api/v3`) |
| `GITHUB_CHECKS_FAIL_ON` | `CRITICAL` — Check Run fails at this risk level (`LOW`…`CRITICAL`, `NEVER`) |
| `GITHUB_CHECKS_FAIL_ON_SECURITY` | `HIGH` — Check Run fails for introduced findings at this severity |

## AI (optional)

| Variable | Default | Description |
|---|---|---|
| `GEMINI_API_KEY` | — | Google Gemini key. Empty → AI review, assistant and AI code search off (`app.ai.enabled=false`); every deterministic analysis still runs |
| `GEMINI_CHAT_MODEL` | `gemini-3.5-flash` | Chat model for AI review and the assistant (`gemini-2.5-flash` is no longer offered to new keys) |
| `GEMINI_FALLBACK_MODEL` | `gemini-3.1-flash-lite` | Used after the chat model failed twice with 429/5xx/timeout |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` | Embedding model, requested at 768 dimensions (the `code_chunks.embedding` column) |
| `GEMINI_EMBED_PER_MINUTE` | `100` | Texts embedded per minute (client-side throttle; free tier allows 100). Indexing time ≈ chunks ÷ this value minutes for the first build |
| `GEMINI_TIMEOUT_SECONDS` | `90` | Timeout of one Gemini request |
| `GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta` | API base URL (tests point it at a fake server) |

What leaves the server when AI is used, and what is checked before an answer is shown: [AI_REVIEW.md](AI_REVIEW.md).

## Observability

| Variable | Default | Description |
|---|---|---|
| `METRICS_SCRAPE_TOKEN` | — | Bearer token for `/actuator/prometheus` (≥ 24 chars); required for `--profile monitoring` |
| `GRAFANA_ADMIN_PASSWORD` | — | Grafana `admin` password |
| `PROMETHEUS_PORT`, `GRAFANA_PORT` | `9090`, `3001` | Bound to 127.0.0.1 |
| `OTEL_TRACING_ENABLED` | `false` | Export traces via OTLP |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://otel-collector:4318/v1/traces` | OTLP endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` (prod) | Trace sampling ratio |

## Development only

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_VHOST`, `REDIS_HOST`,
`REDIS_PORT`, `SERVER_PORT`, `JWT_ACCESS_EXPIRY_MS`, `JWT_REFRESH_EXPIRY_MS`, `SWAGGER_UI_ENABLED`,
`API_DOCS_ENABLED`, `FORWARD_HEADERS_STRATEGY`, `INGESTION_WORKSPACE_PATH` — set by compose/the image or only
needed when running the backend outside Docker.
