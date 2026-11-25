# Known limitations

The current, consolidated list as of 2026-11-22. Each item states what the platform does instead, so results can
be read correctly. Details live in the linked documents.

## Not implemented

| Item | Status |
|---|---|
| Multi-agent workflow, assistant tool-calling and multi-turn conversations (`PHASE4_PLAN.md`) | Replaced by a deterministic pipeline with one model call per review or question ([AI_REVIEW.md](AI_REVIEW.md) §6). |
| CI pipeline | Dropped by the owner. Run the tests and the image scan before releases (`docs/TESTING.md`, `docs/DEPLOYMENT.md`). |
| Library resolution limits | Only the build at the repository root is used, and only one Maven repository (Maven Central or a mirror); dependencies published only in private repositories stay unresolved; Gradle builds are read as text, so dependencies declared in code are not seen ([LIBRARY_RESOLUTION.md](LIBRARY_RESOLUTION.md)). |
| ArchUnit on customer repositories | ArchUnit needs compiled bytecode; customer code is never built, so customer rules run on the parsed graph (layered architecture, forbidden dependency, only-accessed-by, cycles). ArchUnit checks the platform's own architecture in its test suite. |
| Code editing in the browser | The code browser is read-only by design: the platform never writes to a repository. |
| Key rotation for `TOKEN_ENCRYPTION_KEY` | Needs a re-encryption job, which is not implemented (`SECURITY.md`). |
| Horizontal scaling | Rate limits, the per-repository lock and graph caches are in-process. Run one backend instance per workspace volume. |

## Static analysis (Java)

* **Generated and runtime code is invisible.** Lombok-generated members, reflection, Spring proxies and dependency
  injection wiring are not seen. Calls into generated members resolve to the type (`PARTIAL`) at best.
* **Local and anonymous classes.** Their members are not stored as symbols, so references from them are skipped.
* **Initialisers.** References from initialiser blocks and field initialisers are attributed to the enclosing type.
* **Generics edge cases.** A few generic calls that combine same-named type variables from different declarations
  stay unresolvable for JavaParser. They are recorded as `PARTIAL`/`UNRESOLVED` (the same way in every run).
* **Same-named declarations.** Declarations sharing a qualified name (constant-specific enum bodies) share one link
  target: the first declaration.
* **HTTP-level tests.** MockMvc/RestAssured tests are not linked to controller methods statically; JaCoCo coverage is
  the evidence for them.
* **Semantic diff:**
  * a signature change across several overloads at once is reported as REMOVED + ADDED (not guessed);
  * when an annotation and the body change together, only the annotation kind is reported.
* **Memory grows with repository size.** guava needed 3.8 GB RSS. `MAX_CONCURRENT_JOBS` bounds parallel parses.

## Languages other than Java

See `docs/MULTI_LANGUAGE.md`. In short:

* **Name and declared-type resolution, no full type inference.** Python, JavaScript/TypeScript, PHP, C#, Go and Ruby
  calls are linked when the receiver's type is certain: imports, `this`/`self`, declared parameter/field/property
  types, locals assigned `new X()` (or the language's equivalent) and declared return types. Calls on values of
  unknown type, dynamic dispatch (`send`, `__call`, `getattr`, reflection), extension methods, LINQ/lambda chains and
  monkey-patching are not linked. The graph under-approximates; it does not guess.
* **Overloads (C#)** share one symbol per name. **Go interfaces** are matched by method names, not signatures.
* **Incremental analysis is Java-only.** The other languages are re-parsed in full on each analysed commit.
* **Templates and styles.** Inline `<script>`/`<style>`, Vue/Svelte single-file components, Blade, Handlebars, Razor,
  SCSS/Less sources and dynamic class names (`className={cond ? 'a' : 'b'}`, `th:classappend`) are not analysed.
* **SQL** is read with a purpose-built statement reader (no published tree-sitter SQL grammar). It understands the
  common DDL of PostgreSQL, MySQL, SQL Server, Oracle, SQLite and H2; vendor-specific syntax beyond that is read only
  for the table names it contains. When several scripts define the same table (one schema per database vendor),
  links go to the first by path.
* **Database links** from code (JPA naming, Rails conventions, EF `[Table]`, SQL in strings) are kept only when the
  repository's SQL defines the table: JPQL/HQL entity names, a non-default naming strategy or tables created only at
  runtime lose those links.
* **Security rules.** Semgrep scans changed and directly impacted files of Java, Python, JavaScript/TypeScript,
  PHP, C#, Go and Ruby with bundled rules (SQL and command injection, eval, unsafe deserialization, weak crypto,
  disabled TLS verification, XSS sinks, hard-coded credentials). The *sensitivity classifier* (which flags changes to
  authentication, authorization and payment code) still recognises Java/Spring annotations and names only.
  Architecture rules (layers, packages) work on every language's qualified names.
* **Accuracy has been spot-checked on real repositories (see MULTI_LANGUAGE.md), not measured systematically.**
* **Platforms.** tree-sitter needs glibc (the Docker image is Debian-based).

## Change analysis inputs

* **Quality data is per commit.** Coverage, test results and mutation data count only for the exact head commit
  they were produced for. History (churn, bug-fix flags) covers up to 300 commits per scan.
* **Semgrep scope.** Semgrep scans changed and directly impacted files with a small curated rule set. It is an
  analysis aid, not a security guarantee.
* **Dependencies.** The Gradle dependency diff understands `group:artifact:version` strings, not version catalogs or
  `platform()` BOMs. CVE data comes from uploaded Dependency-Check reports.

## Heuristics and models

* **Risk score.** A transparent weighted heuristic (0–100 points), not a probability. It has not been validated as a
  defect predictor.
* **Hotspots and reviewer suggestions.** Hotspot thresholds and reviewer recency weighting are documented heuristics.
* **Defect model (experimental).** SZZ labels are noisy: commit-message fix detection, and blame of the last touching
  commit. Right-censoring affects recent commits. Results:
  * accepted on 1 of 4 evaluated repositories;
  * mostly a size signal;
  * low precision and recall at a 5–6 % base rate.

  It is shown only as a labelled estimate, never in the risk score (`docs/DEFECT_MODEL.md`).

## GitHub integration

* **No live round trip yet.** The live round trip needs the owner's GitHub App registration and a public webhook URL.
  The flow was verified against a fake GitHub API that checks signatures, scopes and payload limits.
* **Duplicate repositories.** When several users connect the same repository, only the earliest-connected copy
  publishes Check Runs.

## Operations

* **Data protection.** Backups cover PostgreSQL and `.env`. Git clones are re-created on demand.
* **Access.** The UI must be opened at exactly `FRONTEND_URL` (CORS).
* **Image scanning.** Image scans have one documented exception: CVE-2026-115563 in Semgrep's pinned `mcp`, in
  OAuth-client code that never runs. It expires on 2027-02-28 (`docker/trivyignore.yaml`).

## AI review, code search and assistant

Details and measurements: [AI_REVIEW.md](AI_REVIEW.md).

* **Verified means grounded, not correct.** A verified finding cites real evidence and names only existing code;
  its reasoning can still be wrong (2 of 18 evaluated findings described a real change with a wrong
  consequence). The deterministic
  report is the source of truth; AI output is a review aid.
* **Bounded evidence.** At most 15 changed symbols, 20 impacts, 10 tests, 10 security findings and 5 related chunks
  per review, riskiest first; large changes are reviewed from that subset.
* **Gemini free tier:** 100 embedded texts per minute, 1,000 embedding requests per day. One medium repository index
  (≈ 750 chunks) uses most of a day's quota; afterwards search falls back to full-text (and says so) until the quota
  resets. Use a paid plan for regular use.
* **Search index per commit.** The index covers the repository's last indexed commit; AI reviews of other head
  commits get no related-code evidence (they still get changed/impacted code).
* **Retrieval evaluated on two repositories** (85 labelled queries, one of them held out). Code search chunks
  every analysed language by its symbols; only Java and Python retrieval were evaluated.
* **Code leaves the server** (secret-redacted) when AI features are used; see [AI_REVIEW.md](AI_REVIEW.md) §2.
