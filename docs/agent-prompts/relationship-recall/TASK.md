# Task: improve relationship recall, scored on the smartdiff corpus

Written 2026-11-27. Matcher task for a coding agent; the paste-in prompt is [PROMPT.md](PROMPT.md). Work on branch `feat/relationship-recall-corpus-misses`, which already holds this file.

## Why

The [relationship benchmark](../../RELATIONSHIP-BENCHMARK.md) on `src/test/testData/relationships-corpus-full.txt` is the score for relationship quality. At the [0.5.0 release validation](../../VALIDATION.md#2026-11-25-050-release-validation) it gave **623 cases, precision 1.000, recall 0.966 (345 of 357 labelled pairs)**, with tuning recall 1.000 and **held-out recall 0.924**. Every remaining miss is a real-commit case from the cloned projects of the smartdiff corpus. The [2026-11-23 relationship recall](../../VALIDATION.md#2026-11-23-relationship-recall) work (PR #218, `relationships/03` R21 and `relationships/05` R21) recovered seven pairs and listed the twelve that are still missed:

| # | Kind | Case | Label |
| --- | --- | --- | --- |
| 1–6 | EXTRACT | commons-lang `CharRange` (six extractions in one file) | `src/test/testData/java/relationships/commits/commons-lang/e66ad3d/CharRange.yaml` |
| 7 | EXTRACT | Exposed `H2FunctionProvider.update` → property `isH2Oracle` | `src/test/testData/kotlin/relationships/commits/Exposed/deb65b9/H2.yaml` |
| 8 | RENAME | moshi `skipTo(String)` → `skipToEndOfBlockComment()`, rewritten | `src/test/testData/java/relationships/commits/moshi/5a46cd6/JsonUtf8Reader.yaml` |
| 9 | RENAME | okhttp `httpWithExcessiveHeaders` | `src/test/testData/kotlin/relationships/commits/okhttp/7ef4fb4/CallTest.yaml` |
| 10–11 | MOVE | cats `IfApplyOps`, `ApplyOps` | `src/test/testData/scala2/relationships/commits/cats/e74b301/apply.yaml` |
| 12 | MOVE | cats `parReplicateA` | `src/test/testData/scala2/relationships/commits/cats/9bd7dce/parallel.yaml` |

The list above is from the last recorded run. The first step re-measures it on main; if main's report differs, the report wins.

The goal is to recover as many of these as the engine can find **without one new wrong pair, forced pair or hidden change**, and to show that the gain is not fitted to these twelve cases.

## The overfitting problem, and how this task handles it

All twelve misses are on the `held-out` split, and they have already been looked at, so fixing exactly them would turn the held-out set into a tuning set ([relationships/01](../../specs/relationships/01-relationship-benchmark.md) R11, its details' "Thresholds are tuned only on the `tuning` split", [relationships/03](../../specs/relationships/03-relationship-matching.md) R20). The task therefore:

1. Explains every miss by a **general mechanism** (what structural evidence the matcher ignores), never by the case's names, sizes or file.
2. Reproduces each mechanism it fixes in **new synthetic cases on the `tuning` split** (at least three per mechanism, including at least one near-miss that must stay unpaired), and makes those pass first.
3. Changes **no existing threshold or weight** to recover a held-out case. A new rule may add a new condition; it must not lower `MIN_TILE`, the content floor, the rename or extraction score thresholds or the confidence thresholds.
4. Measures the result on a **fresh held-out set**: new real-commit labels from commits the benchmark has never seen, labelled against main's engine *before* the change (step 2 below), so they score the change honestly.

## What to do

### Step 1. Baseline on main

On a clean checkout of `origin/main` (or this branch before any engine change), with the corpus clones in `../smartdiff-corpus` (the paths `relationships-corpus-full.txt` uses), run:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json
```

Copy `build/reports/smartdiff-relationships/report.json` and `report.md` **outside the repository**, for example `../relationship-recall-baseline/`. This main report is the baseline every later run is gated against (`-Psmartdiff.relationships.baseline=../relationship-recall-baseline/report.json`). Do not overwrite the checked-in `src/test/testData/relationships-baseline/report.json`.

Before going on, check that every clone's HEAD matches the pinned commits in VALIDATION.md's 0.5.0 run (the corpus revision in the report must be `60f29d157c12` or the report must say why it differs). If a clone is missing or at another commit, stop and report it.

### Step 2. Fresh held-out labels (before any engine change)

Add at least **5 new real commits per language for Java, Kotlin, Scala 2, Scala 3 and Python** from the clones already in `../smartdiff-corpus` (okhttp, guava, commons-lang, spring-petclinic, moshi, Exposed, cats, zio, scala3, flask, attrs), choosing commits that are **not** in `relationships-corpus-full.txt` and that contain at least one rename, move, extraction or inlining a reviewer can see in the commit. Prefer commits whose message or diff names a refactoring. Then:

- Generate drafts with main's engine: add the commits to a copy of the list and run with `-Psmartdiff.relationships.drafts=<dir>`.
- Review every draft by hand against `git show <commit> -- <path>`, add the relationships the engine missed, remove the wrong ones, set `reviewed: true` and `split: held-out`, and write in `notes` what the commit does. A label says what the commit did, not what the engine reports.
- Add the commit lines to `relationships-corpus-full.txt` and the labels under `src/test/testData/<language>/relationships/commits/<repo>/<short-hash>/`, as the existing ones are.
- Rerun step 1's command on main with the extended list and keep that report as the baseline instead (the fresh misses are now part of main's score).
- Commit this alone ("test(benchmark): add fresh held-out real-commit labels"), before any engine change.

These labels are a release gate input, so the PR lists them for a second review ([relationships/01 details](../../specs/relationships/01-relationship-benchmark-details.md), Labelling).

### Step 3. Diagnose the misses

For each miss in the main report (the twelve above and any fresh one), find why the engine did not report it, by reading the engine's scores for that declaration pair (the benchmark's report, a focused unit test, or a temporary debug print that is not committed). Write the result in a table in the PR body and in VALIDATION.md, one row per miss, with one of:

- **label error**: the label is wrong against the commit. Fix the label, say why in `notes`, and count it separately. (The 0.5.0 run fixed a scala3 label this way; the 2026-11-23 work lengthened the Python `inlining-1` fixture for the same reason.)
- **engine miss, fixed**: name the mechanism and the requirement that fixes it.
- **engine miss, not fixed**: why a fix would risk precision or is out of scope (for example a rename whose body was rewritten past any token evidence, which [ADR 0003](../../adr/0003-conservative-classification.md) says must stay a removal and an addition).

Starting points, to be checked rather than trusted:

- **CharRange (6 EXTRACT)**: several small helpers extracted from one method; look at whether the many-targets path (`extraction-many-targets` cases, [declarations/05](../../specs/declarations/05-extraction-detection.md)) splits the source's removed tokens so that each target falls under the floor, and whether the R21 short-run rule of relationships/05 has an extraction-side counterpart that does not apply here.
- **Exposed `isH2Oracle` (EXTRACT to a property)**: extraction targets may be limited to methods and functions; an extracted expression into a property or `val` getter is a different target kind.
- **moshi `skipTo` and okhttp `httpWithExcessiveHeaders` (RENAME)**: renames with a signature change and a body rewrite; check which evidence (call-site replacement in the same file, unique removed/added pair in the container, reference evidence from [relationships/04](../../specs/relationships/04-rename-grouping.md)) is present and unused. A unique removed/added pair alone is not enough evidence.
- **cats `IfApplyOps`, `ApplyOps`, `parReplicateA` (MOVE)**: moves between containers in Scala 2, possibly between an object and a trait or into a new container; check how the matcher pairs declarations whose container path changed and whose container was itself added or renamed.

### Step 4. Spec first

For each mechanism you fix, amend the feature spec that owns it with a new requirement ID, as PR #218 did (relationships/03 R21, relationships/05 R21): [relationships/03](../../specs/relationships/03-relationship-matching.md) for pairing and renames, [relationships/05](../../specs/relationships/05-inlining-detection.md) for inlining, [declarations/05](../../specs/declarations/05-extraction-detection.md) for extraction, and the move rules where they live. Update the `-details.md` files (design, thresholds, tests table) and the status lines in the specs and in `docs/specs/README.md` ("implemented, …; R22 2026-11-xx"). Follow [docs/SPEC-GUIDE.md](../../SPEC-GUIDE.md). Commit the spec changes alone, before the code ("docs: specify … (relationships/03 R22 …)").

Each new requirement states the structural evidence it needs and what must stay unpaired; the near-miss cases of step 5 are its "not" examples.

### Step 5. Synthetic cases and tests

- For each new requirement: at least three new benchmark cases on the `tuning` split under `src/test/testData/<language>/relationships/<category>-N/` (`old.<ext>`, `new.<ext>`, `label.yaml`), in at least two languages where the mechanism applies, one of them a near-miss whose label lists no relationship (or an `ambiguous` entry). Add them to `relationships-corpus.txt` and `relationships-corpus-full.txt`.
- A unit test per requirement in the matcher's test classes (`RelationshipMatchingTest`, the extraction and inlining tests), named with the requirement (`// relationships/03 R22`), on the providers' real tokens where the earlier R21 tests do.
- `RelationshipTuningTest` and the benchmark smoke run must pass.

### Step 6. Implement

In `src/main/kotlin/io/github/agadek/smartdiff/engine/declarations/analysis/` (`DeclarationMatcher.kt`, `ExtractionDetector.kt`, `GlobalAssignment.kt`, `CodeMoveDetector.kt` as needed). Rules:

- No threshold or weight in the existing code changes (see the overfitting section). A new rule is a new, named condition with its own constant, documented in the details file.
- Nothing hidden: a new relationship never hides a change; every declaration keeps its classification unless the spec says otherwise.
- Ambiguity stays visible: an equally good second candidate means no pair (relationships/03, the ambiguous-group cases).
- Bump `DeclarationEngine.VERSION` (26 → 27; relationships/03 R18).
- Stay within the analysis limits ([engines/03](../../specs/engines/03-analysis-limits.md)); `LimitsBenchmarkTest` must not slow down by more than 20%.

### Step 7. Score

Rerun the benchmark with the extended list, gated against step 2's main report:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs \
  -Psmartdiff.relationships.baseline=../relationship-recall-baseline/report.json
```

Also run the language modules' benchmarks (they run in `check`), `LimitsBenchmarkTest` on the six large files of the 0.5.0 run (`-Psmartdiff.limits.corpus=<list>`), and `CorpusValidationTest` on the Java/Kotlin and Scala two-sided lists of the 0.5.0 run with the same pinned HEADs (rebuild the lists from VALIDATION.md if they are not on disk; keep them outside the repository): no new wrong link, `hidden` or `empty` flag. Review every new `rename`, `extraction`, `move` or `uncertain` flag by hand.

Record the run in `docs/VALIDATION.md` as a new dated section at the top ("2026-11-xx: relationship recall on the smartdiff corpus"), in the style of the 2026-11-23 section: engine version, corpus revision, a table of main against the new engine (precision, recall, tuning and held-out recall separately, fresh held-out recall separately, per kind tp/fn, per language recall, usages, classification mismatches, forced pairs), the miss table from step 3, and the corpus validation and limits results.

### Step 8. Golden fixtures

Golden fixtures may change only where a new relationship is now reported. Run `./gradlew check` without `-Psmartdiff.updateGolden=true` first and read every failure. Update a golden file only after reading its diff and agreeing with it, and list each changed fixture with one line of why in the PR (relationships/03 R17). A golden change that removes a relationship, hides a change or changes a classification for another reason is a bug to fix, not a fixture to update.

### Step 9. Docs

`CHANGELOG.md` `[Unreleased]` → `### Changed` (or `Fixed`), in user terms ("SMART Diff now links … ."); `docs/RELATIONSHIP-BENCHMARK.md` only if the case matrix gains a category. Set the amended specs' status to implemented with the date in the last commit.

## Acceptance criteria

1. The fresh held-out labels (step 2) are committed before any engine change, reviewed by hand, and listed in the PR for a second review.
2. The spec changes are committed before the code, with new requirement IDs and their "not" cases.
3. Benchmark on the extended full list, gated against main's report: **precision 1.000, 0 wrong pairs, 0 forced pairs, no new gate pair**; held-out recall (old and fresh separately) is not lower than main's on any kind or language, and overall recall is higher. Tuning recall stays 1.000.
4. Every miss of main's report has a row in the miss table: label error, fixed (with its requirement), or not fixed (with the reason).
5. No existing threshold, weight or floor changed; every new constant is documented in a details file.
6. Corpus validation: no new wrong link, `hidden` or `empty` flag against the 0.5.0 run; limits within 20% of the 0.5.0 run.
7. Golden fixture changes are each read and listed with their reason; none is produced by `-Psmartdiff.updateGolden=true` without review.
8. No test is skipped, disabled, weakened or deleted.
9. Local CI passes before every push: `./gradlew buildPlugin check` and `./gradlew verifyPlugin` (every IDE family: idea, pycharm, rustrover, webstorm, goland, phpstorm, datagrip), with no new internal, experimental or deprecated API use and no change to `docs/PLATFORM-API.md`. The commands, their results and the date are recorded in the PR body.
10. One PR from `feat/relationship-recall-corpus-misses` to `main`, opened by the agent, which names the requirements, gives the before/after table, the miss table, the fresh labels for review and the local CI record, and is not merged by the agent.

## Out of scope

- Cross-file moves and commit-level refactoring groups ([RELATIONSHIP-BENCHMARK.md](../../RELATIONSHIP-BENCHMARK.md) "Later").
- Local-variable renames and moved expressions.
- The Tree-sitter languages, Go, PHP, SQL and Rust relationship rows (their benchmarks must still pass unchanged).
- Any UI change. The work is in the engine and the benchmark; no `runIde` check is needed.
