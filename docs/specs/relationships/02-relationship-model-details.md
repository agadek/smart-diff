# Relationship model: details

Status: proposed, 2026-11-01

Feature: [Relationship model](02-relationship-model.md)

## Design

### Data

```kotlin
data class ComparisonResult(
    // existing fields …
    val relationships: List<Relationship> = emptyList(),
)

data class Relationship(
    val kinds: Set<RelationshipKind>,        // RENAME, MOVE, KIND_CHANGE, EXTRACT, INLINE
    val old: List<RelationshipEndpoint>,     // empty when the old side has no endpoint
    val new: List<RelationshipEndpoint>,
    val confidence: Confidence,              // HIGH, MEDIUM, LOW
    val evidence: List<Evidence>,
)

data class RelationshipEndpoint(val subject: Subject, val range: TextRange)

sealed interface Evidence {
    data class Name(val old: String, val new: String, val similarity: Double) : Evidence
    data class Content(val shared: Int, val total: Int, val oldRange: TextRange, val newRange: TextRange) : Evidence
    data class Structure(val oldPath: String, val newPath: String) : Evidence
    data class Position(val oldIndex: Int, val newIndex: Int) : Evidence
    data class Score(val value: Double) : Evidence
}

data class ConfidencePolicy(val high: Double = 0.85, val medium: Double = 0.7) {
    companion object { val DEFAULT = ConfidencePolicy() }
}
```

`ComparisonRequest` gains `confidence: ConfidencePolicy = ConfidencePolicy.DEFAULT`, next to `limits`. The registry key `smartdiff.relationships.confidence` holds the two thresholds as `high,medium` and is read when the request is built. Tests and the benchmark pass a policy directly.

- A relationship has at least one endpoint on each side, except inlining and extraction links, which name the declaration on one side and the code range on the other.
- Content and signature changes stay `ChangeNode` attributes. They are not relationship kinds.
- A relationship is not a presence: it never replaces `MATCHED`, `ADDED`, `REMOVED` or `UNCERTAIN`.

### Computed fields

`renamedFrom`, `moved`, `kindChangedFrom`, `extractedFrom` and `extractedTo` keep their types but are computed from `relationships` when the result is built, then marked deprecated. `DeclarationMatcher` and `ExtractionDetector` write relationships only. Because the outline, the Changes tree and the merge outline read the same fields as before, they need no change in this step.

### What each confidence shows

| Confidence | Today's equivalent | Outline |
| --- | --- | --- |
| High | A rule M1 to M4b match | Paired |
| Medium | A rename candidate (rule M5) | Paired as a candidate |
| Low | Not produced today | Both sides stay added and removed |

With today's matcher every relationship is high or medium, so the outline does not change.

### Contract rules

`ComparisonEngineContract` gains these checks for every engine:

- every endpoint names a subject in the result tree, on its own side;
- every endpoint and evidence range lies inside its side's text;
- an extraction or inlining link is the only source of the fields of both its ends;
- relationships are sorted by the old side's first endpoint, then the new side's, then kind; evidence is sorted by type;
- nothing-hidden gives the same answer with relationships removed.

### Engine version

`ComparisonEngine.version` adds the Scala plugin's version when the Scala provider is loaded, because its parser can change a result without a platform update.

### Failure behaviour

A `PARTIAL` result has relationships only between analyzed declarations; an `UNSUPPORTED`, `TOO_LARGE` or `FAILED` result has none.

### Thresholds and reasons

- `high = 0.85` and `medium = 0.7`: medium matches today's 70% rename threshold, so rename candidates stay as they are. High is a starting value, set from the benchmark baseline before relationships/03.

## Tests

| Requirement | Check |
| --- | --- |
| R1–R6 | Model tests in a new `ComparisonResultTest`: defaults, kinds, evidence and the score kept in evidence |
| R7, R8 | `DeclarationEngineTest`: every computed field equals the field the old code set, over the golden fixtures |
| R9, R10 | `GoldenFixturesTest`, `MergeFixturesTest` and `OutlineModelTest` pass with no fixture change |
| R11–R13 | `DeclarationMatcherTest` with a policy that moves one pair across each threshold |
| R14, R15 | A request test for the policy, and a registry test for the key |
| R16–R20, R23 | New checks in `ComparisonEngineContract`, run by `ComparisonEngineContractTest` and `LineEngineContractTest` |
| R21 | A partial-result fixture with a parse error |
| R22 | `LineEngineContractTest` passes with an empty list |
| R24 | An engine version test with and without the Scala plugin |
