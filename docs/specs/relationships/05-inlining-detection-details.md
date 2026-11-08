# Inlining detection: details

Status: accepted, 2026-11-08

Feature: [Inlining detection](05-inlining-detection.md)

## Design

### Where it plugs in

`ExtractionDetector` runs a second pass with the sides swapped:

- extraction: an added target is tiled with the removed tokens of changed sources;
- inlining: a removed declaration is tiled with the added tokens of changed targets.

A target's **added tokens** are the new tokens that an alignment of its old and new tokens inserts; they are the
deletion mask of the reversed alignment, `removed(new, old)`. Code the old version already had never counts (R3).

The declaration analysis runs extraction first and inlining second, then builds the tree. Both passes write relationships
(`EXTRACT` and `INLINE`) to the result, as [relationships/02](02-relationship-model.md) describes. The relationship is the
source of truth: the computed `inlinedInto` and `inlinedFrom` fields of a [change node](../../GLOSSARY.md#change) come from
the `INLINE` relationships, and the outline reads the fields (R12).

An `INLINE` relationship names the removed declaration on the old side and the changed target on the new side. Its
content evidence carries the old range of the tiled removed code and the new range of the tiled added code. Its score
evidence is the covered share, as for extraction. Like an extraction, it has high confidence (R19,
[relationships/02](02-relationship-model.md) R35). It is a separate relationship from a rename, move or kind change of
the target; a target that is renamed and also takes inlined code shows both relationships, and its row keeps
`rename candidate` and `code inlined from …` (R20).

The `Content` evidence's `oldRange` is the span of the removed body's tiled tokens, and its `newRange` is the span of the
target's tiled added tokens. Selecting the removed row frames `newRange` on the new side (R9); selecting the target
highlights every `newRange` inside it (R10).

### Thresholds

The same as extraction, for the same reasons ([EXTRACTION-DETECTION.md](../../EXTRACTION-DETECTION.md)). Both passes use
the same `MAX_WORK` constant, one budget each, so a file costs at most twice as much as extraction alone:

| Name | Value | Meaning |
| --- | --- | --- |
| `MIN_TOKENS` | 10 | Shortest removed body considered |
| `MIN_TILE` | 5 | Shortest run of tokens a tile may have |
| `MIN_SHARE` | 0.65 | Share of the removed body that must be tiled |
| `MIN_REMOVED` | 0.75 | Share of the tiled tokens that must be new in the target |
| `MAX_WORK` | 20 000 000 | Token pairs per pass per file; above it the pass is skipped |

### Algorithm

1. **Removed declarations:** old-side declarations without a counterpart, of kind `METHOD` or `FUNCTION`, with a body of
   at least `MIN_TOKENS` significant tokens and token offsets.
2. **Targets:** matched declarations whose content is `CHANGED`, of a kind that owns code (`METHOD`, `FUNCTION`,
   `CONSTRUCTOR`, `INITIALIZER`, `FIELD`, `PROPERTY`), with token offsets. A pair the confidence policy makes low is
   shown as added and removed, so it is not a target.
3. **Added tokens:** for each target, align its old and new significant tokens and mark the new tokens the alignment
   inserts. A changed target with no added tokens cannot be a target.
4. **Pre-check:** skip a pair when the bags of adjacent token pairs of the removed body and the target's added tokens
   share less than half of the removed body's pairs (the extraction pass's Dice pre-check, reversed).
5. **Tiling:** tile the removed body with the target's new tokens by greedy string tiling, down to `MIN_TILE` tokens,
   through the shared index of token runs.
6. **Covered share:** the share of the removed body inside tiles must be at least `MIN_SHARE` (R1, R4, R5).
7. **Added share:** the share of tiled target tokens that are added must be at least `MIN_REMOVED` (R3).
8. **One stretch:** the target tiles must lie in one stretch of code, the span from the first to the last tile holding at
   most twice as many tokens as the tiles. The span becomes the relationship's `newRange`.
9. **Old range:** the span of the tiled removed-body tokens becomes the relationship's `oldRange`, inside the removed
   declaration's range.
10. **Order:** links are grouped by removed declaration in old-side source order; within a removed declaration they come
    largest covered share first, then in new-side target order. The result's relationship list is sorted by old start,
    then new start, then kind, as relationships/02 R19 requires (R11).

### Computed fields

- A removed node whose code was inlined gets `inlinedInto: List<Subject>`, one target per link, and a details
  `RangePair(null, newRange)` for each link.
- A matched target gets `inlinedFrom: List<Origin>`, one removed declaration per link, and a details
  `RangePair(null, newRange)` for each link. The `Origin`'s `declarationRange` is the removed declaration's old range and
  its `range` is the old range of the inlined code.
- Both fields are computed from the same high-confidence `INLINE` relationships, so they agree. A low-confidence link
  would set neither field, but every inlining is high.
- The outline uses `inlinedInto` for the note `inlined into …`, `inlinedFrom` for `code inlined from …`, and the details
  for framing and highlighting.

### Outline, summary and filter

- The removed declaration keeps `removed` and its note lists up to 3 target names, then `+N more` (R6, R8).
- The target keeps `changed` (and any `rename candidate`, `moved` or `signature changed`) and its note lists up to 3
  source names, then `+N more` (R7, R8).
- The removed row frames the first inlined `newRange` on the new side and highlights every inlined range (R9).
- The target highlights every inlined `newRange` inside it (R10).
- The summary line adds `inlined` before `removed` and takes inlining rows out of the `removed` count (R13).
- `ChangeType.REMOVED` still owns the `removed` label, so hiding removed changes hides inlining rows with no new filter
  type (R14).

### Failure behaviour

Over `MAX_WORK` in the inlining pass, the file has no inlinings; the result is still `ANALYZED` (R17). Extraction keeps
its own budget, so adding the pass cannot drop an extraction that a file reported before.

### Languages

Nothing in the detector is language-specific. It uses the declaration engine's own tokens, bodies and offsets, which
Java, Kotlin and Scala 2/3 providers already supply, so detection works the same way in all four (R16).

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `InliningDetectionTest` `a removed helper is linked to the changed caller it was inlined into`; golden fixtures for Java, Kotlin, Scala 2 and Scala 3 |
| R2 | `InliningDetectionTest` `the target is the changed declaration that gained the code` |
| R3 | `InliningDetectionTest` `code the target already had is not an inlining` |
| R4 | `InliningDetectionTest` `reformatted inlined code is linked`; the `inline-method-reformatted` golden fixtures |
| R5 | `InliningDetectionTest` `a removed body under ten tokens is not linked`; the `inline-method-short-body` golden fixtures |
| R6 | `OutlineModelTest` `an inlining names both ends and lists at most three names` |
| R7 | `OutlineModelTest` `an inlining names both ends and lists at most three names` |
| R8 | `OutlineModelTest` `an inlining names both ends and lists at most three names`: a fifth link ends in `+2 more` |
| R9 | `OutlineModelTest` `an inlining frames the inlined code on the new side` |
| R10 | `OutlineModelTest` `selecting a target highlights the code inlined into it` |
| R11 | `InliningDetectionTest` `one helper inlined into two callers links both`; `two helpers inlined into one target link both` |
| R12 | `RelationshipModelTest` `R3 kinds are one or more`; `RelationshipChecks.inliningFieldsAgree` runs for every engine |
| R13 | `OutlineModelTest` `the summary counts an inlining as inlined, not removed` |
| R14 | `OutlineModelTest` `an inlining is hidden with removed changes`; `ChangesTreeFilterTest` |
| R15 | Golden fixtures pass with detection on and off; `RelationshipBenchmark` re-runs the recorded baseline |
| R16 | `InliningDetectionTest` and benchmark fixtures in Java, Kotlin, Scala 2 and Scala 3 |
| R17 | `InliningDetectionTest` `detection is skipped above the work limit and the result is still analyzed` |
| R18 | `RelationshipBenchmarkTest` `inlining` cases in all four corpora, `no-new-wrong-pairs` against the baseline |
| R19 | `InliningDetectionTest` `an inlining is a high-confidence relationship` |
| R20 | `InliningDetectionTest` `a renamed target keeps its labels and the inlining` |
