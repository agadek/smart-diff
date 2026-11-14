# Refactorings view: details

Status: implemented, 2026-11-09

Feature: [Refactorings view](06-refactorings-view.md)

## Design

### Where it plugs in

`OutlinePanel` shows a second tree model built from the same `ComparisonResult`. A toggle action in the outline toolbar, left of the change type filter, switches between the container tree and the refactorings view. The choice is stored in `SmartDiffSettings` as `outlineView = CONTAINER_TREE | REFACTORINGS`; `CONTAINER_TREE` is the default.

`RefactoringsModel.build` reads the result and the request texts and returns `OutlineItem`s, so the panel, the navigator, the strip and the filters work as they do for the container tree. `OutlineModel` keeps building the container tree and the summary line. The controller builds the chosen view from the request it kept, so switching views never compares the file again.

### Rows

```text
2 changed · 1 added · 1 extracted             (summary line, same in both views)
Refactorings
  rename total → sum, 12 usages  high
    usage  line 40
    usage  line 52
  extracted describeItems from summarize  high
  moved parse  high
  possible rename load → read  low
Other changes
  method validate()  changed
```

- One row per relationship, sorted by the first endpoint's position on the new side, then by the result's fixed relationship order.
- A row's title names its kinds and both names: `rename total → sum`, `moved parse`, `kind change trait → class Shape`, `extracted describeItems from summarize`, `inlined heading into render`. A combined relationship joins its kinds with `and`, such as `rename and move total → parse`.
- The row ends with the confidence word `high`, `medium` or `low`. A medium row is prefixed `candidate `, as a rename candidate is shown today; a low row is prefixed `possible `.
- A rename with grouped reference evidence adds `, 1 usage` or `, 12 usages` after its names. The grouped usages are children of the row.
- Low-confidence relationships are listed as `possible …` rows and also under each of their declarations in `Other changes`, so a reader sees the candidate where the added and removed declarations are.
- `Other changes` has the container tree's rows for every change that is in no shown relationship, plus the labels and notes of an uncertain, unanalyzed or changed declaration that a shown relationship links (R15), so the two views show the same changes. A container that holds such a change stays as a container without labels, as in the container tree.
- Expanding a relationship shows its evidence: the name pair, shared content, containers, positions and the resolved symbol of each usage. The engine's numeric score stays in the evidence and is not shown as a percentage.

### Endpoints, selection and colour

Selecting a row frames every endpoint on both sides, not only the first, and highlights every grouped usage exactly. The first endpoint is the row's `oldRange` and `newRange`; the other endpoints are `OutlineItem.frames`. A usage is an `OutlineItem.details` span, as the container tree already frames rename usages. Synchronized scrolling is held only when the row has one range per side, because the platform's scroll pause pairs two editors.

Each rename gets a colour from a fixed palette of 6, in row order among the shown rows, used for its usages' highlights in both editors; a seventh rename reuses the first colour. The palette has a light and a dark variant, so the usages stay readable on both themes. A rename whose kind is hidden gets no colour. Grouped usages are exact-range highlights; they never replace the diff's colours or a selection frame.

### Filters

- The change type filter hides the same changes in both views. Relationship rows have no change type and are not hidden by it.
- A second menu, the [relationship kind filter](../../GLOSSARY.md#relationship-kind-filter), hides relationship kinds: rename, move, kind change, extraction and inlining. A relationship row is hidden when every one of its kinds is hidden.
- The change type filter keeps the platform filter icon and the relationship kind filter uses the plugin's own funnel with a link badge, with a light and a dark variant.
- A declaration of a hidden relationship still appears in `Other changes`, because it is then in no shown relationship.
- A low-confidence relationship never covers its declarations: both stay in `Other changes` beside the `possible …` row.
- A change type filter never hides a relationship row. A relationship kind filter never hides an uncertain, unanalyzed or changed declaration: such a declaration keeps its labels and notes in `Other changes` beside the relationship row, so the two views still show every change.

### Failure behaviour

When the result has no relationships (an engine without them, or a partial result), or every relationship is hidden by kind, the `Refactorings` section shows `No refactorings found` and `Other changes` follows with the container tree's remaining rows.

### Theme and accessibility

Confidence and kinds are words, not colours. The palette only marks usages; it is never the only carrier of meaning. Every row's accessible name is its label, and the section rows are groups that the navigator skips.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `SmartDiffSettingsTest` for the stored default and `OutlineIntegrationTest` for the toolbar action, including the relationship-kind filter hidden in the container tree |
| R3, R4 | `RefactoringsModelTest` for one row per relationship, names, kinds and usage children |
| R5, R6 | `RefactoringsCoverageTest` over the golden fixtures and the checked-in relationship corpus: every labelled container-tree row has a relationship row or an `Other changes` row |
| R7, R8 | `RefactoringsModelTest` for the confidence word, the `candidate` and `possible` rows and the low links under declarations, including old/new ranges with colliding offsets |
| R9 | `RefactoringsModelTest` for evidence children, including names, content, containers and usage symbols |
| R10, R11 | `OutlineNavigator` checks in `OutlineIntegrationTest` for several frames and usage highlights, and `RefactoringsModelTest` for an absent side |
| R12 | `RefactoringsModelTest` for the light and dark palette and the seventh rename; `OutlineIntegrationTest` for one palette colour per rename in both editors, above the navigator's usage detail |
| R13, R14 | `RefactoringsModelTest` for both filters, including a relationship hidden by all its kinds and an exact filtered summary, and `OutlineIntegrationTest` |
| R15 | `RefactoringsModelTest` with uncertain, unanalyzed and changed declarations beside shown, hidden and low relationships, including a changed endpoint that keeps its `changed` label in `Other changes`; `OutlineIntegrationTest` for the hidden relationship and changed label |
| R16 | `RefactoringsModelTest` for a result with no relationships and for all kinds hidden |
| R17 | `OutlineIntegrationTest` drives the container tree and refactorings view through the controller and panel and asserts the same summary text and hidden count, before and after hiding a change type |
| R18 | `OutlineIntegrationTest` with the outline toggle and native diff in both views |
| R19 | `FilteringDiffToolTest` `test outline 06 R19 the relationship kind filter has its own icon` |
