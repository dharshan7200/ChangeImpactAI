# Deploying ChangeImpact AI

This guide runs the complete platform — web UI, API, workers, PostgreSQL + pgvector, RabbitMQ, Redis and,
optionally, Prometheus + Grafana — with Docker Compose on a single host. Every step below was executed on
2026-11-19 against the images built from this repository (see *Acceptance run* at the end).

## 1. Requirements

| | Minimum | Recommended |
|---|---|---|
| Docker Engine + Compose plugin | 24 / v2.20 | current |
| CPU | 2 cores | 4 cores |
| Memory | 6 GB | 8 GB+ (the backend container is limited to `BACKEND_MEMORY_LIMIT`, default 4 GB) |
| Disk | 10 GB | 20 GB+ (database grows with indexed repositories; Git clones live in the `workspaces` volume) |
| Network (outbound) | Docker Hub, Maven Central, `repo.spring.io`, npm, PyPI, `deb.debian.org` and `dl-cdn.alpinelinux.org` (OS security updates) during the build; the Git hosts in `ALLOWED_GIT_HOSTS` and Maven Central (`LIBRARY_REPOSITORY_URL`, dependency jars of analysed projects) at runtime; `api.github.com` for GitHub integration; `generativelanguage.googleapis.com` only if Gemini is configured |

## 2. Install

```bash
git clone <your fork of this repository> changeimpact && cd changeimpact
./scripts/generate-env.sh https://changeimpact.example.com     # creates .env (mode 600) with random secrets
```

Edit `.env`:

* `FRONTEND_URL` — **the exact URL users open** (scheme, host and port). It is the only origin the API accepts
  (CORS): opening the UI under another name (e.g. `127.0.0.1` when `FRONTEND_URL` says `localhost`) makes every
  API call fail with 403.
* `ADMIN_EMAILS` — accounts that become administrators when they register or log in.
* Optional integrations: GitHub OAuth, GitHub App, Gemini for the AI review, code search and assistant (see
  [CONFIGURATION.md](CONFIGURATION.md) and, for what is sent to Google, [AI_REVIEW.md](AI_REVIEW.md)).

**Keep a copy of `.env` in your secret store.** `TOKEN_ENCRYPTION_KEY` encrypts the stored GitHub tokens; without
it they cannot be decrypted, and a database backup alone cannot restore them.

```bash
docker compose -f docker-compose.full.yml up -d --build
docker compose -f docker-compose.full.yml ps              # all services "healthy" after ~1–2 minutes
```

Open `FRONTEND_URL`, register (use an `ADMIN_EMAILS` address for the first account), connect a repository
(e.g. `spring-projects/spring-petclinic`), wait for indexing to finish and start an analysis between two commits.

The first build downloads all dependencies and takes several minutes (backend ~5 min, frontend ~1 min).
`INSTALL_SEMGREP=false` builds a smaller backend image without the Semgrep CLI; the security stage then reports
Semgrep as unavailable and keeps the other checks.

## 3. What runs where

```
                 ┌──────────── host ────────────┐
 browser ──TLS──▶ reverse proxy (yours) ──▶ :HTTP_PORT (8088)
                 │   frontend  (nginx: static UI, /api/ → backend:8080, security headers)
                 │   backend   (Spring Boot, workers, JGit, JavaParser, Semgrep)  ── not published
                 │   postgres  (pgvector/pgvector:pg16)                          ── not published
                 │   rabbitmq  (job queue + dead-letter queues)                   ── not published
                 │   redis     (cache, rate limiting)                             ── not published
                 │   prometheus 127.0.0.1:9090, grafana 127.0.0.1:3001  (profile "monitoring")
                 └───────────────────────────────┘
```

* Only the web port is published. Actuator endpoints are not routed by nginx; Prometheus scrapes the backend
  inside the compose network with `METRICS_SCRAPE_TOKEN`.
* Containers run as non-root (backend uid 10001). Repository code is cloned and parsed, never built or executed.
* Images: backend = Python 3.13 slim (Debian, glibc — needed by the tree-sitter natives for Python analysis) +
  Temurin 21 JRE (+ Semgrep), Debian security updates applied at build time; frontend = nginx 1.30 Alpine.

### TLS

Terminate TLS in front of the web port (Caddy, Traefik, nginx, a cloud load balancer). Forward `Host` and
`X-Forwarded-Proto`/`X-Forwarded-For`; the bundled nginx passes them on and the backend trusts them from the
private compose network (`FORWARD_HEADERS_STRATEGY=native`). Example with Caddy:

```
changeimpact.example.com {
    reverse_proxy 127.0.0.1:8088
}
```

Set `HTTP_BIND=127.0.0.1` in `.env` when the reverse proxy runs on the same host, so the plain-HTTP port is not
reachable from outside.

### Private repositories: "Connect with GitHub" (OAuth App)

1. GitHub → *Settings → Developer settings → OAuth Apps → New OAuth App*: homepage `FRONTEND_URL`, authorization
   callback URL `<FRONTEND_URL>/github/oauth/callback`.
2. Put the client ID and a generated client secret into `.env` (`GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`)
   and restart the backend.
3. Users open **GitHub → Connect with GitHub**, approve the `repo` scope, and can then connect their private
   repositories. The code is exchanged server-side; the token is stored AES-256-GCM encrypted.

Without an OAuth App, users can paste a personal access token (classic or fine-grained with repository read access)
on the same page.

### GitHub App webhooks

Point the App's webhook URL at `https://<your host>/api/v1/webhooks/github`; requests are verified with
`GITHUB_WEBHOOK_SECRET` (see `IMPLEMENTATION_NOTES.md`, Phase 5 guide).

## 4. Monitoring (optional)

```bash
docker compose -f docker-compose.full.yml --profile monitoring up -d
```

Prometheus (`127.0.0.1:9090`) scrapes `/actuator/prometheus` with the scrape token (a compose secret created from
`METRICS_SCRAPE_TOKEN`); alert rules are in `monitoring/prometheus/alerts.yml`. Grafana (`127.0.0.1:3001`, user
`admin`, password `GRAFANA_ADMIN_PASSWORD`) has the *ChangeImpact AI — Overview* dashboard provisioned.

## 5. Backup and restore

What holds state:

| Data | Where | Back up? |
|---|---|---|
| Repositories, symbols, graphs, analyses, reports, users, encrypted tokens | PostgreSQL (`postgres_data`) | **yes** — `scripts/backup.sh` |
| Secrets (`TOKEN_ENCRYPTION_KEY`, passwords) | `.env` | **yes** — separately, in a secret store |
| Git clones | `workspaces` volume | no — re-cloned on the next index/analysis |
| Dependency jars of analysed projects | `libraries` volume | no — downloaded again on demand |
| Cache, rate-limit counters | Redis | no |
| Pending job messages | RabbitMQ | no — job state is in PostgreSQL; the watchdog fails jobs that never finish |

```bash
./scripts/backup.sh /var/backups/changeimpact                 # pg_dump (custom format) + .sha256
KEEP_BACKUPS=14 ./scripts/backup.sh /var/backups/changeimpact # and keep only the newest 14
./scripts/restore.sh /var/backups/changeimpact/changeimpact-<timestamp>.dump   # asks for confirmation
```

Schedule backups with cron or a systemd timer, e.g. `15 2 * * * cd /opt/changeimpact && KEEP_BACKUPS=14 ./scripts/backup.sh /var/backups/changeimpact`.
`restore.sh` stops the backend, recreates the database from the dump, verifies the checksum first and restarts
the services; Flyway then applies any newer migrations. Restore with the same `TOKEN_ENCRYPTION_KEY`.
Copy backups off the host.

## 6. Upgrade

```bash
./scripts/backup.sh /var/backups/changeimpact
git pull
docker compose -f docker-compose.full.yml up -d --build
```

Database migrations (Flyway) run automatically at backend start-up and are forward-only: to roll back, restore the
backup taken before the upgrade and start the previous version.

## 7. Operations

| Task | Command |
|---|---|
| Logs | `docker compose -f docker-compose.full.yml logs -f backend` |
| Status | `docker compose -f docker-compose.full.yml ps` |
| Restart backend | `docker compose -f docker-compose.full.yml restart backend` |
| Stop (keep data) | `docker compose -f docker-compose.full.yml down` |
| Remove everything incl. data | `docker compose -f docker-compose.full.yml down -v` (irreversible) |
| Scan images | `docker run --rm -v "$PWD:/work:ro" -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.2 image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile /work/docker/trivyignore.yaml changeimpact-backend:latest` (accepted findings and their justification: `docker/trivyignore.yaml`) |

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `required variable … is missing a value` on `up` | `.env` is missing or incomplete — run `scripts/generate-env.sh` |
| Backend restarts, log shows `Unsafe production configuration:` | a secret is weak or a published development default — the log lists each problem |
| Login does nothing / API calls 403 | the browser URL differs from `FRONTEND_URL` (CORS) |
| Backend unhealthy, `Connection refused` to postgres/rabbitmq | a dependency is still starting; compose waits for health checks, check `docker compose ps` |
| Indexing fails with `Git host … is not allowed` | add the Git host to `ALLOWED_GIT_HOSTS` |
| Jobs stay QUEUED | backend not consuming — check the backend log; `MAX_CONCURRENT_JOBS` limits parallel jobs |
| Repository page says "Libraries: the build's dependencies could not be resolved" | Maven Central (or `LIBRARY_REPOSITORY_URL`) not reachable, a limit exceeded or private dependencies; the message names the cause. Allow the host or set a mirror, then re-index (a re-index retries) |
| Out of memory on large repositories | raise `BACKEND_MEMORY_LIMIT` (the JVM uses 75 % of it) |
| No AI review / AI Assistant tab says "not configured" | `GEMINI_API_KEY` is empty — expected; all deterministic analyses still run |
| Code search shows "Vector search unavailable: the daily Gemini quota … is used up" | Gemini free tier (1,000 embedding requests/day). Search uses full-text until the quota resets; use a paid plan or a lower indexing volume (`docs/AI_REVIEW.md`) |
| AI search index build is slow | it embeds at `GEMINI_EMBED_PER_MINUTE` texts/minute (free tier 100); re-indexing reuses unchanged embeddings |
| AI review "written by" the fallback model | `GEMINI_CHAT_MODEL` was overloaded (503/429) twice; the fallback (`GEMINI_FALLBACK_MODEL`) answered. The model used is shown on every review |
| Backend build fails at `apt-get update`, or frontend build fails with `stale/unavailable repositories` (apk) | `deb.debian.org` / `dl-cdn.alpinelinux.org` is not reachable from the build; allow it (recommended — security updates) or build with `APPLY_OS_UPDATES=false` |
| Build fails behind a TLS-inspecting proxy | pass the proxy CA: `docker build --secret id=ca_cert,src=<ca.pem> …` (and `--build-arg HTTPS_PROXY=…`); `docker/proxy-env.sh` configures Maven, npm and pip with it during the build only |

## Acceptance run (2026-11-19)

Executed from this guide on a clean set of volumes:

1. `scripts/generate-env.sh` → `.env` with random secrets; a second run refuses to overwrite it.
2. Images built: backend 1.13 GB (Java 21.0.13, Semgrep 1.182.1, uid 10001), frontend 75 MB.
   Trivy 0.76.2: **0 fixable HIGH/CRITICAL** in both images, after upgrading Guava to 33.8.0, the RabbitMQ client to
   5.38.0, nginx to 1.30, urllib3/msgpack/setuptools, and removing pip from the runtime image.
3. `docker compose -f docker-compose.full.yml up -d` → all services healthy; `/healthz` ok; actuator not routed.
4. Through `http://localhost:8088`: register → connect `spring-projects/spring-petclinic` → index (50 files,
   323 symbols) → analysis `818c413 → 500158f` completed (HIGH 58.5, 7 changed symbols, 9 impacts, checklist,
   reviewers, drift). The UI (Chromium) loaded the analysis and repository pages with 0 console errors under the CSP;
   deep links served with `no-cache`; from a different origin the login was rejected (403), as intended.
5. `--profile monitoring`: Prometheus target `changeimpact-backend` up with the scrape token; 14 `changeimpact_*`
   metric families; Grafana dashboard provisioned.
6. `backup.sh` → 500 KB dump + checksum; analyses deleted; `restore.sh --yes` → data back, services healthy.
7. `down` + `up` with the rebuilt images → previous analysis intact; a new 30-commit analysis completed
   (including the Semgrep security stage).

Found and fixed during the run: the backend could not start with an empty `GEMINI_API_KEY` (Spring AI's Gemini
client refuses a blank key) — `AiAvailability` now switches the AI auto-configuration off in that case.
