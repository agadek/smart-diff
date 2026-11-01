# Inlining detection: details

Status: proposed, 2026-11-01

Feature: [Inlining detection](05-inlining-detection.md)

## Design

### Where it plugs in

`ExtractionDetector` runs once more with the sides swapped:

- extraction: an added target is tiled with the removed tokens of changed sources;
- inlining: a removed declaration is tiled with the added tokens of changed targets.

A target's added tokens are the new tokens that an alignment of its old and new tokens inserts. Both passes write relationships (`EXTRACT` and `INLINE`) to the result, as [relationships/02](02-relationship-model.md) describes; the computed `inlinedInto` and `inlinedFrom` fields feed the outline.

### Thresholds

The same as extraction, for the same reasons ([EXTRACTION-DETECTION.md](../../EXTRACTION-DETECTION.md)):

| Name | Value | Meaning |
| --- | --- | --- |
| `MIN_TOKENS` | 10 | Shortest body considered |
| `MIN_TILE` | 5 | Shortest run of tokens a tile may have |
| `MIN_SHARE` | 0.65 | Share of the removed body that must be tiled |
| `MIN_REMOVED` | 0.75 | Share of the tiled tokens that must be new in the target |
| `MAX_WORK` | 20 000 000 | Token pairs per file; above it the pass is skipped |

Both passes share `MAX_WORK`, so a file costs at most twice as much as today.

### Outline

- The removed declaration's label: `inlined into <target>`, with up to 3 names, then `+N more`.
- The target's label: `code inlined from <declaration>`.
- Selecting an inlining frames the tiled ranges in the new side; selecting a target highlights the same ranges.
- The summary line adds an `inlined` count and takes it out of `removed`.

### Failure behaviour

Over `MAX_WORK` or past the time limit, the file has no inlinings; the result is still `ANALYZED`.

## Tests

| Requirement | Check |
| --- | --- |
| R1–R5 | `ExtractionDetectionTest` cases with the sides swapped, plus a short-body case |
| R6–R10 | `OutlineModelTest` labels and selection ranges |
| R11 | One-to-many and many-to-one fixtures |
| R12 | A model test for both link kinds |
| R13, R14 | `ChangesSummaryTest` and a filter test |
| R15 | Golden fixtures pass with detection on and off |
| R16 | Fixtures in Java, Kotlin, Scala 2 and Scala 3 |
| R17 | A generated file over `MAX_WORK` |
| R18 | `RelationshipBenchmarkTest` inlining cases |
