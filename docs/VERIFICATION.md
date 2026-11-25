# Feature verification

## Verification — 2026-11-23 (all languages on by default)

Production Docker stack, images rebuilt from this commit, fresh volumes, `ANALYSIS_LANGUAGES` unset (default `all`),
through the published web port only.

| Step | Result |
|---|---|
| Backend tests (`mvn test`) | 217 / 217 passed; tests added afterwards for the fixes below pass on their own |
| Parser fixture tests, one project per language | all pass ([MULTI_LANGUAGE.md](MULTI_LANGUAGE.md)) |
| Real repositories parsed and indexed through the platform (6) | all indexed and labelled correctly; spot-checked links match the source; 3 parser bugs found and fixed |
| tree-sitter grammars in the Debian runtime image | all load (no "not available" warnings) |
| API / feature checks (`verification/verify.py`, now with 12 language checks) | **141 / 144**: every language check passed; the 3 failures are explained below |
| Graph node clicks; TSX in the code browser (Chromium) | clicks with 2–5 px of hand movement now select (they started a drag before); hover and Ctrl-click on `<Button` → `button.tsx:46`; 0 console errors |
| Image scan (Trivy 0.76.2, fixable HIGH/CRITICAL, `docker/trivyignore.yaml`) | backend 0, frontend 0 |

**The 3 failed checks:**

* **PageRank ranked `petclinic.css` first** (a real regression: stylesheets, templates and tables had joined the
  centrality ranking). Fixed: centrality ranks program types only; covered by
  `CentralityServiceTest.pagesStylesheetsAndTablesAreNotRanked`. Not re-run on the stack.
* **Rule restore check:** the script picked the first global rule of the listing, whose order is not fixed; this time
  that rule had 0 violations, so it could not appear in the result. Script fixed (it picks a rule with violations).
* **`db:owners` check:** the script expected 4 schema copies; petclinic has 3 (h2, mysql, postgres), all found, and
  `Owner` uses the table. Script fixed.

Raw results: `verification/api-results-2026-11-23-languages.json`.

## Release verification — 2026-11-23 (after library resolution, centrality, rule management, code browser)

**Setup:**
* Production Docker stack, images rebuilt from the final commit, fresh volumes, monitoring on.
* Real public repositories and the real Gemini API.
* Checked only through the published web port.

| Suite | Result |
|---|---|
| API / feature checks (`verification/verify.py`) | **132 / 132 passed** |
| Browser sweep, every page and tab at 1440 px and 390 px, incl. Code and Centrality (`verification/ui-sweep.js`) | **68 / 68 passed** (no console or CSP errors, no failed API calls, no horizontal overflow) |
| Code browser under the production CSP | Monaco renders; hover shows the resolved target; Ctrl-click jumps from `OwnerController.java:145` to `OwnerRepository.java:45`; 0 errors |
| Operations: monitoring, backup → delete → restore, full down/up | all passed: 2 targets up, 17 metric families, dashboard provisioned; restore brought back analyses, AI reviews, library records and rules; the jar cache persists in the `libraries` volume |
| Image scan (Trivy 0.76.2, fixable HIGH/CRITICAL) | **backend 0, frontend 0**, after the fix below |
| Automated tests (`mvn test`, `tsc`, `npm run build`) | **200 / 200 passed**; type check and build OK; `npm audit --omit=dev`: 0 vulnerabilities |

**Results per area** (`verification/api-results-2026-11-23.json`):

| Area | Passed | Area | Passed |
|---|---|---|---|
| Web tier | 4/4 | Python (experimental) | 5/5 |
| Auth | 9/9 | Quality reports | 7/7 |
| Repositories | 7/7 | Architecture twin | 6/6 |
| Indexing | 9/9 | GitHub webhooks | 8/8 |
| Code intelligence | 12/12 | Dashboard | 1/1 |
| History | 2/2 | Defect model (AUC 0.772 vs churn 0.725) | 5/5 |
| Change analysis | 17/17 | AI review | 6/6 |
| **Libraries** | 4/4 | Code search (index built; quota available) | 7/7 |
| **Centrality** | 3/3 | Assistant | 2/2 |
| **Architecture rules** | 8/8 | Security / isolation | 6/6 |
| **Code browser** | 4/4 | | |

**What the new checks show:**
* **Libraries.** petclinic resolved 171 Maven jars inside the container. Its unresolved references fell from 897 to
  6, while the Python-only repository reports `NO_BUILD_FILE`.
* **Risk.** The petclinic analysis risk stayed HIGH 58.5, and a second analysis gives the same result.
* **Centrality.** PageRank puts `BaseEntity` first; betweenness is normalised and ordered.
* **Architecture rules.** A preview of inverted layers found 16 upward dependencies, and a catastrophic regex was
  refused. Switching the global rule off and back on, then re-evaluating, behaved as expected. Another user got 404
  and a non-admin got 403.
* **Code browser.** Line 145 resolves to `OwnerRepository.java:45`. Traversal paths and unanalysed files were
  refused, and other users got 404.

**Found and fixed in this run:** the Maven model builder pulled in `plexus-utils` 3.6.0, which has CVE-2025-67030
(HIGH). It is now pinned to 3.6.1; the library tests pass and the rebuilt image scans clean.

## Final re-verification of all phases — 2026-11-23 (before the additions above)

**Setup:**
* Phases 1–6, including Phase 4 (AI review, code search, assistant) and GitHub OAuth.
* Run again on the **production Docker stack** with images rebuilt from the final commit.
* Fresh volumes, real public repositories, real Gemini API. Monitoring profile on.
* Checked only through the published web port.

| Suite | Result |
|---|---|
| API / feature checks (`verification/verify.py`, now with 11 Phase 4 checks) | **108 / 109 passed**. The one failure was an outdated check that still expected the pre-Phase-4 wording ("AI review not available"). The report correctly says `ON_REQUEST`; the check is fixed |
| Browser sweep, every page and tab at 1440 px and 390 px, incl. the AI Assistant tab and the AI review card (`verification/ui-sweep.js`) | **62 / 62 passed** (no console errors, no failed API calls, no horizontal overflow) |
| Operations: monitoring, backup → delete → restore, full down/up | **all passed** (details below) |
| Image scan (Trivy 0.76.2, fixable HIGH/CRITICAL) | **backend 0, frontend 0**, after the frontend fix below |
| Automated tests (`mvn test`, `tsc`, `npm run build`) | **180 / 180 passed**, type check and build OK |

**Results per area** (`verification/api-results-2026-11-23.json`):

| Area | Passed | Area | Passed |
|---|---|---|---|
| Web tier | 4/4 | Quality reports | 7/7 |
| Auth | 9/9 | Architecture twin | 6/6 |
| Repositories | 7/7 | GitHub webhooks | 8/8 |
| Indexing | 9/9 | Dashboard | 1/1 |
| Code intelligence | 12/12 | Defect model (commons-lang accepted, AUC 0.772 vs churn 0.725) | 5/5 |
| History | 2/2 | AI review | 6/6 |
| Change analysis | 16/17 (see above) | Code search | 4/4 |
| Python (experimental) | 5/5 | Assistant | 1/1 |
| Security / isolation | 6/6 | | |

**Phase 4 in production:**
* The AI review completed on `gemini-3.6-flash`; citations exist in the evidence; the Markdown export carries the
  labelled review.
* Before the index exists, search and assistant answer `RAG_NOT_INDEXED`.
* Another user gets 404 on the review and on the index status.
* The search-index build failed with the clear message *"The daily Gemini quota for embedding requests is used up
  (retry in 4h29m)"*. The free tier's 1,000 embeddings per day were spent by the previous day's evaluation, so this
  is the documented fallback path, not a defect.

**Operations:**
* Prometheus targets are up; there are 17 `changeimpact_*` metric families; the Grafana dashboard is provisioned.
  The metrics endpoint answers 401 without the token.
* Backup → delete analyses, AI reviews and defect models → restore: everything came back (3 analyses, 4,584
  symbols, 2 AI reviews, 2 defect models).
* After a full `down`/`up`: login works, repositories, analyses and AI reviews are intact.

### Found and fixed during this run

| Problem | Fix |
|---|---|
| **AI review validator false alarm**: a review's 125-character method name in backticks exceeded the validator's 120-character span limit. The backticks were then paired wrongly, prose (", adding", "redirects out-of-bounds") was checked as code, and 2 correct findings were marked UNVERIFIED | Spans are paired in order with no length limit (`ReviewValidator`); regression test with that exact text (it fails on the old code). The same review re-run in production: 3/3 verified |
| **Frontend image: CVE-2026-86144 (libxml2, HIGH)**, published after the 2026-11-19 scan; the newest `nginx:1.30-alpine` still ships the vulnerable `2.13.9-r2` | The frontend image now applies Alpine security updates at build time (`APPLY_OS_UPDATES`, like the backend), which brings libxml2 `2.13.9-r3`, OpenSSL `3.5.9-r1` and zlib `1.4`. Needs `dl-cdn.alpinelinux.org` during the build (documented) |
| `restore.sh --yes <file>` was rejected; only `<file> --yes` worked, although the acceptance notes write it the other way | `--yes` is accepted in either position |

**Not verifiable here:**
* The held-out vector-search evaluation (`AI_REVIEW.md` §3) waited for the daily embedding quota; it was run later
  the same day (results in `AI_REVIEW.md` §3).
* The live GitHub App round trip and the real github.com OAuth consent screen need your registrations
  (*Not covered here*, below).

## Verification run — 2026-11-21

Every user-facing feature of ChangeImpact AI was exercised on the **production Docker stack**
(`docker-compose.full.yml`, `prod` profile, images built from this commit, fresh volumes, `ANALYSIS_LANGUAGES=java,python`,
monitoring profile), through the published web port only — the way a user or a CI system reaches it — with real
public repositories (spring-petclinic, spring-petclinic-rest, psf/requests, apache/commons-lang).

| Suite | Result |
|---|---|
| API / feature checks (`verification/verify.py`) | **98 / 98 passed** |
| Browser sweep, every page and tab at 1440 px and 390 px (`verification/ui-sweep.js`) | **58 / 58 passed** (no console errors, no failed API calls, no horizontal overflow) |
| Operations: monitoring, backup → delete → restore, restart persistence | **all passed** (below) |
| Automated test suite (`mvn test`, `npm run build`) | **163 / 163 passed**, frontend build OK (`docs/TESTING.md`) |

Raw results: `verification/api-results-2026-11-21.json`, `verification/ui-results-2026-11-21.json`. Re-run: start the stack
(`docs/DEPLOYMENT.md`), then `python3 docs/verification/verify.py http://localhost:8088 <GITHUB_WEBHOOK_SECRET>
backend/src/test/resources/reports results.json` (needs `ADMIN_EMAILS=admin@example.com` and a webhook secret in `.env`;
it registers `admin@example.com` and `other@example.com`, so use an empty database).

## Found and fixed during this verification

| Problem | Fix |
|---|---|
| Error responses never contained the documented error codes (`SAME_COMMIT`, `JOB_ALREADY_RUNNING`, `INVALID_REPORT`, …): the handler logged the code and sent only the message, so clients could only match on text | `code` field in every error response (`GlobalExceptionHandler`, `ApiResponse`), documented; `GlobalExceptionHandlerTest` |
| `GET /repositories/{id}/defect-model` answered 404 before the first training — handled by the UI but logged as a console error on every repository's History tab | returns `{"status": "NOT_TRAINED"}`; 404 only for other users' repositories |
| (environment) Docker Hub rate-limited the anonymous pull of `nginx:1.30-alpine` (HTTP 429) | built from the locally cached `nginx:stable-alpine` (= 1.30.7) tagged as `1.30-alpine`; no code change |

Not product defects, corrected in the verification script itself: two checks looked for an error code that was not sent
(the first fix above), and one looked up a symbol in the first page of a 579-result search.

## API and feature checks


### Web tier (4/4)

| Check | Result | Evidence |
|---|---|---|
| nginx health endpoint | PASS | 200 b'ok' |
| security headers on the SPA | PASS | {'x-frame-options': 'DENY', 'x-content-type-options': 'nosniff', 'referrer-policy': 'strict-origin-when-cross-origin'} |
| SPA deep link served (no-cache) | PASS | 200 no-cache |
| actuator not exposed through the web port | PASS | 200 returns SPA page |

### Auth (9/9)

| Check | Result | Evidence |
|---|---|---|
| register (admin e-mail) | PASS | 201 |
| register second user | PASS | 201 |
| duplicate e-mail refused | PASS | 400 {'code': 'DUPLICATE_EMAIL', 'message': 'An account with this email already exists', 'success': False, 'timestamp': '2026-11-21T18:15:29.455794090Z'} |
| weak password refused | PASS | 400 |
| login returns access + refresh token | PASS | 200 |
| wrong password → 401 | PASS | 401 |
| refresh token rotation | PASS | 200 |
| API without token → 401 | PASS | 401 |
| forged JWT → 401 | PASS | 401 |

### Repositories (7/7)

| Check | Result | Evidence |
|---|---|---|
| connect public repository (Java) | PASS | 201 |
| connect public repository (Python) | PASS | 201 |
| connect third repository | PASS | 201 |
| connect large repository (commons-lang) | PASS | 201 |
| non-existent repository refused | PASS | 400 REPOSITORY_NOT_FOUND: GitHub repository this-owner-does-not-exist-9x/nothing-here was not found or is private; check the name or connect a GitHub account that can read it |
| non-GitHub URL refused (input validation) | PASS | 400 VALIDATION_FAILED |
| default branch read from the remote | PASS | main |

### Defect model (5/5)

| Check | Result | Evidence |
|---|---|---|
| training job queued (commons-lang) | PASS | 202 |
| small history → not used (too few labels) | PASS | INSUFFICIENT_LABELS: Only 25 bug-introducing commits in training and 5 in test (need 30 and 10) |
| commons-lang → accepted, beats churn baseline | PASS | COMPLETED ACCEPTED: Test ROC-AUC 0.772 (churn baseline 0.725) on 815 later commits |
| calibration and baselines reported | PASS | AUC 0.7715938722294654 Brier 0.052257600060271116 ECE 0.014004086127369688 |
| recent commits scored with drivers | PASS | 300 commits |

### Indexing (9/9)

| Check | Result | Evidence |
|---|---|---|
| index spring-petclinic | PASS | INDEXED in 12s, commit 500158f |
| spring-petclinic labelled Java from its code | PASS | Java |
| index psf/requests | PASS | INDEXED in 8s, commit 3c7baaa |
| psf/requests labelled Python from its code | PASS | Python |
| index spring-petclinic-rest | PASS | INDEXED in 8s, commit 0515ec8 |
| spring-petclinic-rest labelled Java from its code | PASS | Java |
| second concurrent index request refused (code JOB_ALREADY_RUNNING) | PASS | 202 then 400 JOB_ALREADY_RUNNING |
| job history with stages | PASS | 2 jobs, latest stage COMPLETED |
| re-index of an analysed commit reuses symbols | PASS | COMPLETED Indexing completed successfully |

### Code intelligence (12/12)

| Check | Result | Evidence |
|---|---|---|
| symbol search | PASS | 2 result(s) |
| symbol details with source location | PASS | org.springframework.samples.petclinic.owner.OwnerController line 48 |
| forward dependencies | PASS | 10 |
| reverse dependents (blast radius) of Owner | PASS | 41 |
| dependency graph, PACKAGE view | PASS | 6 nodes, 8 edges |
| dependency graph, CLASS view | PASS | 50 nodes, 116 edges |
| dependency graph, MEMBER view | PASS | 323 nodes, 641 edges |
| graph statistics incl. resolution coverage | PASS | {'commitSha': '500158f732419217507c7656904b8e6aa1bcc0d6', 'graph': {'cycleGroups': 0, 'isolatedTypes': 9, 'memberEdges': 641, 'memberEdgesByRelationship': {'CONSTRUCTOR_CALL': 41, 'EXTENDS': 8, 'FIELD_ACCESS': 250, 'FIEL |
| coupling / instability metrics | PASS | list |
| architecture violations endpoint | PASS | 200 |
| unresolved references listed (not hidden) | PASS | 200 total 897 |
| shortest dependency path OwnerController → Owner | PASS | {'edges': [{'id': 'f05b2edd-d036-3281-8fdb-89fa5cae432d', 'dependencyType': 'RETURN_TYPE', 'sourceLine': 65, 'external': False, 'occurrences': 7, 'types': ['CON |

### History (2/2)

| Check | Result | Evidence |
|---|---|---|
| commit history with change stats | PASS | 50 commits |
| weekly activity and most-changed files | PASS | ['weeks', 'scannedCommits', 'commitsInWindow', 'bugfixCommitsInWindow', 'revertCommitsInWindow'] |

### Change analysis (17/17)

| Check | Result | Evidence |
|---|---|---|
| analysis 818c413 → 500158f completes | PASS | COMPLETED risk HIGH 58.5 |
| deterministic: same risk as the earlier acceptance run (HIGH 58.5) | PASS | HIGH 58.5 |
| semantic changes incl. 2 breaking | PASS | ['OwnerController#processFindForm(int,Owner,BindingResult,Model,RedirectAttributes)', 'VetController#showVetList(int,Model,RedirectAttributes)'] |
| impact findings with dependency paths and evidence | PASS | 27 findings, e.g. OwnerController#processFindForm(int,Owner,BindingResult,Model,RedirectAttributes) via 2 steps |
| explainable risk: factors with weight and contribution | PASS | ['score', 'level', 'disclaimer', 'overall', 'symbols'] |
| report: pre-merge checklist | PASS | ['REVIEW', 'TESTS', 'TESTS', 'REVIEW'] |
| report: suggested reviewers (authors excluded) | PASS | ['Patrick Baumgartner', 'YiXuan Ding', 'Dave Syer'] |
| report: architecture drift | PASS | ['No structural drift: same packages, dependencies and cycles'] |
| report: defect-model status present | PASS | NOT_TRAINED |
| report: AI review marked not available (deterministic) | PASS | {'detail': 'LLM-assisted review (Phase 4) is not enabled; every finding in this report is deterministic.', 'status': 'NOT_AVAILABLE'} |
| Markdown report | PASS | 8953 bytes, text/markdown;charset=UTF-8 |
| test selection | PASS | {'DIRECT': 5, 'INDIRECT': 17, 'SCENARIO': 2, 'MISSING_TEST': 4} |
| security stage with Semgrep run | PASS | {'status': 'COMPLETED', 'findings': 0, 'filesScanned': 4} |
| analyses list (timeline) | PASS | 1 |
| same commit refused (code SAME_COMMIT) | PASS | 400 SAME_COMMIT: Base and head must be different commits |
| unsafe ref refused | PASS | 400 |
| unknown commit → readable failure | PASS | FAILED Commit or ref not found in repository: deadbeefdeadbeef |

### Python (experimental) (5/5)

| Check | Result | Evidence |
|---|---|---|
| change analysis of psf/requests completes | PASS | COMPLETED MEDIUM 45.3  |
| Session#get: optional parameter added, not breaking | PASS | ['PARAMETERS_EXTENDED'] |
| Session#request: type hints only | PASS | ['PARAMETER_ANNOTATIONS_CHANGED'] |
| Python dependency graph | PASS | 109 nodes, 311 edges |
| Session.request calls resolved (prepare_request, send) | PASS | [{'endLine': 375, 'id': '0ed0085d-5d72-4601-9098-12586b78bee1', 'ownerTypeId': '0ed0085d-5d72-4601-9098-12586b78bee1', 'packageName': 'requests', 'qua |

### Quality reports (7/7)

| Check | Result | Evidence |
|---|---|---|
| upload JUNIT | PASS | 201 {"tests": 4, "files": 1, "byStatus": {"PASSED": 4}} |
| upload JACOCO | PASS | 201 {"sourceFiles": 1, "methods": 12, "lineCoverage": 69.0, "branchCoverage": 56.3, "linesCovered": 2784, "linesMissed": 125 |
| upload PIT | PASS | 201 {"mutants": 25, "byStatus": {"KILLED": 16, "NO_COVERAGE": 1, "SURVIVED": 8}, "mutationScore": 64.0} |
| upload DEPENDENCY_CHECK | PASS | 201 {"vulnerabilities": 1, "vulnerableDependencies": 1, "bySeverity": {"CRITICAL": 1}} |
| XXE payload rejected (code INVALID_REPORT, entity not expanded) | PASS | 400 INVALID_REPORT: Could not parse JaCoCo report: ParseError at [row,col]:[3,20] Message: The entity "xxe" wa |
| unknown commit rejected | PASS | 400 |
| reports listed | PASS | 4 |

### Architecture twin (6/6)

| Check | Result | Evidence |
|---|---|---|
| snapshot (packages, coupling, cycles) | PASS | 17 packages, 19 edges, 0 cycles |
| hotspots (churn × complexity) | PASS | 87 files |
| history backfill job | PASS | COMPLETED Architecture history of 4 commits ready |
| trend over analysed commits | PASS | 4 points |
| compare oldest → newest commit | PASS | ['8 new package dependencies, e.g. org.springframework.samples.petclinic.mapper → org.springframework.samples.petclinic.model', '2 new architecture rule violation(s)'] |
| unknown commit → 404 | PASS | 404 |

### GitHub webhooks (8/8)

| Check | Result | Evidence |
|---|---|---|
| unsigned delivery → 401 | PASS | 401 |
| wrong signature → 401 | PASS | 401 |
| signed ping accepted | PASS | 202 {'status': 'ACCEPTED', 'detail': 'pong (hook 1)', 'analysisIds': []} |
| redelivery de-duplicated | PASS | 200 {'detail': 'Delivery 2d3856a1-bedd-47ad-b158-1ca7404c2baf was already received', 'status': 'DUPLICATE'} |
| form-encoded delivery → 415 | PASS | 415 |
| App status page (webhook secret configured, App not registered) | PASS | {"appConfigured": false, "webhookSecretConfigured": true, "checksEnabled": true, "checkName": "ChangeImpact AI", "failOn": "CRITICAL", "analyzeDraftPullRequests |
| delivery log | PASS | 200 |
| pull-request list | PASS | 200 |

### Dashboard (1/1)

| Check | Result | Evidence |
|---|---|---|
| team dashboard | PASS | {'repositories': 4, 'indexedRepositories': 3, 'analyses': 3, 'distinctChanges': 2, 'failedAnalyses': 1, 'highRiskChanges': 1, 'openPullRequests': 0, 'openHighRiskPullRequests': 0, 'securityFindingsHighOrCritical': 2} |

### Security (6/6)

| Check | Result | Evidence |
|---|---|---|
| other user cannot see the repository (404) | PASS | 404 |
| other user cannot see the analysis (404) | PASS | 404 |
| other user cannot see the defect model (404) | PASS | 404 |
| non-admin → 403 on admin operation | PASS | 403 |
| admin can run retention | PASS | 200 {"analyses": 0, "webhookDeliveries": 0, "qualityReports": 0, "deletedRepositoryWorkspaces": 0, "staleSnapshots": 0} |
| login rate limit → 429 | PASS | [401, 401, 401, 401, 401, 401, 401, 401, 401, 401, 429, 429, 429, 429] |

## Browser sweep

Chromium (Playwright); each page loaded after login, tab clicked, network idle; checked: no console error, no API
response ≥ 500, document not wider than the viewport.

| Page | 1440 px | 390 px |
|---|---|---|
| Login | PASS | PASS |
| Overview dashboard | PASS | PASS |
| Repositories | PASS | PASS |
| GitHub page | PASS | PASS |
| Repository petclinic · graph | PASS | PASS |
| Repository petclinic · impact | PASS | PASS |
| Repository petclinic · prs | PASS | PASS |
| Repository petclinic · reports | PASS | PASS |
| Repository petclinic · history | PASS | PASS |
| Repository petclinic · architecture | PASS | PASS |
| Repository petclinic · jobs | PASS | PASS |
| Repository requests (Python) · graph | PASS | PASS |
| Repository requests (Python) · impact | PASS | PASS |
| Repository requests (Python) · prs | PASS | PASS |
| Repository requests (Python) · reports | PASS | PASS |
| Repository requests (Python) · history | PASS | PASS |
| Repository requests (Python) · architecture | PASS | PASS |
| Repository requests (Python) · jobs | PASS | PASS |
| Architecture twin · Code city | PASS | PASS |
| Architecture twin · Hotspots | PASS | PASS |
| Architecture twin · Dependency matrix | PASS | PASS |
| Architecture twin · Main sequence | PASS | PASS |
| Architecture twin · Trends | PASS | PASS |
| Architecture twin · Compare | PASS | PASS |
| Architecture twin · Rules & coupling | PASS | PASS |
| Defect model panel (commons-lang) | PASS | PASS |
| Analysis page (Java) | PASS | PASS |
| Analysis page (Python) | PASS | PASS |
| Markdown report download | PASS | PASS |

## Operations

```
## Monitoring
target changeimpact-backend up
target prometheus up
jobs_total {'outcome': 'completed', 'type': 'analysis'} 2
jobs_total {'outcome': 'completed', 'type': 'architecture'} 1
jobs_total {'outcome': 'completed', 'type': 'defect-model'} 2
jobs_total {'outcome': 'completed', 'type': 'indexing'} 4
jobs_total {'outcome': 'discarded', 'type': 'analysis'} 0
jobs_total {'outcome': 'discarded', 'type': 'architecture'} 0
jobs_total {'outcome': 'discarded', 'type': 'defect-model'} 0
jobs_total {'outcome': 'discarded', 'type': 'indexing'} 0
jobs_total {'outcome': 'failed', 'type': 'analysis'} 1
jobs_total {'outcome': 'failed', 'type': 'architecture'} 0
jobs_total {'outcome': 'failed', 'type': 'defect-model'} 0
jobs_total {'outcome': 'failed', 'type': 'indexing'} 0
alert rules loaded 7
grafana dashboards ['ChangeImpact AI — Overview']
prometheus endpoint without token 401 | with scrape token 200
## Backup / restore
before: analyses=3 symbols=4572 defect_models=2
Backup written: /tmp/claude-0/-home-user-ChangeImpactAI/6df2de0b-e596-57d7-9c55-7167a61f7cb7/scratchpad/verify/backups/changeimpact-20261121T182055Z.dump (2.2M)
after delete: analyses=0 defect_models=0
Restored /tmp/claude-0/-home-user-ChangeImpactAI/6df2de0b-e596-57d7-9c55-7167a61f7cb7/scratchpad/verify/backups/changeimpact-20261121T182055Z.dump. The backend is starting (docker compose -f docker-compose.full.yml logs -f backend).
after restore: analyses=3 symbols=4572 defect_models=2
## Restart persistence
after down/up: login ok, 4 repositories: [('apache/commons-lang', 'NOT_INDEXED'), ('psf/requests', 'INDEXED'), ('spring-petclinic/spring-petclinic-rest', 'INDEXED'), ('spring-projects/spring-petclinic', 'INDEXED')]
backend Up 21 seconds (healthy)
frontend Up 4 seconds (health: starting)
grafana Up 6 minutes
postgres Up 32 seconds (healthy)
prometheus Up 6 minutes
rabbitmq Up 32 seconds (healthy)
redis Up 32 seconds (healthy)
changeimpact metric families 14
```

## GitHub OAuth and private repositories (2026-11-22)

"Connect with GitHub" was implemented after the run above (before, only personal access tokens worked although the
docs mentioned OAuth). Verified in Chromium against a fake GitHub (`verification/oauth/fake_github.py`) that plays
the consent page, token endpoint and API and checks every parameter it receives:

| Check | Result |
|---|---|
| "Connect with GitHub" shown only when the OAuth App is configured | PASS |
| consent request: client ID, redirect URI `<FRONTEND_URL>/github/oauth/callback`, scopes `repo read:user user:email`, signed state | PASS |
| code exchanged server-side with the client secret, matching redirect URI, code used once | PASS |
| account connected and listed (`octo-dev`), scopes recorded, token stored AES-GCM encrypted (no plaintext in the database) | PASS |
| private repository listed through the token and connected with `private = true` | PASS |
| replayed callback (same code + state) and forged state → `OAUTH_INVALID_STATE` | PASS |
| user denies consent → GitHub's message shown on the callback page | PASS |
| unit tests: state bound to the user, signed, single-use, expires after 10 minutes (`GitHubOAuthServiceTest`) | PASS |

Not verifiable here: the consent screen of the real github.com and cloning a real private repository — both need
your GitHub OAuth App and account. Cloning uses the same stored-token path as personal access tokens.
Found while testing: the GitHub API client ignored `GITHUB_API_BASE_URL` (hard-coded api.github.com, breaking GitHub
Enterprise) — fixed; and the new service failed to start because of two constructors — fixed before release.

## AI review, code search and assistant (2026-11-22)

Run on the development stack with the real Gemini API. Method, numbers and their limits are in
[AI_REVIEW.md](AI_REVIEW.md); scripts, query sets and raw results are in `verification/ai/`.

| Check | Result |
|---|---|
| Search index built for spring-petclinic (245 chunks) and psf/requests (743 chunks); embeddings reused by content hash | PASS |
| Retrieval on 45 labelled petclinic queries: `auto` Hit@5 1.00 for questions and identifiers; the measurements drove two fixes (V14 name-weighted full-text, `auto` mode) | PASS |
| Held-out retrieval on 40 requests queries | PASS: full-text that day, vector/hybrid/auto once the quota had reset (2026-11-23, `AI_REVIEW.md` §3) |
| Six AI reviews (4 repositories, Java + Python, 9–254 changed symbols), every finding checked against `git diff` | 13/18 correct and specific, 3 generic, 2 partly wrong, 0 invented code (`review-v3`) |
| Validator catches an invented method (`EventUtils.bindEvents`) | PASS |
| Assistant: 3 answerable questions verified and correct, 1 unanswerable → `INSUFFICIENT_EVIDENCE` | PASS |
| Analysis page: AI review card with findings, verification status, citations that open their evidence, model/tokens/latency footer; 0 console errors besides the expected 503 below | PASS |
| AI Assistant tab: index status/build, ask box, history, search inspector with per-retriever ranks and the mode used; no horizontal scroll at 390 px | PASS |
| Daily embedding quota used up → search answers in 0.2 s from full-text with an explanatory note (before: minutes of retries) | PASS |
| Both chat models overloaded → assistant shows "The AI model is unavailable right now…" (`AI_UNAVAILABLE`, 503; before: a generic 500) | PASS |
| `report.md` ends with the AI review, labelled as language-model output with each finding's status | PASS (unit test + export) |

Found and fixed during these runs:
* full-text ranking that missed exact class names;
* fusion that hurt natural-language questions;
* plain-text code names that escaped validation;
* class renames reported as removals in the review evidence;
* a throttle that blocked searches behind an index build;
* minutes of retries on a daily quota;
* a literal flagged as an unknown name;
* AI provider errors surfacing as HTTP 500.

## Not covered here

* **Live GitHub App round trip** (real App registration, public webhook URL, Check Runs on github.com): needs the owner's
  App; webhook verification, de-duplication and the App status page were checked here, the full PR → Check Run flow
  against a fake GitHub API in Phase 5 (`IMPLEMENTATION_NOTES.md`).
* **The primary chat model at scale**: `gemini-3.5-flash` was mostly overloaded on 2026-11-22, so the fallback model
  wrote most evaluated reviews (see [AI_REVIEW.md](AI_REVIEW.md) §4).
* **TLS** (terminated by the operator's reverse proxy; `docs/DEPLOYMENT.md`).
