# ChangeImpact AI — Security Assessment

Scope: backend API, background workers, GitHub integration (OAuth/PAT and GitHub App), report ingestion, frontend.
Status: 2026-11-18, Phase 6. Each control lists how it is verified; "test" = automated test in `backend/src/test`,
"live" = verified against the running application.

## Assets and trust boundaries
| Asset | Where | Protection |
|---|---|---|
| GitHub access tokens (PAT/OAuth) | `connected_accounts` | AES-256-GCM, random 96-bit IV per value; key from `TOKEN_ENCRYPTION_KEY` |
| GitHub App private key, webhook secret | files / environment | never stored in the DB, never logged |
| User passwords | `users` | BCrypt (strength 12 in prod) |
| Refresh tokens | `refresh_tokens` | stored as SHA-256 hashes, rotated |
| Customer source code | Git workspaces, symbol tables | per-repository workspace directories, owner-scoped queries |
| Analysis results, uploaded reports | PostgreSQL | owner-scoped queries, retention policy |
| Code sent to Gemini (only when AI features are used) | Google's Gemini API | secret redaction before sending; nothing is sent without `GEMINI_API_KEY`; data use depends on the Google plan (`docs/AI_REVIEW.md` §2) |

Untrusted inputs: everything from users, webhook bodies, repository contents (source files, build files), uploaded CI
reports, GitHub API responses. Repository code is **never executed** by the platform; tests, coverage, mutation testing
and vulnerability scans run in the customer's CI and only their reports are uploaded.

## Controls
| # | Threat | Control | Evidence |
|---|---|---|---|
| 1 | Cross-tenant data access (IDOR) | every repository/analysis query is scoped by owner; foreign IDs answer 404 (no existence leak) | test `AccessIsolationIntegrationTest` (11 read endpoints, report upload, analysis, indexing, dashboard, lists) |
| 2 | Broken authentication | stateless HS256 JWT (15 min in prod), refresh rotation; forged tokens → 401 | tests `JwtServiceTest`, `AccessIsolationIntegrationTest.authentication` |
| 3 | Privilege escalation | method security; `/api/v1/admin/**` requires ROLE_ADMIN (granted only via `ADMIN_EMAILS`); denials answer 403 | test `AccessIsolationIntegrationTest.adminAndRetention` (found and fixed: denials used to answer 500) |
| 4 | Brute force / abuse | Bucket4j: login/registration 10/min per IP, API 300/min per user, webhooks 600/min per IP; 429 + `Retry-After` | test `loginRateLimit` |
| 5 | Forged webhooks | HMAC-SHA256 over the raw body, constant-time compare; 503 without secret; size limit; JSON only; delivery de-duplication | tests `WebhookSignatureVerifierTest` (GitHub's published vector), `GitHubWebhookControllerTest`; live E2E |
| 6 | SSRF through Git operations | `GitUrlGuard`: https only, allowed hosts (github.com + GHE API host), no credentials in URLs, no loopback/private/link-local/metadata/CGNAT addresses; `file://` only behind `ALLOW_LOCAL_REPOSITORIES` (refused in prod) | test `GitUrlGuardTest` |
| 7 | Path traversal | workspace and snapshot paths normalised and checked against the base directory; commit SHAs validated; Semgrep targets with `..` rejected | tests `WorkspaceManagerTest`, `SemgrepRunnerTest` |
| 8 | XML attacks in uploaded reports | StAX with DTDs and external entities disabled | test `ReportParsersTest` (XXE payload), `BuildDependencyDiffTest.xxe` |
| 9 | Secrets in logs | `%maskedMsg`/`%maskedEx` mask GitHub tokens, JWTs, Google API keys, Bearer/Basic values, `password=`-style pairs, URL credentials and PEM keys in messages **and stack traces**; SQL bind values never logged | test `SecretRedactorTest`; live: a token-like ref appeared 0 times in the prod log |
| 10 | Insecure deployment defaults | `ProductionSafetyCheck` (before any bean starts): prod refuses unset or published JWT secret, encryption key, DB/RabbitMQ credentials, missing `FRONTEND_URL`, App without webhook secret, local repositories | test `ProductionSafetyCheckTest`; live: both refusal cases and a clean prod start |
| 11 | Information disclosure | prod: no error messages/stack traces in responses, Swagger UI and API docs off, health without details | live (404 for `/swagger-ui.html`, `/api-docs`) |
| 12 | Token leakage to GitHub logs/UI | Check Run errors are scrubbed of tokens; installation tokens scoped to one repository with `checks:write` + `contents:read`, cached ≤ 55 min | tests `CheckRunPublisherTest`, `GitHubAppAuthServiceTest` |
| 13 | Untrusted analysis tooling | Semgrep runs offline (`--metrics=off`), without a shell, with a timeout, on an exported copy of the files | test `SemgrepRunnerTest` |
| 14 | Data retention | daily job: analyses 180 days, webhook deliveries 30 days, reports 90 days (latest per type kept), workspaces of deleted repositories, stale snapshots; configurable | test `adminAndRetention` |
| 15 | Vulnerable dependencies / images | images scanned with Trivy before release (command in `docs/DEPLOYMENT.md`); fixed versions pinned (Guava, RabbitMQ client, Semgrep venv packages), current nginx base, pip removed from the runtime image | live scan 2026-11-19: 0 fixable HIGH/CRITICAL in both images (was 70 + 10); re-scan 2026-11-20 after the Debian base change: zlib1g patched by the build's security updates, one accepted, documented and expiring exception (`docker/trivyignore.yaml`: CVE-2026-115563 in Semgrep's pinned `mcp`, OAuth-client code that is never executed); re-scan 2026-11-23: plexus-utils CVE-2025-67030 (from the Maven model builder) fixed by pinning 3.6.1, libxml2 CVE-2026-86144 fixed by Alpine updates at build time — 0 fixable HIGH/CRITICAL in both images |
| 16 | Operational data exposure | `/actuator/prometheus` only with the scrape token (constant-time compare) or ROLE_ADMIN; every other actuator endpoint except health/info is admin-only (found and fixed: JWTs were not evaluated on `/actuator/**` at all); metric tags carry no ids, names or messages; Prometheus/Grafana bound to 127.0.0.1 in compose | test `ObservabilityIntegrationTest`; live: 401 without token, Prometheus target `up` with the token |
| 17 | Container compromise / exposure | backend runs as uid 10001 without a package manager or build tools; repository code is never executed; only the web port is published (DB, broker, cache, backend internal); build-time CA certificates removed from the image; secrets only from `.env` (compose refuses to start without them) | live acceptance run (`docs/DEPLOYMENT.md`) |
| 18 | XSS / clickjacking in the UI | nginx adds a Content-Security-Policy (self + Google Fonts + GitHub avatars), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`, `Permissions-Policy` | live: UI loads with 0 CSP violations |
| 19 | Prompt injection through repository content; invented AI claims | code is passed to the model as delimited, untrusted data and the system instruction forbids following it; the model gets no tools and can change nothing; output is schema-constrained JSON; every citation and code name is checked deterministically, and unsupported findings are shown as UNVERIFIED; AI output never changes the deterministic report | tests `AiGroundingTest`; evaluation in `docs/AI_REVIEW.md` (an invented method was caught) |
| 20 | Account linking forgery (OAuth CSRF) | "Connect with GitHub": HMAC-signed state bound to the user, 10-minute expiry, single use; code exchanged server-side with the client secret | test `GitHubOAuthServiceTest`; replayed/forged state refused in the end-to-end run (`docs/verification/oauth`) |
| 21 | Library resolution as an attack path (malicious build file, SSRF, code execution) | build files are read, never run (no Maven plugins, no Gradle); only `LIBRARY_REPOSITORY_URL` is contacted — repositories declared in builds are mirrored to it; checksums must match; limits on jar count, size and time; jars are parsed for type information (javassist), never loaded or executed; ArchUnit test: only the Semgrep runner may start a process | `LibraryResolutionTest` (a repository at 127.0.0.1:9 declared in the POM is never contacted), `PlatformArchitectureTest` |
| 22 | Denial of service through user regular expressions (architecture rules) | patterns ≤ 300 characters; matching runs under a deterministic step budget; patterns that blow up on a probe input are refused when saved; a failing rule is skipped, never fails indexing | `ArchitectureRuleTypesTest` (`(.*a){8}b` refused, `(.*.*)*x` stopped) |
| 23 | Source code disclosure / path traversal through the code browser | owner-scoped; only files recorded by the analysis of that commit can be read, by repository path from the Git object database (no filesystem path); 1 MB and text-only limits; read-only | live: `../../../etc/passwd`, `pom.xml`, unanalysed commits and other users refused (404/400) |

## Residual risks and recommendations
- Rate limits, the repository lock and caches are per process; run one backend instance per workspace volume or move them to Redis/PostgreSQL before scaling horizontally.
- JWTs are bearer tokens stored by the SPA in `localStorage`; the bundled nginx sets a strict Content-Security-Policy — keep it if you replace the frontend container, and terminate TLS in front of it.
- Key rotation for `TOKEN_ENCRYPTION_KEY` needs a re-encryption job (not implemented).
- Semgrep rules are a small curated set; they are analysis aids, not a guarantee of security.
- The GitHub App live round trip (real GitHub) must be validated by the owner after registering the App.
- AI review output can be wrong even when verified (grounded ≠ correct); it is labelled as language-model output everywhere it appears.
