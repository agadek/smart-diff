# Change labels: details

Status: implemented, 2026-11-04

Feature: [Change labels](06-change-labels.md)

## Design

### Where it plugs in

`OutlineModel.tags` builds a node's labels from two sources: `ChangeNode.presence` gives `added`, `removed` or `uncertain`; `ChangeNode.renamedFrom`, `moved`, `content` and `signatureChanged` add `rename candidate`, `moved`, `changed`, `formatting-only`, `unanalyzed` and `signature changed`. A node shown only as a container of changes has no label.

`OutlineModel.summary` counts labels in a fixed order: `changed`, `extracted`, `added`, `removed`, `moved`, `formatting-only`, `rename candidate`, `uncertain`, `unanalyzed`. An added node with `extractedFrom` counts as `extracted`; it keeps the `added` label on its row. `unanalyzed` adds the file region's unanalyzed ranges, so a range without a row is still counted. The hidden count comes from the rows the filter leaves out.

`ChangeType` maps the filter's five types to labels. `changed` covers both `changed` and `signature changed`; `rename candidate`, `uncertain` and `unanalyzed` have no type, which is why they are never hidden.

### Labels added by later features

[Relationship model](../relationships/02-relationship-model.md) replaces `renamedFrom`, `extractedFrom` and `extractedTo` with relationships while keeping these labels. [Inlining detection](../relationships/05-inlining-detection.md) adds `inlined` to the summary and the note `inlined into <target>`; this feature is updated with it, not duplicated. [Refactorings view](../relationships/06-refactorings-view.md) adds confidence and evidence to the row; those are not labels of this feature.

### Edge cases

- A rename candidate that also changed signature shows both `rename candidate` and `signature changed`, and counts once as `rename candidate` plus once as `changed`.
- A moved declaration that also changed shows both `moved` and `changed`, and counts once in each.
- A hidden change inside a shown container leaves the container as a row without labels, so the container remains reachable.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `OutlineModelTest` `every change label appears when it applies`: added, removed, changed, moved, formatting-only, signature changed, rename candidate, uncertain and unanalyzed |
| R2 | `OutlineModelTest` `labels list presence, moved, content and signature changed`: a signature change also shows `changed` |
| R3 | `OutlineModelTest` `a kind change shows both kinds and carries signature changed`: `trait → class Shape` and `signature changed` |
| R4 | `OutlineModelTest` `a rename candidate shows both names, is never hidden and is counted`: `method total → sum()` |
| R5 | `OutlineModelTest` `extractions name both ends, qualified by container only when it differs`: `extracted from` and `code extracted to` |
| R6 | `OutlineModelTest` `the summary counts every label in a fixed order`: all nine counts in order, with zero counts left out |
| R7 | `OutlineModelTest` `extractions name both ends, qualified by container only when it differs`: an extraction counts as `extracted`, not `added` |
| R8 | `OutlineModelTest` `a signature change and a kind change count as changed` |
| R9 | `OutlineModelTest` `a change whose every label is hidden is left out, and the summary says how many are hidden`: every change is counted and the line ends with `· 4 hidden` |
| R10 | `OutlineModelTest` `the filter hides changed, added, removed, moved and formatting-only`: changed covers signature changes |
| R11 | `OutlineModelTest` `hiding changed covers signature changes, and uncertain and unanalyzed changes are never hidden`, and `a rename candidate shows both names, is never hidden and is counted` |
| R12 | `OutlineModelTest` `a change whose every label is hidden is left out, and the summary says how many are hidden`: a change with another visible label stays |
| R13 | `OutlineModelTest` `every row's label is its title followed by its change labels`: every row's label is the title followed by its change labels, so the meaning is text, not color alone |
| R13 | Manual check: (1) open a Java diff with the outline; (2) move focus to a row; (3) inspect its accessible name with a screen reader or the IDE's accessibility inspector; (4) confirm it is the row's label |
