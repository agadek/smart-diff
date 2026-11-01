# Relationship Benchmark

Status: proposal, 2026-10-30; specified in [relationships/01](specs/relationships/01-relationship-benchmark.md) (2026-11-01), which also settles Q1, Q3, Q5 and Q7 as proposed there. The label format, metrics, gates and harness are in its [details file](specs/relationships/01-relationship-benchmark-details.md), which takes precedence over §3 to §9 below. This benchmark supports the [structural relationships and refactoring-awareness program](STRUCTURAL-RELATIONSHIPS.md). It measures matching quality before and after every matcher change; it does not define the matcher itself.

Related: [product scope](specs/README.md#product-scope), [technical specification](TECHNICAL-SPECIFICATION.md), [roadmap](ROADMAP.md), [extraction detection](EXTRACTION-DETECTION.md), [validation](VALIDATION.md), [structural relationships](STRUCTURAL-RELATIONSHIPS.md).

## 1. Problem

The existing tests check expected output and flag suspicious results, but they do not answer:

- How many real relationships were found?
- How many reported relationships were wrong?
- How often are two similar declarations incorrectly paired?
- How many ambiguous cases were forced into a match?
- Did a matcher change improve recall without damaging precision?
- What did the change cost in latency and source coverage?

Without a labelled benchmark, matcher changes are judged by anecdote and fixture churn. This specification defines a reproducible ground truth and the metrics used to accept or reject a change.

## 2. Scope

In scope:

- per-file comparisons;
- Java, Kotlin, Scala 2 and Scala 3;
- declaration renames, moves, kind changes, signature changes, extraction and inlining;
- duplicate bodies, overloads and ambiguous candidates;
- formatting-only, comment-only and string changes;
- malformed and partial source;
- large files and performance-sensitive cases;
- the existing golden fixtures, contract corpus and corpus validation as inputs.

Later, after an explicit scope decision:

- cross-file moves and commit-level refactoring groups;
- local-variable renames;
- statement-level moves;
- reference-group precision across files.

Out of scope:

- behavioural equivalence;
- runtime performance of the IDE;
- comparing SMART Diff with other products' features;
- LLM-generated labels or comparisons.

## 3. Case matrix

Every case is one old/new text pair with expected relationships and change classifications. The matrix is a minimum: a language that supports a case must have at least one fixture for it.

| Case | What it exercises |
| --- | --- |
| Method/function rename | One declaration relationship, same body |
| Class/type rename | Container rename, members and constructor follow |
| Field/property rename | Name-only declaration relationship |
| Rename plus body change | Combined relationship and `CHANGED` content |
| Rename plus signature change | Combined relationship and signature evidence |
| Move within a file | Container or order change |
| Move plus rename | Combined relationship |
| Move plus body change | Combined relationship and `CHANGED` content |
| Kind change | Class/trait/interface or `def`/`val` family change |
| Signature change | Overload pairing and M3 behavior |
| Overloads with the same body | Ambiguity; must not force a pair |
| Duplicate bodies in one container | Ambiguity; position/name/evidence must decide or leave uncertain |
| Extraction | Added method/function from changed source code |
| Extraction from several sources | One target, many origins |
| Extraction to several targets | One source, many targets |
| Inlining | Removed declaration whose code now sits in a changed declaration |
| Statement reorder | Expected unsupported or later; must remain visible as ordinary changes |
| Formatting-only | No relationship, `FORMATTING_ONLY` classification |
| Comment-only | No relationship, `CHANGED`/comment classification |
| String literal change | No relationship, `CHANGED` content |
| Parse error on one side | Partial/fallback behavior, nothing hidden |
| Malformed both sides | `FAILED`/fallback rather than an exception |
| Empty and identical files | No reported changes |
| Large file | Latency and source coverage under limits |
| Ambiguous rename group | Several equally plausible candidates |

## 4. Label schema

A label names the expected relationships and classifications. The final format is an implementation detail; the required information is:

```yaml
case: rename-method
language: JAVA
old: old.java
new: new.java
expected:
  relationships:
    - kinds: [RENAME]
      old: { subject: "method total()", range: "18:24" }
      new: { subject: "method sum()", range: "18:24" }
      confidence: HIGH
  changes:
    - subject: "method sum()"
      presence: MATCHED
      content: UNCHANGED
  ambiguous: []
  unanalyzed: []
```

Rules:

- a relationship label is a pair (or link) of endpoint identities, not just names;
- ranges are offsets or stable line/column references, never line numbers that shift with edits;
- duplicate names use a path, kind and range to identify the endpoint;
- a case may label expected ambiguity instead of a relationship;
- a wrong-but-plausible candidate is recorded in a `notes` field so reviewers can see why the case exists;
- every fixture is checked for nothing hidden, as the golden fixtures are today.

Labels are stored with the fixtures under `src/test/testData/<language>/relationships/` or an adjacent metadata file. The final layout is decided with the harness.

## 5. Metrics

All metrics are reported per language, per case category and overall.

### 5.1 Relationship quality

For each relationship type:

- **True positive (TP)**: expected relationship reported with the expected endpoints.
- **False positive (FP)**: reported relationship that is not in the label, or has the wrong endpoint.
- **False negative (FN)**: expected relationship not reported.
- **Precision** = TP / (TP + FP).
- **Recall** = TP / (TP + FN).
- **Incorrect-match rate** = wrong reported pairs / all reported pairs.
- **Uncertain rate** = expected relationships left as candidates or uncertain; reported separately from misses.

Endpoint matching uses subject identity, kind and range, not only the displayed name. A relationship with the right name but the wrong declaration is a false positive.

### 5.2 Change classification

- Accuracy of `presence` (`MATCHED`, `ADDED`, `REMOVED`, `UNCERTAIN`).
- Accuracy of `content` (`UNCHANGED`, `FORMATTING_ONLY`, `CHANGED`, `UNANALYZED`).
- Accuracy of `moved`, `signatureChanged` and `kindChangedFrom` when applicable.
- Rate of combined changes represented correctly.

### 5.3 Ambiguity

- Number of labelled ambiguous cases that produce a forced pair (must be zero).
- Number of labelled ambiguous cases that remain visible as uncertain or low-confidence candidates.
- Unresolved-candidate rate for duplicate bodies and overloads.

### 5.4 Source coverage

- **Coverage** = share of differing characters that lie inside a reported change, a reported file region or an unanalyzed range, using the same rule as the nothing-hidden contract.
- **Nothing-hidden violations**: must be zero for every `ANALYZED`/`PARTIAL` result.
- **Fallback rate**: files that return `UNSUPPORTED`, `TOO_LARGE` or `FAILED`.
- **Unanalyzed rate**: declarations with `UNANALYZED` content.

### 5.5 Performance

- Median, p95 and slowest analysis time per language.
- Number of declarations and tokens in the case.
- Comparison with the current limits in [TECHNICAL-SPECIFICATION.md](TECHNICAL-SPECIFICATION.md) §9.3.
- Benchmark machine and JVM/IDE version recorded with the report.

### 5.6 Determinism

- Two runs on the same engine produce equal results.
- A fresh engine produces the same result as a reused engine.
- Relationship order is stable.

## 6. Baseline and acceptance gates

1. Run the benchmark against the current engine before any matcher change.
2. Publish the baseline report with the corpus, IDE build, parser versions and thresholds.
3. Set the acceptance gates from that baseline. The first gate is non-regression:

- precision does not fall below the baseline;
- incorrect-match rate does not rise above the baseline;
- nothing-hidden violations stay at zero;
- latency stays within the technical limits;
- every malformed case still falls back without throwing.

4. A matcher change may trade a small, reviewed recall loss for a larger precision gain, or the reverse, only when the benchmark report explains the trade-off and the release criteria permit it.
5. No change is accepted on fixture churn alone. The report must show the metric movement.
6. The gate is configurable (decided 2026-10-30). A Gradle property, `-Psmartdiff.relationships.gate=<mode>`, picks one of:

| Mode | Fails when |
| --- | --- |
| `no-new-wrong-pairs` (default) | any wrong pair or forced ambiguous pair that the baseline does not have |
| `precision` | precision falls below the baseline |
| `tolerance` | precision or recall falls more than `-Psmartdiff.relationships.tolerance=<points>` below the baseline |

Nothing-hidden violations, latency within the limits and malformed input falling back without throwing apply in every mode. The report records the mode used.

Cross-file metrics are added only when the cross-file scope is approved.

## 7. Harness

The benchmark should reuse the existing infrastructure where possible:

- golden fixtures for expected output;
- `ComparisonEngineContract` for the shared rules;
- `CorpusValidationTest` for real-commit sampling and flags;
- `NothingHidden` for coverage;
- `LimitsBenchmarkTest` for large-file timing;
- the existing `Rendering` helpers for readable reports.

A new opt-in test, for example `RelationshipBenchmarkTest`, should:

- read a labelled case directory or a list of repositories;
- run the engine through `compareSafely` with the default limits;
- compare relationships and classifications with the labels;
- write `build/reports/smartdiff-relationships/` with `summary.tsv`, `report.md` and a machine-readable JSON file;
- print a short summary for the build log.

The benchmark runs on demand only (decided 2026-10-30): CI skips it, like the existing validation and limits corpora. It runs before a release and on any pull request that changes matching, which attaches its report. Both the labelled fixtures and the real-commit corpus are opt-in through Gradle properties:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=<list>
```

The report records the corpus commit or hashes so a result can be reproduced.

## 8. Labelling protocol

- Labels are written against a pinned revision pair.
- A second reviewer checks every relationship label that affects a release gate.
- Disagreements are recorded; they are not silently resolved by the implementer.
- A label may say “ambiguous” or “unsupported” when no single relationship is correct.
- Fixtures derived from a bug report keep the report's context and the failing behaviour.
- Do not tune thresholds against the same cases used to report the final result without recording that the corpus was used for tuning.

## 9. Reporting

The report must contain:

- corpus identity and versions;
- per-case expected vs actual;
- per-language and overall precision/recall/incorrect-match;
- ambiguity and forced-pair counts;
- classification accuracy;
- latency table;
- coverage and nothing-hidden results;
- fallback and unanalyzed counts;
- a short list of every false positive and false negative for review;
- a comparison with the previous baseline.

## 10. Acceptance criteria

- The labelled corpus covers every case category in §3 for Java and Kotlin, and the Scala categories the provider supports.
- Every expected relationship is identified by endpoints, not just names.
- The baseline report exists before the matcher changes start.
- Every matcher change reports precision, recall, incorrect-match, ambiguity and latency against that baseline.
- Ambiguous fixtures never produce a forced pair.
- Malformed fixtures never throw and remain usable in the native diff.
- Every analysed result passes nothing-hidden.
- A release gate exists for no precision regression, no incorrect-match regression, zero hidden differences and bounded latency.
- The report can be regenerated from the repository and pinned corpus without relying on a developer's local state.

## 11. Open questions

- **Q1 Label format.** YAML, JSON or TSV beside the fixture?
- **Q2 Endpoint identity.** Is the stable identity a source range, a path plus kind plus name, or both? *Decided 2026-10-30: path, kind, name and signature, as the outline names a declaration; a range is added only to tell duplicates apart.*
- **Q3 Corpus size.** How many real commits and labelled fixtures are enough for a release gate?
- **Q4 Cross-file labels.** When, if ever, does the corpus include cross-file moves and reference groups?
- **Q5 Tuning split.** Should the corpus be split into a tuning set and a held-out set?
- **Q6 Language order.** Which language's reference cases enter the benchmark first?
- **Q7 Performance budget.** Is the current 5 s limit the gate, or a per-language budget below it?
- **Q8 Confidence labels.** Does the benchmark label an expected confidence, or only expected high/medium/low evidence?
- **Q9 Source-coverage denominator.** Is coverage measured over all characters, all differing characters, or only characters in declarations?
- **Q10 CI policy.** How much of the benchmark runs on every pull request versus before release? *Decided 2026-10-30: on demand only; see §7.*

## 12. Related documents

- [Structural relationships and refactoring awareness](STRUCTURAL-RELATIONSHIPS.md)
- [Technical specification](TECHNICAL-SPECIFICATION.md)
- [Validation](VALIDATION.md)
- [Extraction detection](EXTRACTION-DETECTION.md)
- [Glossary](GLOSSARY.md)

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Define a versioned label schema that works before and after the relationship model; add a “current engine adapter” section mapping ChangeNode fields to labels.
- Clarify how precision/recall count extraction links and one-to-many groups; pair-level metrics alone are insufficient.
- NothingHidden is a boolean alignment check, not a coverage ratio. Define the source-coverage numerator and denominator explicitly.
- The label example uses an ambiguous range format (“18:24”); use offsets or stable line/column plus a fixture hash.
- Make confidence optional in labels until Q1/Q8 are decided.
- State that thresholds are fixed after the first baseline and that the initial gate is non-regression.
- Specify the fast subset size and time budget for every pull request.
- List Scala 2 and Scala 3 separately, and keep cross-file labels out until the scope ADR exists.

> **Model author:** Claude Opus 5.5 · 2026-10-30. These are review notes; the normative text above is unchanged.

- The baseline (STRUCTURAL-RELATIONSHIPS.md phase 1) runs before the relationship model exists (phase 2). Specify the adapter that derives relationships from today's result: `renamedFrom` to rename, `moved` to move, `kindChangedFrom` to kind change, `extractedFrom`/`extractedTo` to extract. Without it the baseline cannot be computed.
- The comment-only case expects a "`CHANGED`/comment classification", but `Content` has only `UNCHANGED`, `FORMATTING_ONLY`, `CHANGED` and `UNANALYZED`. Expect `CHANGED`.
- "Malformed both sides → `FAILED`" is too strict: TECHNICAL-SPECIFICATION.md §6.7 gives `FAILED` only when no declarations were found; otherwise the result is `PARTIAL`.
- The label example's `range: "18:24"` could be offsets or line and column, while Q2 (endpoint identity) is still open. Decide Q2 before labelling. Done 2026-10-30.
- §5.4 already defines coverage over differing characters, so Q9 is answered or §5.4 is provisional; say which.
- "Precision does not fall below the baseline" fails on a single extra false positive in a small corpus. Gate on zero new wrong pairs plus a tolerance, or set a minimum corpus size (Q3).
- Test data is organized by language (`java`, `kotlin`, `scala2`, `scala3`); fixtures go under `<language>/relationships/`.
- Inlining is not implemented, so its recall starts at zero; state that its gate applies from STRUCTURAL-RELATIONSHIPS.md phase 5.
- §7 runs a fast subset on every pull request while Q10 asks whether to. Pick one. Done 2026-10-30.

> **Model author:** OpenAI Codex (GPT-6) · 2026-10-30. Review comments; these do not change benchmark requirements.

- The label schema needs a closed-world rule: say whether every plausible candidate is labelled positive, negative, or ambiguous. Otherwise precision computed on a curated set of positive examples can miss unlabelled false positives.
- Specify whether results are micro-averaged across pairs or macro-averaged by file/language/category, and publish both if useful. Overall scores can otherwise be dominated by the largest language or case family.
- §5.4 defines coverage over differing characters, but that does not measure classification quality of unchanged regions or distinguish useful range coverage from a broad file-region report. Keep coverage as a safety metric and name it accordingly.
