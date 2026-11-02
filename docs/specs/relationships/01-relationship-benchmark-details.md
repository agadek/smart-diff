# Relationship benchmark: details

Status: accepted, 2026-11-01

Feature: [Relationship benchmark](01-relationship-benchmark.md)

## Design

### Where it plugs in

`RelationshipBenchmarkTest` is an opt-in test next to `CorpusValidationTest` and `LimitsBenchmarkTest`. It reuses the existing parts:

- `compareSafely` with the default limits, so a case runs as it does in the IDE;
- `ComparisonEngineContract` for the shared rules;
- `NothingHidden` for the nothing-hidden check;
- `Rendering` for the readable parts of the report.

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=<list> \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs \
  -Psmartdiff.relationships.baseline=<report.json>
```

The corpus list names case folders and real-commit lines; a real-commit line has the same format as the corpus validation list.

`RelationshipBenchmarkTest` reads the properties, runs `RelationshipBenchmark.run`, writes the report and fails with the gate failures (R1, R22 to R27). The checked-in cases are also run by an ordinary test on a temporary output folder, as a smoke run, so CI exercises the harness without the corpus property.

### Before the relationship model exists

The baseline runs on today's engine, which has no relationship list ([relationships/02](02-relationship-model.md) adds it). An adapter derives relationships from today's `ChangeNode` fields:

| Field | Relationship kind |
| --- | --- |
| `renamedFrom` | rename |
| `moved` | move |
| `kindChangedFrom` | kind change |
| `extractedFrom` / `extractedTo` | extraction |

Today's results have no confidence, so the adapter gives a rename candidate medium confidence and every other link high confidence. Once relationships/02 ships, the benchmark reads the result's list directly and the adapter is removed.

### Label format

One `label.yaml` per case, beside `old.<ext>` and `new.<ext>`:

```yaml
case: rename-method
language: JAVA               # IntelliJ language id for the comparison
corpus: java                 # java, kotlin, scala2 or scala3; groups the report rows
category: method-rename      # the case matrix category
split: held-out             # or tuning
expected:
  relationships:
    - kinds: [RENAME]
      old: { path: "class Order", kind: METHOD, name: total, signature: "()" }
      new: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
  changes:
    - subject: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
      presence: MATCHED
      content: UNCHANGED
  ambiguous: []
notes: "A second method with the same body exists on purpose."
```

- `language` is the IntelliJ language id passed to the engine; `corpus` names the fixture folder and keeps Scala 2 and
  Scala 3 as separate report rows.
- `category` is the case matrix category, such as `method-rename` or `kind-change`; the matrix check uses it rather than
  the case name.
- An endpoint is path, kind, name and signature, as the outline names it. A `range` (`start..end` offsets) is added only when two declarations would have the same identity.
- Confidence is optional in a label; it is compared only when given.
- The label set is closed: every relationship the engine reports that is not listed is wrong, unless the case lists it under `ambiguous`.
- `ambiguous` lists the declarations for which no pair is correct; any pair reported for them is a forced pair.

### Real commits

A real-commit line is a local clone followed by `git rev-list` arguments, as in the corpus validation (R10, R34). For each selected commit, every Java, Kotlin and Scala file it changed is compared between the first parent and the commit, with the same request as the corpus validation, the Scala dialect included.

Labels for real commits are checked in, so the scores do not depend on the machine. One label per file:

```text
src/test/testData/<language>/relationships/commits/<repository>/<short-sha>/<file-name>.yaml
```

```yaml
case: commons-lang-3f2a9c1-StringUtils
language: JAVA
corpus: java                 # java, kotlin, scala2 or scala3
category: real-commit
split: held-out
reviewed: true
source:
  repository: commons-lang   # the clone folder's name, not its path
  commit: 3f2a9c1e...        # the full hash
  path: src/main/java/org/apache/commons/lang3/StringUtils.java
expected:
  relationships: [...]
  changes: [...]
  ambiguous: []
```

- The repository is matched by the clone folder's name, so a label works on any machine with that clone (R35).
- The old and new texts are read with `git show <commit>^1:<path>` and `git show <commit>:<path>`; a file added or deleted by the commit is skipped and counted, like a missing file in the corpus validation.
- A selected file with no label, or with `reviewed: false`, is listed under `unlabelled` in the report and in `report.json`, and is not scored (R36).
- A label whose commit is not in the clone, or whose path is not in the commit, fails the run with the label's path.
- A labelled commit counts toward R10 when every Java, Kotlin or Scala file it changed has a reviewed label.

### Draft labels

`-Psmartdiff.relationships.drafts=<dir>` writes a draft label for every unlabelled file into `<dir>`, in the layout above, with `reviewed: false` (R37). A draft lists what the engine reported today: its relationships, every declaration's classification, and no ambiguous entries.

A draft is a starting point only. The labeller checks every relationship and classification against the two texts, adds what the engine missed, removes what it got wrong, lists the ambiguous declarations, and then sets `reviewed: true`. A label copied from the engine without that check would make today's mistakes count as right, so the pull request that adds labels lists, for each label, what was changed from its draft.

### Case matrix and smoke runs

`matrixFailures` runs on every run, after the cases are read (R30). It counts the reviewed labels per category and corpus (R33), checks each category of the list below against the R9 minimum where the language has the construct, and checks the labelled commits per corpus against the R10 minimum.

- Constructs a language does not have are listed in `RelationshipBenchmark.NOT_APPLICABLE`, with a reason for each; no category is left out without one.
- When any minimum is missed, the report header says `smoke run`, lists every shortfall, and `report.json` has `"smoke": true` (R31).
- A baseline whose `report.json` has `"smoke": true` fails the run with `baseline is a smoke run` (R32).
- The VALIDATION.md baseline of R29 is the first run with no shortfall.

### Case categories

Every category needs at least 3 cases per language where the language has the construct: method rename, class rename, field rename, rename with body change, rename with signature change, move in the file, move with rename, move with body change, kind change, signature change, overloads with the same body, duplicate bodies in one container, extraction, extraction from several sources, extraction to several targets, inlining, statement reorder, formatting-only, comment-only, string literal change, parse error on one side, malformed on both sides, empty and identical files, large file, ambiguous rename group.

Expected classifications follow the current model: a comment-only change is `CHANGED`; malformed input on both sides is `FAILED` only when no declaration was found, otherwise `PARTIAL`.

### Metrics

- Per relationship kind: true positives, false positives, false negatives, precision, recall, and the share of wrong pairs among reported pairs. Endpoints are compared by identity, so the right name on the wrong declaration is a false positive.
- A reported relationship is matched to the label with the same endpoints first, then compared kind by kind (R38). A rename and move reported for a label listing both kinds is two true positives and no false positive; reported as a rename only, it is one true positive and one false negative for the move.
- A pair reported for a declaration listed under `ambiguous` is counted as forced only (R14, R39). It is not a false positive, so it does not lower precision, and the `no-new-wrong-pairs` gate still fails on it.
- Every count and metric is grouped by corpus (`java`, `kotlin`, `scala2`, `scala3`), never by language alone, so Scala 2 and Scala 3 get separate rows (R33).
- Extraction and inlining links are counted per link, so a target with three sources counts three.
- Results are micro-averaged over links per language, and also given per category.
- Presence and content accuracy, and the accuracy of `moved`, signature change and kind change.
- Forced pairs in ambiguous cases, which should be zero.
- Nothing-hidden violations, as a safety count rather than a coverage ratio.
- Files that returned `UNSUPPORTED`, `TOO_LARGE` or `FAILED`.
- Median, 95th percentile and slowest time per language, with the machine, JVM and IDE build.

### Gates

| Mode | Fails when |
| --- | --- |
| `no-new-wrong-pairs` (default) | a wrong or forced pair is not in the baseline |
| `precision` | precision falls below the baseline for any kind and language |
| `tolerance` | precision or recall falls more than `-Psmartdiff.relationships.tolerance=<points>` below the baseline |

In every mode a nothing-hidden violation, a file over the 5 s limit or an exception fails the run. Inlining's recall starts at zero, so its gate applies from [relationships/05](05-inlining-detection.md).

- A `-Psmartdiff.relationships.baseline` path that is not a file fails the run with `baseline not found: <path>` (R40).
- `precision` and `tolerance` compare each `(corpus, kind)` pair on its own (R41). A pair the baseline lacks does not fail the run; the report lists it under `new in this run`, so a new category or language is visible without blocking.

### Report contents

- `report.md` lists every wrong pair, forced pair, missed relationship and misclassified declaration, each with its case, both endpoints or both classifications, and the label's path (R18, R42).
- `report.json` has the engine version, IDE build, Scala plugin version, corpus revision, gate mode, smoke flag and shortfalls, the metrics per corpus, per split and overall, the skipped and unlabelled files, and every list of `report.md` (R19, R20).
- The corpus revision is a hash of the corpus list and of every case and label file it reaches, in path order, plus the commit hash of each real-commit line's clone (R43).

### Labelling

- Labels are written against a pinned revision pair, and a second reviewer checks every label a release gate depends on.
- Disagreements are recorded in `notes`, not settled by the implementer alone.
- Thresholds are tuned only on the `tuning` split, and the report gives the `held-out` split separately.

### Thresholds and reasons

- At least 3 cases per category and 30 real commits per language: enough that one extra wrong pair does not swing precision by more than a few points. Proposed, to confirm after the first baseline.
- `no-new-wrong-pairs` as the default: a small corpus makes "precision not below the baseline" fail on noise.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `RelationshipBenchmarkTest` is skipped without the property; a build with no property passes |
| R2–R7 | A label parser test with one valid label per rule and invalid labels that fail with a clear message |
| R8–R10 | A check that lists the cases per category and language and fails when a minimum is not met |
| R11 | A test that tuning and held-out cases are reported apart |
| R12–R18 | Report tests over a small fixed corpus with known true and false positives |
| R19–R21 | The report header test, and a comparison with a saved baseline |
| R22–R27 | One test per gate mode with a baseline that should pass and one that should fail |
| R28 | Two runs on the same cases give equal reports apart from times |
| R29 | Manual: the baseline section in VALIDATION.md comes from a run with no shortfall |
| R30–R32 | `matrixFailures` on a corpus one case short of a minimum gives a smoke report, and that report is refused as a baseline |
| R33 | A corpus with one Scala 2 and one Scala 3 case gives two rows and two sets of minimums |
| R34–R36 | A test Git repository built in the test, with one labelled and one unlabelled file in a commit, scores the first and lists the second |
| R37 | Drafts for the test repository have `reviewed: false` and are not scored until the flag is changed |
| R38 | A rename and move against labels listing both kinds, one kind, and the other kind |
| R39 | A forced pair is counted as forced and leaves precision unchanged |
| R40 | A missing baseline path fails the run |
| R41 | A baseline missing one `(corpus, kind)` pair passes and lists it as new; a lower precision in one pair fails |
| R42 | A case with a wrong classification is listed with both values |
| R43 | Editing a label changes the corpus revision |

## Open questions

- Does the benchmark label an expected confidence, or only the expected evidence?
- When, if ever, does the corpus include relationships across files?
- Which commits are labelled first: those the corpus validation flagged with a rename, move or extraction, or a random sample of each repository? A mix of both, about half each, is proposed, so precision is measured on likely refactorings and recall on ordinary commits.
