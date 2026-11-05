# Relationship model: details

Status: implemented, 2026-11-04; reference evidence (R5, R17, R19 and R34) arrives with [rename grouping](04-rename-grouping.md), 2026-11-05.

Feature: [Relationship model](02-relationship-model.md)

## Design

This step changes where today's facts live, not what they are. The matcher and the extraction detector keep finding the same pairs; they write them as relationships, and the old `ChangeNode` fields are computed from those relationships. The [2026-11-02 benchmark baseline](../../VALIDATION.md#2026-11-02-relationship-benchmark-baseline) measured today's engine through an adapter; after this step the same run reads the engine's own list and must give the same scores.

### Data

```kotlin
data class ComparisonResult(
    // existing fields …
    val relationships: List<Relationship> = emptyList(),
)

data class Relationship(
    val kinds: Set<RelationshipKind>,        // RENAME, MOVE, KIND_CHANGE, EXTRACT, INLINE, in this order
    val old: List<RelationshipEndpoint>,
    val new: List<RelationshipEndpoint>,
    val confidence: Confidence,              // HIGH, MEDIUM, LOW
    val evidence: List<Evidence>,
)

/** [range] is the declaration's whole range on its side, which identifies its node; a subject is not unique in a file. */
data class RelationshipEndpoint(val subject: Subject, val range: TextRange)

sealed interface Evidence {
    data class Name(val old: String, val new: String) : Evidence
    data class Content(val shared: Int, val total: Int, val oldRange: TextRange, val newRange: TextRange) : Evidence
    data class Structure(val oldPath: String, val newPath: String, val oldKind: DeclarationKind?, val newKind: DeclarationKind?) : Evidence
    data class Position(val oldIndex: Int, val newIndex: Int) : Evidence
    data class Reference(val symbol: Subject.Declaration, val oldRange: TextRange, val newRange: TextRange) : Evidence
    data class Score(val value: Double) : Evidence
}

data class ConfidencePolicy(val high: Double = 0.82, val medium: Double = 0.7) {
    init { require(medium in 0.0..1.0 && high in medium..1.0) { "invalid confidence policy: $this" } }
    companion object { val DEFAULT = ConfidencePolicy() }
}
```

- Every relationship has at least one endpoint on each side, and every endpoint is a declaration (R2, R16). An inlining, from [relationships/05](05-inlining-detection.md), follows the same rule: the removed declaration on the old side, the declarations it was inlined into on the new side.
- An endpoint's `subject` is the subject of the node on its own side: the old subject for an old endpoint, so a rename's old endpoint carries the old name and a kind change's old endpoint the old kind. The benchmark adapter rebuilt the old subject from `renamedFrom` and `kindChangedFrom`; the model stores it.
- `Content` is the only evidence type the engine produces with ranges, because it is the only one that refers to source text: `oldRange` is the extracted code's old range and `newRange` is the target declaration's range (R5). `Reference` is the second range type; the [reference pass](04-rename-grouping.md) adds it to a rename relationship, and its `oldRange` and `newRange` are one usage on each side. `Name`, `Structure`, `Position` and `Score` carry no ranges. Every `Content` in this step has a target range, so `newRange` is not nullable; a later content evidence with no new-side range would need a spec change.
- `Reference.symbol` is the new-side declaration subject the usage resolves to, so it is one of the relationship's new endpoints' subjects. Reference evidence never adds an endpoint: a usage is not a declaration and has no node in the result tree, so R16 stays as it is. The reference evidence and R34 arrive with [rename grouping](04-rename-grouping.md); until that step the engine and the contract see none.
- `RelationshipKind`, `Confidence`, `Relationship`, `RelationshipEndpoint`, `Evidence` and `ConfidencePolicy` go in `engine.api`, next to `ComparisonResult`, so every engine and the UI see the same types.
- Content and signature changes stay `ChangeNode` attributes. They are not relationship kinds.
- A relationship is not a presence: it never replaces `MATCHED`, `ADDED`, `REMOVED` or `UNCERTAIN`.

### Today's relationships

`DeclarationMatcher` and `ExtractionDetector` produce these, and nothing else, in this step (R25 to R27):

| Source today | Kinds | Endpoints | Confidence | Evidence |
| --- | --- | --- | --- | --- |
| Rule M5 rename candidate, a class's constructor included | `RENAME`, plus `MOVE` when the node's order among siblings changed | The old and the new declaration | High or medium, from the policy and its score (below) | `Name`, `Score` (the rename similarity), `Position`; `Structure` when moved |
| A match by rules M1 to M4b that is moved | `MOVE` | The old and the new declaration | High | `Structure` (old and new container path), `Position` (index among siblings) |
| Rule M4b kind change | `KIND_CHANGE`, plus `MOVE` when its order among siblings changed | The old and the new declaration | High | `Structure` (old and new kind) |
| Extraction link | `EXTRACT` | The source on the old side, the extraction on the new side; one relationship per source and target pair | High | `Content` (tiled tokens as `shared`, the target's tokens as `total`, the extracted code's old range, and the target's range), `Score` (the share) |

- `moved` covers both a container change and a change of order among siblings, as today. Both give `MOVE`.
- A relationship with several kinds takes the lowest of its kinds' confidence (R26), so a renamed and reordered declaration takes the rename's confidence.
- Overload pairs from rule M3 are same-name matches with a changed signature. They are not relationships, as today.
- Uncertain nodes from rule M4 keep `candidates` (R29). Their ambiguity stays a presence state: [relationships/03](03-relationship-matching.md) R6 adds low-confidence relationships for ties in its own assignment, not for rule M4 uncertainty.
- The engine adds no `Reference` evidence (R34). Only the [reference pass](04-rename-grouping.md) adds it, to a `RENAME` relationship, after the engine returns.

### Confidence policy

`ComparisonRequest` gains `confidence: ConfidencePolicy = ConfidencePolicy.DEFAULT`, next to `limits` (R14).

- A scored rename is high at `score >= high`, medium at `score >= medium`, and low below. A tied option is low whatever its score, even at or above `high`, because no pair was taken ([relationships/03](03-relationship-matching.md) R6). Today only rename candidates are scored; moves, kind changes and extractions are high by rule (R25).
- A rename pair needs `score >= RENAME_THRESHOLD` (0.7). The matcher keeps that fixed pairing floor; the policy classifies each pair the matcher forms. A pair the policy calls low still occupies both declarations in the assignment, so changing the policy never changes which pairs are found. With the default policy that gives the same pairs as before (R9). Raising `medium` in a test or through the registry makes a pair between 0.7 and `medium` low, which leaves both declarations added and removed while the relationship stays in the list (R13); lowering `medium` does not form new pairs, because the floor stays at 0.7. A low pair also counts as an unmatched container for its members: they keep the pairs M1 to M5 found, get a `MOVE` relationship and are placed under the matched new container, instead of appearing unchanged under an added container. The threshold changes only the pair's own confidence, computed fields and presence, and its members' move flag; it never changes which declarations are paired ([relationships/03](03-relationship-matching.md) R19).
- A rename that scores at least `high` is high. It still shows as a rename candidate (R28), because no other label exists; the outline reads `renamedFrom`, which a high and a medium rename both set. This is the decided label for high confidence as well ([relationships/03](03-relationship-matching.md), 2026-11-04).
- A low rename comes from a tie under the default policy ([relationships/03](03-relationship-matching.md) R6), or from a pair a raised `medium` threshold moves below medium, in tests or through the registry. It leaves both declarations added and removed, and the relationship stays in the list linking them (R13). None of its computed fields is set, a composite included. A low pair still occupies both declarations in the assignment, so the same pairs are found whatever the policy; only the confidence, the computed fields and the `MOVE` flag of its members change.
- The registry key `smartdiff.relationships.confidence` is declared in `plugin.xml` with the default `0.82,0.7` and holds `high,medium` (R15). It is read when a request is built, next to the analysis limits in `SmartDiffSettings.limits`, so the outline, the Changes tree and the merge outline use the same policy. A value that does not parse as two numbers with `0 <= medium <= high <= 1` logs one warning and the default policy is used (R30). The Changes tree cache key and clear path cover `SmartDiffSettings` but not this registry key, so a threshold change can leave summaries computed under the old policy; the implementation must include the policy in the key or clear the cache when it changes.
- Tests and the benchmark pass a policy directly; the benchmark records the policy it used in `report.json`.

### Computed fields

`DeclarationAnalysis` builds the relationship list first, then sets `renamedFrom`, `moved`, `kindChangedFrom`, `extractedFrom` and `extractedTo` from it while it builds the nodes (R7, R8):

1. The matcher returns its pairs and, beside them, a relationship for each rename, move and kind change. The extraction detector returns relationships instead of `Extraction` values.
2. Each relationship is indexed by its endpoints' ranges on each side.
3. When a node is built, its fields come only from high or medium relationships whose endpoints are the node's own ranges: `renamedFrom` from a `RENAME`, `moved` from `MOVE`, `kindChangedFrom` from `KIND_CHANGE`, and `extractedFrom` and `extractedTo` from `EXTRACT` (point 4). The rename, move and kind change fields follow the relationships' order. A low relationship sets none of these fields, a composite included, so its declarations stay added and removed (R13). Its members are still placed under their matched new container, and their own `MOVE` relationships are computed as if the low pair were not a container match.
4. `extractedFrom` and `extractedTo` of a link's two ends come from the same relationship (R18): the `Origin` on the target carries the old range the relationship's source endpoint names, plus the `Content` evidence's old range. Its subject is the source as the outline shows it, the matched node's new-side subject, so the `extracted from …` note keeps the name and signature it shows today (R9); the endpoint still names the old-side declaration. A target with several sources lists them as the extraction detector ordered them, largest covered share first and then in old-side source order, which the `Content` and `Score` evidence carry; the list is not re-sorted into the relationship order, so the outline names them as it does today (R9).

The fields keep their types and are marked `@Deprecated` with a pointer to `relationships`. The outline, the Changes tree, `MergeJoin` and the merge outline read the same fields as before, so they need no change in this step.

### Order

Relationships are sorted by the start of the first old endpoint, then the start of the first new endpoint, then the first kind in enum order. Endpoints are sorted by range start. Kinds are built as an `EnumSet`, so iteration is in enum order by construction rather than by insertion order. Evidence is sorted by type in the order of the sealed interface: `Name`, `Content`, `Structure`, `Position`, `Reference`, `Score` (R19). Several `Reference` entries on one relationship are sorted by old range start, then new range start.

### Contract rules

`ComparisonEngineContract` gains these checks for every engine (R23):

- every endpoint names a subject in the result tree, on its own side, at its range (R16);
- every endpoint and content or reference evidence range lies inside its side's text (R17);
- a reference's `symbol` equals one of the relationship's new endpoints' subjects, and `Reference` evidence appears only on a `RENAME` (R5, R16);
- an extraction's `extractedFrom` and `extractedTo` fields agree with one `EXTRACT` relationship, and no relationship is listed twice (R18);
- the list is in the order above, and two runs on the same request give equal lists (R19);
- nothing-hidden gives the same answer with the relationships removed (R20).

The line engine returns an empty list and passes every check (R22).

### Engine version

`DeclarationEngine.VERSION` goes from 6 to 7, because the result now carries relationships. When the Scala provider is loaded, `version` also adds the Scala plugin's version, for example `9 (251.26927.53; Scala 2025.1.32)`, because its parser can change a result without a platform update (R24). `scalaPluginVersion()` reads every `META-INF/plugin.xml` resource on the Scala language's classloader and keeps the version from the descriptor whose `<id>` is `org.intellij.scala`; one resource is not enough, because in a test every plugin shares one classloader and the first descriptor belongs to another plugin. When no descriptor gives a usable `<version>` for the Scala plugin, whether it is missing, another plugin's, read fails or has no `<version>`, the version reads `Scala unknown` and the lookup logs a debug message with the reason. The benchmark and validation reports already record `version`. The Changes tree cache (`ChangesSummaryService`) lives for one IDE session and keys on the engine id, not the engine version (the key also has each file's content version), so a Scala plugin change that can alter relationships needs an IDE restart before cached summaries are trusted; this step does not clear the cache on plugin load or unload.

### Failure behaviour

A `PARTIAL` result has relationships only between analyzed declarations; a declaration inside an unanalyzed range is never an endpoint. An `UNSUPPORTED`, `TOO_LARGE` or `FAILED` result has none (R21).

### Benchmark

The benchmark's `actualRelationships` adapter is removed (R31). `RelationshipBenchmark` reads `ComparisonResult.relationships`, maps each high or medium relationship to one benchmark relationship per kind, as the adapter did, and keeps the old-side container path from the old declaration tree. A low relationship is not a pair: it is counted in the confidence table and never as a true positive, false positive or forced pair (R33; [relationships/03](03-relationship-matching.md)).

- A forced pair is a relationship reported for a declaration the label marks ambiguous; a low relationship never pairs its declarations, so the ambiguous-case rule does not make it forced either.
- The exact-duplicate drop in `deduplicate` goes, so a repeated relationship is a false positive and fails the `no-new-wrong-pairs` gate (R32). The extraction de-duplication goes too, because the model has one relationship per source and target.
- `report.md` and `report.json` add, per corpus and kind, the number of reported relationships at each confidence (R33). A label's `confidence` is still not scored.
- The signature simplification of the adapter (`null`, `""` and `"()"` are one identity) stays until a label needs the difference.

The gate run, against the checked-in baseline:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs

./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json \
  -Psmartdiff.relationships.gate=tolerance \
  -Psmartdiff.relationships.tolerance=0
```

The first run must pass with no new wrong pair. The second must pass with no lower precision or recall for any corpus and kind than the baseline, because `tolerance=0` fails any drop. The model stores the real old subject, so a moved or kind-changed declaration whose signature also changed can differ from the adapter's old endpoint and move a score upward; any such difference is explained in the VALIDATION section. No MOVE or KIND_CHANGE relationship in the current corpus has old and new signatures that differ (57 across 55 label files: 50 case labels and 7 real-commit labels in 5 files), so this difference does not arise today; a later label that adds one gets its score change explained in the same way. The run is recorded in a dated section of [VALIDATION.md](../../VALIDATION.md). The baseline file is not replaced: [relationships/03](03-relationship-matching.md) compares against the 2026-11-02 baseline.

### Thresholds and reasons

- `medium = 0.7` equals today's `RENAME_THRESHOLD`, so rename candidates stay as they are.
- `high = 0.82` is the value [relationships/03](03-relationship-matching.md) tuned on the tuning split (R20); it only changes the confidence field, not what the outline shows (R28).

## Tests

| Requirement | Check |
| --- | --- |
| R1–R6 | `RelationshipModelTest` (new, `engine/api`): defaults, kinds, endpoints, evidence with ranges only on content and reference, and the score kept in evidence |
| R7, R8, R18 | `DeclarationEngineTest`: over the golden fixtures, every computed field equals the field the engine set before this step (record the old fields from `main` once into the test as expected values, or compare against a copy of the old code kept in the test) |
| R9, R10 | `GoldenFixturesTest`, `MergeFixturesTest`, `OutlineModelTest` and the Changes tree tests pass with no fixture change |
| R11–R13 | `DeclarationMatcherTest` with policies that move one rename pair across each threshold: high and medium set `renamedFrom`, low leaves it added and removed with the relationship listed, the pair set is identical under every policy, and a low composite sets none of the computed fields. `DeclarationAnalysisTest`: a low container pair stays added and removed while its members keep their pairs, get `MOVE` and stay under the new container (R13, [relationships/03](03-relationship-matching.md) R19) |
| R14 | A request test: the default policy, and a custom policy reaching the matcher |
| R15, R30 | A registry test: a valid value, an empty value, and a malformed value that keeps the default |
| R16–R20, R23 | New checks in `ComparisonEngineContract`, run by `ComparisonEngineContractTest` and `LineEngineContractTest`; one negative test per check with a hand-built bad result |
| R21 | A partial-result fixture with a parse error, and the fallback statuses |
| R22 | `LineEngineContractTest` passes with an empty list |
| R24 | `DeclarationEngineTest`: the descriptor parser over a sample Scala `plugin.xml`, a descriptor with another id and one without a version; an engine version test with and without the Scala plugin, where the live version must equal the plugin manager's Scala plugin version and must not read `unknown`; a sandbox check of the live version, recorded in [VALIDATION.md](../../VALIDATION.md) |
| R25–R27 | `DeclarationAnalysisTest`: a rename, a move, a kind change, a renamed and moved method, a kind change with an order change, a renamed class with a constructor, and an extraction with two sources |
| R28 | `OutlineModelTest`: a high and a medium rename show the same row |
| R29 | `DeclarationMatcherTest`: a rule M4 ambiguity has candidates and no relationship |
| R31–R33 | `RelationshipBenchmarkTest`: a result with a repeated relationship fails the gate; a low relationship is counted by confidence and not scored as a pair; the confidence counts appear in both reports; the full-corpus gate run above |
| R34 | `DeclarationEngineTest` and `RelationshipMatchingTest`: no engine result contains `Reference` evidence; `ReferencePassTest`: the reference pass is the only producer and adds it only to a `RENAME` |
