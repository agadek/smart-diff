# Relationship benchmark: details

Status: implemented, 2026-11-02; the usage labels (R8, R9), the reference pass version in R19 and R44 arrive with [rename grouping](04-rename-grouping.md), 2026-11-05; the Java step 1 is implemented, 2026-11-07.

Feature: [Relationship benchmark](01-relationship-benchmark.md)

## Design

### Where it plugs in

`RelationshipBenchmarkTest` is an opt-in test next to `CorpusValidationTest` and `LimitsBenchmarkTest`. It reuses the existing parts:

- `compareSafely` with the default limits, so a case runs as it does in the IDE;
- `ReferencePass.runForBenchmark` after the engine for usage labels, so the benchmark resolves usages without a project file ([relationships/04](04-rename-grouping.md) R18);
- `ComparisonEngineContract` for the shared rules;
- `NothingHidden` for the nothing-hidden check;
- `Rendering` for the readable parts of the report.

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=<list> \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json \
  -Psmartdiff.relationships.drafts=<dir>
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

Today's results have no confidence, so the adapter gives a rename candidate medium confidence and every other link high confidence. Those values are for later: a label may carry `confidence`, but the benchmark does not compare it until the engine reports one (relationships/02, R4). A label's confidence is therefore kept as labeller intent, not as a scored value.

A move's endpoint is named by its container on each side, and a cross-container move changes the container. The adapter builds an old endpoint's path from the old side's declaration tree, matched by the node's old range, and falls back to the new-side path when the old tree has no such range. The extraction source path comes from the origin's declaration range the same way. Without the old tree, as in a unit test that builds only a result, the new-side path is used.

An extraction is listed from both ends: the source's `extractedTo` and the target's `extractedFrom`. The adapter counts the two listings as one link when kind, both identities and the source's declaration range agree; the copy kept is the one that carries declaration ranges on both sides, so a label that gives a range still matches. Two extractions whose source declaration ranges differ stay two links (R6).

Exact duplicate rename, move and kind-change links are dropped today: two links with the same kind and both endpoint keys collapse to one. This is safe for the current adapter, which visits each `ChangeNode` once, so one node tree cannot produce the same non-extraction link twice. It is not safe for an engine that reports its own relationship list, where a repeated link is a wrong pair. When [relationships/02](02-relationship-model.md) makes the benchmark read that list, it should stop dropping these duplicates, so a repeated link counts as the false positive it is.

`null`, `""` and `"()"` are the same signature identity in every language. In Scala, `def size` and `def size()` are distinct declarations, so a label for one can match the other. This is a deliberate adapter simplification until relationships/02 gives each endpoint its declaration's own signature; the current labels do not rely on the difference.

Once relationships/02 ships, the benchmark reads the result's list directly and the adapter is removed.

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
      usages:
        - old: "118..123"
          new: "116..121"
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
- Confidence is optional in a label and is recorded for later; it is not compared until the engine reports a confidence value.
- The label set is closed: every relationship the engine reports that is not listed is wrong, unless the case lists it under `ambiguous`.
- `ambiguous` lists the declarations for which no pair is correct; any pair reported for them is a forced pair.
- A rename relationship carries `usages`: a list of `old` and `new` range pairs, one per grouped usage ([relationships/04](04-rename-grouping.md) R18, R44). The list is added with relationships/04. In the first step every rename label in the full gate corpus is reviewed, including all 120 real commits and the 16 usage-grouping fixtures. The existing 424-case corpus has 89 renames (69 high, 20 medium, 0 low); the 440-case gate corpus adds the 16 usage-grouping fixture folders to `relationships-corpus-full.txt` and has 105 renames (85 high, 20 medium, 0 low), because each usage-grouping fixture adds one high rename. A range is `start..end`, as for an endpoint. `usages: []` means the label was reviewed and the rename has no usages. An absent list is allowed only in a draft; in a gate run it is a `usage-unlabelled` rename and fails completeness. A reported reference on a rename without `usages` is listed as `usage-unlabelled` and is not scored, so an incomplete corpus stays visible. A reported reference that does not match a listed pair is a wrong usage and fails the `no-new-wrong-pairs` gate; a listed usage that is not reported is a missed usage and is listed, not gated by default.

### Real commits

A real-commit line is a local clone followed by `git rev-list` arguments, as in the corpus validation (R10, R34). For each selected commit, every Java, Kotlin and Scala file it changed is compared between the first parent and the commit, with the same request as the corpus validation, the Scala dialect included.

Labels for real commits are checked in, so the scores do not depend on the machine. One label per file:

```text
src/test/testData/<language>/relationships/commits/<repository>/<short-sha>/<file-name>.yaml
```

`<language>` is the corpus folder (`java`, `kotlin`, `scala2` or `scala3`) and `<file-name>` is the changed file's name
without its extension, so `src/main/java/org/example/Order.java` has the label `Order.yaml`. A folder name that is a
prefix of the commit works as the short SHA.

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
- The old and new texts are read with `git show <commit>^1:<path>` and `git show <commit>:<path>`; a file added or deleted by the commit is skipped and counted, like a missing file in the corpus validation. The changed-file listing passes `--no-renames`, so a rename is a delete plus an add, both listed as skipped, and the result does not depend on each machine's `diff.renames` setting (R28, R34, R35). Git path output passes `-c core.quotePath=false`, so a non-ASCII path is not quoted and `git show` reads it unchanged.
- A selected file with no label, or with `reviewed: false`, is listed under `unlabelled` in the report and in `report.json`, and is not scored (R36). A label without `reviewed` is not scored, so forgetting the flag cannot make a draft a result.
- A label whose commit is not in the clone, whose path is not in the commit, whose commit has no first parent, or whose repository or corpus does not match its folder, fails the run with the label's path.
- Every selected file is compared with its first parent, labelled or not (R34). A nothing-hidden violation, a file over the 5 s limit or an exception in an unlabelled file still fails the run (R26).
- An unlabelled Scala file is counted under the dialect it parses in (R33). If no Scala provider is loaded, or reading the dialect throws, the file is listed with the reason `unknown dialect` and is not counted under Scala 2 or Scala 3, so a sandbox without the Scala plugin cannot file a Scala 3 file as Scala 2 or let the commit count toward R10.
- A labelled commit counts toward R10 when every Java, Kotlin or Scala file it *modified* has a reviewed label. Added and deleted files are listed as skipped and do not block the count, so a commit that extracts a class into a new file can still be one of the 30. A commit that modifies no Java, Kotlin or Scala file, or has a file with `unknown dialect`, does not count.
- The first gate reviews usages for every rename in all 120 real-commit labels (30 per corpus) and for the 16 usage-grouping fixtures. A real-commit label with no rename needs no `usages` list; every rename relationship in it gets one, including `usages: []` when the review finds no usage.

### Draft labels

`-Psmartdiff.relationships.drafts=<dir>` writes a draft label for every unlabelled file into `<dir>`, in the layout above, with `reviewed: false` (R37). A draft lists what the engine reported today: its relationships, every declaration's classification, and no ambiguous entries.

A draft is a starting point only. The labeller checks every relationship and classification against the two texts, adds what the engine missed, removes what it got wrong, lists the ambiguous declarations, and then sets `reviewed: true`. A label copied from the engine without that check would make today's mistakes count as right, so the pull request that adds labels lists, for each label, what was changed from its draft.

### Case matrix and smoke runs

`matrixFailures` runs on every run, after the cases are read (R30). It counts the reviewed labels per category and corpus (R33), checks each category of the list below against the R9 minimum where the language has the construct, and checks the labelled commits per corpus against the R10 minimum.

- Constructs a language does not have are listed in `RelationshipBenchmark.NOT_APPLICABLE`, with a reason for each; no category is left out without one.
- When any minimum is missed, the report header says `smoke run`, lists every shortfall, and `report.json` has `"smoke": true` (R31).
- A baseline whose `report.json` has `"smoke": true` fails the run with `baseline is a smoke run` (R32).
- The VALIDATION.md baseline of R29 is the first run with no shortfall, and its `report.json` is checked in.

### Case categories

Every category needs at least 3 cases per language where the language has the construct: method rename, class rename, field rename, rename with body change, rename with signature change, usage grouping (at least 4 cases, as [relationships/04](04-rename-grouping.md) R18 asks), move in the file, move with rename, move with body change, kind change, signature change, overloads with the same body, duplicate bodies in one container, extraction, extraction from several sources, extraction to several targets, inlining, statement reorder, formatting-only, comment-only, string literal change, parse error on one side, malformed on both sides, empty and identical files, large file, ambiguous rename group.

Expected classifications follow the current model: a comment-only change is `CHANGED`; malformed input on both sides is `FAILED` only when no declaration was found, otherwise `PARTIAL`.

### Metrics

- Per relationship kind: true positives, false positives, false negatives, precision, recall, and the share of wrong pairs among reported pairs. Endpoints are compared by identity, so the right name on the wrong declaration is a false positive.
- A reported relationship is matched to the label with the same endpoints first, then compared kind by kind (R38). A rename and move reported for a label listing both kinds is two true positives and no false positive; reported as a rename only, it is one true positive and one false negative for the move.
- A pair reported for a declaration listed under `ambiguous` is counted as forced only (R14, R39). It is not a false positive, so it does not lower precision, and the `no-new-wrong-pairs` gate still fails on it.
- Every count and metric is grouped by corpus (`java`, `kotlin`, `scala2`, `scala3`), never by language alone, so Scala 2 and Scala 3 get separate rows (R33).
- Extraction and inlining links are counted per link, so a target with three sources counts three.
- Usages are counted per reference range pair, separately from relationship pairs. A reference matches a label when its relationship endpoints and both ranges equal the label's; a reported reference that matches a labelled usage is a true positive, one with no matching pair is a wrong usage, and a labelled usage with no reference is a missed usage. A rename with no `usages` list has its reported references counted as `usage-unlabelled`, not scored. The first gate counts usages for all 440 cases (424 existing plus 16 usage-grouping), including all 120 real commits.
- Results are micro-averaged over links per language, and also given per category, with the category rows in `report.md` and `report.json`.
- A precision or recall with nothing to divide is shown as `n/a`, not as `1.000`, in `report.md`, `summary.tsv` and the baseline table; `report.json` keeps the number for the gate and adds the reported-pair count beside it.
- Presence and content accuracy, and the accuracy of `moved`, signature change and kind change.
- Each expected change claims at most one node, and each node is claimed at most once. Candidates are walked in label order, a presence match is preferred among the nodes left, and a claimed node is removed, so two same-identity overloads cannot both score against one reported node (R15, R42).
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

In every mode a nothing-hidden violation, a file over the 5 s limit or an exception fails the run. Inlining's recall starts at zero, so its gate applies from [relationships/05](05-inlining-detection.md). The first usage gate is the 440-case corpus: all 320 checked-in cases (304 existing plus the 16 usage-grouping fixtures) and all 120 real commits, every rename usage-reviewed. The 2026-11-05 engine-9 run on the existing 424 cases is not that gate; by R9/R18 the missing usage-grouping category makes it a smoke run for the usage gate. A wrongly grouped usage is a wrong pair for `no-new-wrong-pairs` (R44); the baseline has no usages, so any wrong usage is new. A `usage-unlabelled` rename in the gate run also fails completeness. The usage precision and recall baseline is recorded from the gate run after [relationships/04](04-rename-grouping.md), and the 2026-11-02 baseline is not replaced.

- A `-Psmartdiff.relationships.baseline` path that is not a file fails the run with `baseline not found: <path>` (R40).
- A baseline whose `report.json` has `"smoke": true` fails the run with `baseline is a smoke run: <path>` (R32); a smoke run is not the R29 baseline.
- `precision` and `tolerance` compare each `(corpus, kind)` pair on its own (R41). A pair the baseline lacks does not fail the run; the report lists it under `new in this run`, so a new category or language is visible without blocking.

### Report contents

- `report.md` lists every wrong pair, forced pair, wrong usage, missed relationship, missed usage and misclassified declaration, each with its case, both endpoints or both classifications, and the label's path (R18, R42, R44). Its wrong and forced pairs are under the heading `Wrong and forced relationships`; missed relationships and usages have their own sections.
- `report.md` and `report.json` give the metrics per corpus, per split, per category and overall.
- The `report.md` header shows the engine version and the reference pass version next to the IDE build, Scala plugin version and corpus revision.
- `report.json` has the engine version, the reference pass version, the IDE build, the Scala plugin version, the corpus revision, gate mode, smoke flag and shortfalls, the metrics per corpus, per split, per category and overall, the skipped and unlabelled files, and every list of `report.md` (R19, R20; the pass version arrives with [relationships/04](04-rename-grouping.md) R18).
- The corpus revision is a hash of the corpus list and of every case and label file it reaches, in path order, plus the commit hash of each real-commit line's clone (R43). Editing a checked-in real-commit label or moving a clone's HEAD changes it.
- The R29 baseline's `report.json` is checked in at `src/test/testData/relationships-baseline/report.json`, so a later gate compares against a file rather than a rerun (R21, R29, R40).

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
| R12–R18 | Report tests over a small fixed corpus with known true and false positives, including a per-category row and `n/a` for a kind with no reported pairs |
| R19–R21 | The report header test, including `referencePassVersion`, and a comparison with a saved baseline (the pass version arrives with [relationships/04](04-rename-grouping.md) R18) |
| R22–R27 | One test per gate mode with a baseline that should pass and one that should fail |
| R28 | Two runs on the same cases give equal reports apart from times |
| R29 | Manual: the baseline section in VALIDATION.md comes from a run with no shortfall; a test checks that `src/test/testData/relationships-baseline/report.json` exists, is not a smoke run and carries the revision VALIDATION.md records |
| R30–R32 | `matrixFailures` on a corpus one case short of a minimum gives a smoke report, and the run's own `report.json` is refused as a baseline |
| R33 | A corpus with one Scala 2 and one Scala 3 case gives two rows and two sets of minimums; an unlabelled Scala file is counted under the dialect it parses in, and an unknown dialect is listed rather than filed as Scala 2 |
| R34–R36 | A test Git repository built in the test, with one labelled and one unlabelled file in a commit, scores the first and lists the second; a renamed file is a delete plus an add; a non-ASCII path is read (R10: a fully labelled commit counts once, and a commit that only adds a file still counts when its modified file is labelled) |
| R37 | Drafts for the test repository have `reviewed: false` and are not scored until the flag is changed |
| R38 | A rename and move against labels listing both kinds, one kind, and the other kind; an extraction link is counted once |
| R39 | A forced pair is counted as forced and leaves precision unchanged |
| R40 | A missing baseline path fails the run |
| R41 | A baseline missing one `(corpus, kind)` pair passes and lists it as new; a lower precision in one pair fails |
| R42 | A case with a wrong classification is listed with both values; two same-identity changes cannot claim one node |
| R43 | Editing a case label, editing a real-commit label, or moving a clone's HEAD changes the corpus revision |
| R44 | `RelationshipBenchmarkTest`: `ReferencePass.runForBenchmark` runs without a file; the gate corpus has 440 cases and 105 renames (85 high, 20 medium, 0 low), including the 16 usage-grouping fixtures; every rename relationship in the 120 real-commit labels has a `usages` list; a usage label matches a `Reference`; a reference with no matching pair fails `no-new-wrong-pairs`; an absent `usages` lists the rename as `usage-unlabelled`, fails completeness in a gate run and does not score it; the report records `referencePassVersion` |
| Adapter (not a requirement) | A cross-container move names its old container from the old tree; a provider that throws on the second old-side parse falls back to the new-side path and the run still reports the file; two extractions that differ only by range stay two links; the signature simplification is pinned by a test |

## Open questions

- When, if ever, does the corpus include relationships across files?
