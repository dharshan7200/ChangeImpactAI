# Evaluation methodology and results

What ChangeImpact AI claims, how each claim was checked, and what the checks found. Every number below comes from
a run in this project's environment: a 4-vCPU container, with PostgreSQL, RabbitMQ and Redis in Docker on the same
host. Each one is recorded in the linked document.

## Principles

1. **Deterministic results are checked against ground truth**: javac's overload choice, `git log`, the source code,
   or a hand-written expected change set.
2. **Heuristics are labelled as heuristics.**
   * The risk score is a weighted sum of explained factors, not a probability.
   * Reviewer suggestions and hotspots are rankings with their evidence shown.
3. **The one statistical model is evaluated statistically.**
   * Time-ordered evaluation, baselines, calibration and a leakage check.
   * Used only if it passes a predefined gate.
4. **Failures and corrections are recorded.** Each "found and fixed" entry in the notes is a case where a check
   disagreed with the platform; the check led to the fix.

## 1. Code model: parsing and dependency resolution

| Claim | Method | Result |
|---|---|---|
| Overloads resolve as the Java compiler resolves them | `OverloadResolutionTest`: 22 calls, javac's choice as ground truth, each resolved with candidates in 40 shuffled orders | All match. Upstream JavaParser fails this test. |
| Analysis is deterministic | commons-lang `1af74dd`, full parse in two separate JVMs, every column of every dependency row compared | 0 of 128,054 rows differ (113 differed before the fixes) |
| Resolution quality improved by the JavaParser patches | Same commit, upstream vs patched | RESOLVED 60,419 → 62,163; 1,315 call sites newly resolved, none lost; 53 calls moved from a wrong overload to the right one |
| Incremental analysis equals a full parse | `IncrementalAnalysisIntegrationTest` (4 commits, every row); commons-lang `2626360..90c16f1` | Identical rows (127,948 / 127,993 dependencies, 15,458 / 15,461 symbols) and report. 224.5 s → 42.8 s. |
| Python dependencies are not invented | `psf/requests`, `Session.request` compared with its source | 5 of 5 repository calls found, 0 false links (`docs/MULTI_LANGUAGE.md`) |

Unresolved references are counted, not hidden. Each dependency carries `RESOLVED`, `PARTIAL`, `EXTERNAL` or
`UNRESOLVED`, and the repository page shows the distribution.

## 2. Change detection, impact and risk

**Semantic change detection.** Two checks:

* `SemanticChangeDetectorTest` asserts the *exact* change set for the shop fixture (v1 → v2), including that
  comment- and formatting-only edits are not reported.
* Pairing of same-named declarations was found to be order-dependent: the same edit produced 8 different outcomes.
  It is now deterministic and tested.

**Impact propagation.** Every finding stores its dependency path from the changed symbol, with the relationship of
each step and its line. Evidence is either `VERIFIED_REFERENCE` (a direct, resolved dependency) or
`TRANSITIVE_PROPAGATION`.

* **Strength:** the product of relationship weights (inheritance 1.0, calls 0.9, field access 0.8, types 0.6,
  annotations 0.4, imports 0.3) and a 0.85 decay per hop.
* **Ranking:** strength orders the findings. It is not a probability.
* **Tests:** `ImpactPropagationServiceTest` and the end-to-end integration tests assert specific paths, for example
  `placeOrder` → `OrderController#create` (DIRECT, VERIFIED_REFERENCE). Removed symbols are traced in the *base*
  commit's graph.

**Risk.** A transparent heuristic of 0–100 points, configurable in `application.yml`:

| Factor | Weight |
|---|---|
| Change severity | 0.25 |
| Direct dependents | 0.15 |
| Transitive dependents | 0.10 |
| Public API exposure | 0.10 |
| Test reachability | 0.10 |
| Changed-line coverage (only with a JaCoCo report of the head commit) | 0.10 |
| Propagation depth | 0.05 |
| Complexity | 0.05 |
| Module criticality | 0.05 |
| Historical churn | 0.05 |
| Architecture violations | 0.05 |

Levels: MEDIUM from 25, HIGH from 50, CRITICAL from 75. Every factor's raw value, normalised value, weight and
contribution are shown.

The risk score is **not validated as a predictor of defects**, and the documentation says so. The historical defect
model (section 5) is the platform's attempt to measure defect likelihood, and it is evaluated separately.

## 3. Test intelligence and security

From `IMPLEMENTATION_NOTES.md`, Phase 5, using real reports.

**spring-petclinic, `9a5d50c → 500158f`**, with JaCoCo, JUnit (77 tests) and PIT (160 mutants) produced at the head
commit:

* 45 tests selected;
* changed-line coverage 37/41;
* the mutation score exposed a real test gap: `PetController#updatePetDetails` at 14.3 %;
* 8 "missing test" items are controller methods tested over MockMvc, which static analysis cannot link; coverage
  shows them executed and lowers their priority.

**Security, on a seeded demo repository:**

* Semgrep flagged an SQL injection and a weak hash in the changed lines;
* the dependency diff joined a downgrade to CVE-2022-42889, using a Dependency-Check fixture because NVD is not
  reachable here;
* the Check Run concluded `failure` with 9 annotations.

**Check Run policy.** One bug was found during these runs: a pull request introducing an SQL injection and a
CRITICAL CVE concluded `success` because its risk was MEDIUM. Introduced vulnerabilities now have their own failure
threshold.

## 4. Architecture twin and developer workflow

From `IMPLEMENTATION_NOTES.md`, P6-5 and the developer-workflow section:

* **Churn.** Hotspot churn for commons-lang `StringUtils.java` (16 changes by 6 authors) equals `git log` over the
  same window. Lines differ, 433 vs 443, because whitespace-only changes are ignored.
* **Cycles.** On spring-petclinic-rest the twin shows a package cycle appearing at `9545f8c` (2017) and disappearing
  at `de7dbb3` (2021). The sources confirm the `model → rest` imports in exactly those commits.
* **Reviewer suggestions.** Ownership shares match `git log`. For example, on commons-lang `MethodUtilsTest.java`
  the other contributor is suggested with a 33 % share. Authors of the change are excluded; that exclusion was
  missing for people committing under several e-mail addresses, found on real data and fixed.

## 5. Historical defect prediction (statistical evaluation)

Full method and results: `docs/DEFECT_MODEL.md`.

**Method:**

* Kamei just-in-time features computed from prior history only;
* SZZ labels;
* time-ordered split (warm-up / train / test / immature), with training labels restricted to fixes known before the
  test period;
* baselines: churn (LA + LD) and the training prior rate;
* metrics: ROC-AUC, Brier score, calibration (ECE), precision/recall/F1;
* a permutation test against leakage, and cross-repository runs.

| Repository | Model AUC | Churn AUC | Brier (prior) | ECE | Permutation AUC | Gate |
|---|---|---|---|---|---|---|
| apache/commons-lang | 0.773 | 0.729 | 0.0534 (0.0566) | 0.015 | 0.516 | accepted |
| google/guava | 0.801 | 0.788 | 0.0563 (0.0598) | 0.028 | 0.516 | not better than churn |
| spring-petclinic | — | — | — | — | 0.590 | too few labels (25 / 5) |
| spring-petclinic-rest | — | — | — | — | 0.528 | too few labels (28 / 28) |

**Conclusion.** There is a real but mostly size-driven signal. The model is well calibrated, modestly better than
"larger is riskier" on one of four repositories, and useful only as an experimental estimate next to the evidence.

## 6. Performance

From `IMPLEMENTATION_NOTES.md`, P6-2. Indexing time excludes the first clone.

| Repository | Java files | Symbols | Dependencies | Indexing | Peak RSS |
|---|---|---|---|---|---|
| spring-petclinic-rest | 109 | 805 | 6,909 | 7.1 s | — |
| apache/commons-lang | 646 | 15,429 | 128,054 | 110 s (parse 105 s) | 2.2 GB |
| google/guava | 3,267 | 42,047 | 478,328 | 203 s (parse 189 s) | 3.8 GB |
| psf/requests (Python, experimental) | 37 `.py` | 744 | 1,831 | parse 3.8 s | — |

* A JavaParser hot spot fix cut the commons-lang parse from 255–281 s to 79–83 s on an idle machine.
* Change analyses reuse analysed commits and plan the rest incrementally. For example, a 40-commit range of
  `psf/requests` was analysed in 5.9 s.

## 7. Deployment

The deployment guide was followed end to end on clean volumes (`docs/DEPLOYMENT.md`, "Acceptance run"):

* secrets generated;
* images built and scanned;
* full stack healthy;
* register → connect → index → analyse a public repository through the published port;
* UI checked in Chromium;
* monitoring;
* backup → delete → restore;
* restart with data intact.

The run found one start-up bug: a blank Gemini key prevented the backend from starting. It was fixed.

## Threats to validity

* **Small samples.** Real-repository checks cover a handful of Java projects and one Python project. Spot checks are
  evidence of correctness for the cases checked, not accuracy rates.
* **Heuristic labels.** Bug-fix detection uses commit messages: for the defect model and the "bug fix" history
  flags.
* **One environment.** The measurements were taken in one container. Times vary with load: commons-lang parsed in
  79–125 s.
* **Simulated GitHub.** The GitHub App flow was verified against a faithful fake API, not a live App registration.
