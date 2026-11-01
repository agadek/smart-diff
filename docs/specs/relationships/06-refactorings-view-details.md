# Refactorings view: details

Status: proposed, 2026-11-01

Feature: [Refactorings view](06-refactorings-view.md)

## Design

### Where it plugs in

`OutlinePanel` gets a second tree model built from the same `ComparisonResult`. A toggle action in the outline toolbar, next to the change type filter, switches between them. The choice is stored in `SmartDiffSettings` as `outlineView = CONTAINER_TREE | REFACTORINGS`.

### Rows

```text
2 changed · 1 added · 1 extracted             (summary line, same in both views)
Refactorings
  rename total → sum, 12 usages      high
    usage  line 40
    usage  line 52
  extracted describeItems             high
  moved parse                         high
  possible rename load → read         low
Other changes
  method validate()  changed
```

- One row per relationship, sorted by the first endpoint's position on the new side.
- Low-confidence relationships are listed as `possible …` rows and also under each of their declarations in `Other changes`.
- `Other changes` has the container tree's rows for every change that is in no relationship, so the two views show the same changes.
- Expanding a relationship shows its evidence: names, content shared, containers, and the resolved symbol of a usage.

### Selection and colour

Selecting a row frames every endpoint and usage range on both sides, as the merge outline frames several ranges. Each rename gets a colour from a fixed palette of 6, in row order, used for its usages' highlights in both editors; a seventh rename reuses the first colour.

### Filters

- The change type filter hides the same changes in both views.
- A second menu hides relationship kinds: rename, move, kind change, extraction, inlining.
- A relationship row is hidden only by its kind; its declarations still appear in `Other changes` when its kind is hidden.

### Failure behaviour

When the result has no relationships (an engine without them, or a partial result), the view shows `No refactorings found` and then `Other changes`.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `SmartDiffSettingsTest` and a toolbar action test |
| R3–R6 | `OutlineModelTest` for the refactorings model, and a test that both views cover the same changes |
| R7–R9 | Row rendering tests |
| R10, R11 | `OutlineNavigator` tests for several ranges and a missing side |
| R12 | Highlight test in `OutlineIntegrationTest` |
| R13, R14 | Filter tests in both views |
| R15 | A model test with uncertain and unanalyzed declarations |
| R16 | A result with no relationships |
| R17 | The summary line test in both views |
| R18 | `OutlineIntegrationTest` with the outline toggle off |
