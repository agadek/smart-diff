# Relationship benchmark: details

Status: proposed, 2026-11-01

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
language: JAVA
split: held-out            # or tuning
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

- An endpoint is path, kind, name and signature, as the outline names it. A `range` (`start..end` offsets) is added only when two declarations would have the same identity.
- Confidence is optional in a label; it is compared only when given.
- The label set is closed: every relationship the engine reports that is not listed is wrong, unless the case lists it under `ambiguous`.
- `ambiguous` lists the declarations for which no pair is correct; any pair reported for them is a forced pair.

### Case categories

Every category needs at least 3 cases per language where the language has the construct: method rename, class rename, field rename, rename with body change, rename with signature change, move in the file, move with rename, move with body change, kind change, signature change, overloads with the same body, duplicate bodies in one container, extraction, extraction from several sources, extraction to several targets, inlining, statement reorder, formatting-only, comment-only, string literal change, parse error on one side, malformed on both sides, empty and identical files, large file, ambiguous rename group.

Expected classifications follow the current model: a comment-only change is `CHANGED`; malformed input on both sides is `FAILED` only when no declaration was found, otherwise `PARTIAL`.

### Metrics

- Per relationship kind: true positives, false positives, false negatives, precision, recall, and the share of wrong pairs among reported pairs. Endpoints are compared by identity, so the right name on the wrong declaration is a false positive.
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
| R29 | Manual: the baseline section in VALIDATION.md |

## Open questions

- Does the benchmark label an expected confidence, or only the expected evidence?
- When, if ever, does the corpus include relationships across files?
