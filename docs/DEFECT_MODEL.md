# Historical defect prediction (P6-6, experimental)

**Question.** Can a model trained on a repository's own history estimate which new commits are likely to introduce
a bug — better than the obvious rule "larger changes are riskier"?

**Answer from this evaluation (2026-11-19).** Sometimes, and only modestly. On commons-lang the model beats the churn
rule (ROC-AUC 0.773 vs 0.729) and is well calibrated, so the platform uses it there. On guava it is about as good as
churn (0.801 vs 0.788) and is not used. The two spring-petclinic repositories do not have enough labelled bugs to
evaluate a model at all. The gate below makes these decisions automatically, per repository.

Code: `backend/src/main/java/com/changeimpact/defect/`. Raw results:
[`evaluation/defect-model-2026-11-19.json`](evaluation/defect-model-2026-11-19.json).

## Method

### Data: change-level features from Git history

`JitDatasetBuilder` reads up to 5,000 newest non-merge commits of the default branch. Only commits that change source
code are kept (`.java .kt .scala .groovy .py .js .ts .go .rb .php .c/.h .cpp .cs .swift .rs`). Each commit becomes
one sample with the 14 just-in-time features of Kamei et al. (2013):

| Group | Features |
|---|---|
| Diffusion | NS (top-level directories), ND (directories), NF (files), ENTROPY (spread of the change over its files) |
| Size | LA (lines added), LD (lines deleted), LT (size of the touched files before the change) |
| Purpose | FIX (the commit message says it fixes something) |
| History | NDEV (earlier authors of the files), AGE (days since the files last changed), NUC (earlier commits to the files) |
| Experience | EXP (author's earlier commits), REXP (recency-weighted), SEXP (author's commits in the same subsystems) |

Commits are processed oldest first, and every feature uses only the commit itself and earlier commits. This is
tested in `DefectPredictionTest.featuresAreComputedFromPriorHistoryOnly`.

### Labels: SZZ

`SzzLabeler` implements SZZ (Śliwerski, Zimmermann & Zeller, 2005):

1. Bug-fix commits are recognised from their messages, using the same heuristic as the platform's history view.
2. For each fix, the source lines it deletes or modifies are blamed in the fix's parent revision. The commits that
   last touched those lines are labelled **bug-introducing**.
3. Blank, comment, brace-only, import and `@Override` lines are ignored.
4. Fixes touching more than 30 files or 400 traced lines are skipped, because they are usually refactorings.

### Time-ordered evaluation

```
| warm-up 10 % | training 60 %                        | test 25 %          | immature 5 % |
                 labels: only fixes before the cutoff    labels: all fixes    not evaluated
```

* **Warm-up commits are not used.** Their history features are incomplete, because the window starts mid-history.
* **No leakage into training labels.** Training labels only count fixes committed before the first test commit:
  what a model trained at that moment could have known.
* **The newest 5 % are not evaluated.** Most of their bugs have not been found yet.
* **Model:** L2-regularised logistic regression (λ = 1), fitted with Newton–Raphson on log1p-transformed,
  standardised features. It is small, deterministic, and each score decomposes into per-feature contributions.
* **Flag threshold:** the threshold with the best F1 on the training period.
* **Baselines:**
  * *churn*: LA + LD as the score, the "larger is riskier" rule.
  * *prior*: the training bug rate for every commit, the reference for the Brier score.
* **Metrics:** ROC-AUC (Mann–Whitney, ties count half), Brier score, a 10-bin reliability diagram with expected
  calibration error, and precision/recall/F1 at the threshold and at 0.5.
* **Leakage check:** the same pipeline with shuffled training labels must score ROC-AUC ≈ 0.5 (permutation test,
  5 repetitions).

### Gate: when the model is used

A model is **accepted** only when all of these hold:

* at least 30 bug-introducing commits in training and 10 in test;
* test ROC-AUC ≥ 0.65;
* test ROC-AUC at least 0.02 above the churn baseline.

Otherwise its evaluation is stored and shown, but nothing is predicted with it.

An accepted model is retrained on all usable commits and scores the newest 300. An analysis shows those scores for
its own commits, next to the deterministic findings, labelled as a history-based estimate. Commits newer than the
model are reported as not scored, and the user can retrain.

## Results

Run on 2026-11-19 against full clones (`DefectEvaluationRun`):

| Repository | Commits used | Fixes (traced) | Bug-introducing | Train / test positives | Model AUC | Churn AUC | Brier (prior) | ECE | F1 (churn) | Permutation AUC | Decision |
|---|---|---|---|---|---|---|---|---|---|---|---|
| apache/commons-lang | 3,256 | 339 (195) | 238 | 102 / 49 | **0.773** | 0.729 | 0.0534 (0.0566) | 0.015 | 0.250 (0.169) | 0.516 | **accepted** |
| google/guava | 4,388 | 458 (241) | 314 | 141 / 70 | 0.801 | 0.788 | 0.0563 (0.0598) | 0.028 | 0.167 (0.249) | 0.516 | not better than baseline |
| spring-petclinic | 346 | 54 (40) | 41 | 25 / 5 | — | — | — | — | — | 0.590 | too few labels |
| spring-petclinic-rest | 346 | 82 (65) | 80 | 28 / 28 | — | — | — | — | — | 0.528 | too few labels |

Run times on 4 cores:

| | Dataset | SZZ |
|---|---|---|
| commons-lang | 8 s | 68–71 s |
| guava | 18–23 s | 92 s |
| petclinic repositories | < 2 s | < 2 s |

**Cross-repository** (train on all usable commits of A, test on B's test period; ROC-AUC, with B's churn baseline in
brackets):

| Train ↓ / Test → | petclinic | petclinic-rest | commons-lang | guava |
|---|---|---|---|---|
| petclinic | — | 0.748 (0.690) | 0.748 (0.729) | 0.734 (0.788) |
| petclinic-rest | 0.746 (0.784) | — | 0.775 (0.729) | 0.807 (0.788) |
| commons-lang | 0.795 (0.784) | 0.735 (0.690) | — | 0.800 (0.788) |
| guava | 0.805 (0.784) | 0.744 (0.690) | 0.778 (0.729) | — |

The petclinic test sets contain only 5 (petclinic) and 28 (petclinic-rest) bug-introducing commits, so their
numbers vary a lot with a few commits.

## Interpretation

* **There is a real signal, but it is mostly size.**
  * Every model ranks bug-introducing commits well above chance (AUC 0.73–0.81, permutation ≈ 0.5).
  * Change size alone already gets most of the way (0.69–0.79).
  * The largest coefficients are lines added (LA) and, on commons-lang, the number of earlier authors of the files
    (NDEV).
* **Calibration is good.** On commons-lang an estimate of 14 % corresponds to an observed 12 % (ECE 0.015). The
  estimates can be read as rough probabilities for commits similar to past ones. They stay low: the base rate is
  5–6 %, and the model rarely goes above 40 %.
* **It is not a precise per-commit warning.** At the best threshold it flags about 1 in 5 bug-introducing commits,
  and about 3 in 10 flagged commits really were bug-introducing (commons-lang). The platform therefore shows it as
  an experimental estimate beside the evidence, never as a finding or as part of the deterministic risk score.
* **Models transfer between repositories about as well as they work within one.** That is consistent with the
  signal being generic (size, diffusion, file history) rather than project-specific.

## Threats to validity and limitations

* **SZZ labels are noisy.**
  * Fixes are found from commit messages: commits that fix bugs without saying so are missed, and "fix typo" counts
    as a fix.
  * Blame points at the last commit to touch a line, which may be a refactoring rather than the cause.
  * 30–45 % of fixes cannot be traced, because they only add lines or are too large.
* **Right-censoring.** Recent commits look clean because their bugs have not been fixed yet. The immature 5 % is
  excluded from testing, but the effect continues into the test period and lowers the observed bug rate.
* **The window starts mid-history** on large repositories (5,000 commits). The warm-up reduces, but does not
  remove, the bias in history features.
* **Generalisation is limited.** There are four repositories, all Java, three of them small. More repositories
  (and languages) are needed before generalising.
* **No issue-tracker linkage.** SZZ variants that use issue reports, filter by issue date, or detect refactorings
  (RA-SZZ) would give cleaner labels but need data the platform does not collect.
* **The model is deliberately simple.** Logistic regression on 14 features. More complex learners might gain a
  little AUC, at the cost of the per-feature explanation shown with every estimate.

## Reproduce

```bash
cd backend
mvn test -Dtest=DefectEvaluationRun -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddefect.eval.repos=commons-lang=/path/to/commons-lang,guava=/path/to/guava \
  -Ddefect.eval.out=/tmp/defect-evaluation.json
```

In the application: open a repository, go to **History → Defect prediction from history**, and click **Train from
history** (`POST /api/v1/repositories/{id}/defect-model/train`). This runs as a `DEFECT_MODEL` job.

## References

* Kamei, Y. et al. (2013). *A large-scale empirical study of just-in-time quality assurance.* IEEE TSE 39(6).
* Śliwerski, J., Zimmermann, T., Zeller, A. (2005). *When do changes induce fixes?* MSR 2005.
* Kim, S. et al. (2006). *Automatic identification of bug-introducing changes.* ASE 2006.
* Tan, M. et al. (2015). *Online defect prediction for imbalanced data.* ICSE 2015 (time-ordered evaluation, label
  latency).
