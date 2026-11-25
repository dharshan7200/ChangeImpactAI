# Demo script (about 20 minutes)

The steps use public repositories and commits on which every feature was verified. The expected results below are
the ones observed in those runs. Repositories keep evolving, but the commits named here are immutable, so analyses
between them give the same results.

## Before the demo

1. **Start the platform.**
   * Production-style: `docs/DEPLOYMENT.md` (`./scripts/generate-env.sh`, then
     `docker compose -f docker-compose.full.yml up -d --build`).
   * All languages are analysed by default (`ANALYSIS_LANGUAGES=all`).
   * For local development, see `IMPLEMENTATION_NOTES.md` → "Local Environment Setup".
2. **Open `FRONTEND_URL` and register.** Use an `ADMIN_EMAILS` address to also see admin features.
3. **Pre-index the larger repositories (optional).** First clones take time: commons-lang has 10k commits, guava has
   7.6k.

## 1. Connect and index a repository (2 min)

**Repositories** → connect `spring-projects/spring-petclinic` → **Start Indexing**.

* Stages: clone → source discovery → history → parsing → graph → architecture rules.
* About 50 files and 320 symbols, parsed in a few seconds.
* The repository page shows the indexed commit, symbols and dependency edges, coupling hotspots, and architecture
  violations.

## 2. Explore the dependency graph (3 min)

Open the **Interactive Dependency Graph** tab.

* Switch **Packages → Classes → Methods & fields**.
* Search `OwnerController`, then select it. What it *uses* and what *uses it* are highlighted; everything else fades.
* **Isolate neighbourhood** re-lays out only the connected part.
* Edge filters (extends, calls, creates, field access, types, imports) show which relationship each line is. Test
  code can be hidden.

Message: every edge is a resolved reference from the source code, with its line. Nothing is inferred.

## 3. Analyse a change (5 min)

**Change Impact** → base `818c413`, head `500158f` → **Analyse**.

Observed result: **HIGH 58.5**:

* 7 changed files and 7 changed symbols, 2 of them contract-breaking (`OwnerController#processFindForm`,
  `VetController#showVetList`);
* 9 impacted symbols;
* 22 test methods reach the change.

Walk through:

* **Risk card and risk distribution.** Click a symbol to show its factor breakdown: raw value, weight, contribution.
  Say it plainly: a heuristic, not a probability.
* **Pre-merge checklist.** Review the breaking changes, run the 22 tests, add tests for the 4 untested symbols, ask
  the suggested reviewers. The same checklist appears in a pull request's Check Run.
* **Suggested reviewers.** Each name comes with evidence: the files, their share of each file's history, and the
  last change. The authors of the change are excluded.
* **Blast radius sunburst.** Centre = the change, each ring = one dependency hop. Hover to see a path, click to zoom.
* **Impact list → dependency path** of one finding, then **Markdown report** (the same content as the Check Run).

## 4. Architecture digital twin (3 min)

Use `spring-petclinic/spring-petclinic-rest`, which has the most instructive history.

1. **Architecture & Hotspots → Trends.** Run the history backfill; it is a background job with progress. In the
   recorded run, two backfills (6 and 8 commits) gave 12 points from 2016 to 2026.
2. **Trends.** A 4-package cycle appears at `9545f8c` (2017) and disappears at `de7dbb3` (2021).
3. **Compare** `72fa510` → `9545f8c`. The findings name the new `model → rest` dependency and the new cycle. The
   sources confirm it: `Owner`, `Pet` and `Visit` import serializers from `rest`.
4. **Code city / Hotspots / Dependency matrix / Main sequence.** On commons-lang, `StringUtils.java` is the top
   hotspot (16 changes by 6 authors, matching `git log`).

## 5. Test and quality data (optional, 2 min)

This needs reports produced by the project's own build. The platform never runs repository code.

* Run petclinic's tests with JaCoCo (and optionally PIT) at commit `500158f`.
* Upload the reports: **Quality Reports** tab, or `POST /api/v1/repositories/{id}/reports`.
* Re-run the analysis `9a5d50c → 500158f`. Observed:
  * 45 tests selected;
  * changed-line coverage 37/41;
  * `PetController#updatePetDetails` with a 14.3 % mutation score, a real test gap.

## 6. Historical defect model (2 min)

**History → Defect prediction from history → Train from history.**

* **On spring-petclinic** the result is *Not used — too few labelled bugs* (25 training / 5 test). This is the
  honest outcome for a small history.
* **On apache/commons-lang** (1–2 minutes) the result is *Accepted*:
  * ROC-AUC 0.773 against a churn baseline of 0.729;
  * calibration diagram close to the diagonal;
  * coefficients: lines added and earlier authors raise the estimate.

Then open an analysis of recent commons-lang commits. The **History-based defect estimate (experimental)** card
lists each commit's estimate and what raised it.

## 7. Python (optional, 2 min)

Python is analysed by default.

* Connect `psf/requests`. It is labelled **Python**: 744 symbols (modules, classes, methods, functions), parsed in
  about 4 s.
* In the graph, open `Session`. Its calls to `prepare_request`, `send` and `merge_environment_settings` are real
  edges.
* Analyse the last ~40 commits.
  * `Session.get(url, **kwargs)` → `(url, params=None, **kwargs)` is reported as *Optional parameters added*, not
    breaking.
  * Changes that only touch type hints are reported as *Parameter type hints changed*.

## 8. Operations (1 min)

* **Monitoring.** `--profile monitoring` → Grafana `http://127.0.0.1:3001` → *ChangeImpact AI — Overview*: jobs,
  stage durations, queue depth, parse throughput.
* **Backups.** `./scripts/backup.sh` / `./scripts/restore.sh`.

## 9. GitHub App (if registered)

With a GitHub App configured (`IMPLEMENTATION_NOTES.md` → Phase 5):

* opening a pull request triggers an analysis of `merge-base..head`;
* a Check Run moves through queued → in progress → completed, with the checklist, the risk summary and annotations;
* it fails for introduced vulnerabilities (Semgrep in changed lines, CVEs in changed dependencies) or at the
  configured risk level.
