# Change labels: details

Status: implemented, 2026-11-04; the `usages renamed` additions (R1, R4, R6, R9, R11 and R14) arrive with [rename grouping](../relationships/04-rename-grouping.md), 2026-11-05.

Feature: [Change labels](06-change-labels.md)

## Design

### Where it plugs in

`OutlineModel.tags` builds a node's labels from two sources: `ChangeNode.presence` gives `added`, `removed` or `uncertain`; `ChangeNode.renamedFrom`, `moved`, `content` and `signatureChanged` add `rename candidate`, `moved`, `changed`, `formatting-only`, `unanalyzed` and `signature changed`. A node shown only as a container of changes has no label.

`OutlineModel` also reads the result's relationships. A rename relationship with grouped usages adds the note `N usages` to the rename row. A matched node is labelled `usages renamed` when it is not any rename's new endpoint, contains at least one grouped `Reference`, and has no change other than those references. The check applies the shared `RenameNameMapping` (`engine.api`) to the node's old text using every grouped reference in the node, maps each reference by its own rename relationship, and compares the result with the new text; it starts at the node's first non-whitespace token and never reads `ChangeNode.details`, so leading whitespace or an engine detail span cannot make the label. The helper changes only text at resolved reference ranges, never strings or comments. The grouped ranges go into the row's `OutlineItem.details` for navigation, not into `ChangeNode.details`. A node with another change keeps `changed`, and its grouped usages remain part of its row and the rename row. Neither case hides a usage: `usages renamed` is never a hidden type, and a node with a grouped usage is exempt from hiding even when its other labels are hidden (R11, R14).

`OutlineModel.summary` counts labels in a fixed order: `changed`, `extracted`, `added`, `removed`, `moved`, `formatting-only`, `rename candidate`, `usages renamed`, `uncertain`, `unanalyzed`. An added node with `extractedFrom` counts as `extracted`; it keeps the `added` label on its row. `usages renamed` counts the declaration once, not each usage; the rename row's `N usages` note gives the usage count. `unanalyzed` adds the file region's unanalyzed ranges, so a range without a row is still counted. The hidden count comes from the rows the filter leaves out.

`ChangeType` maps the filter's five types to labels. `changed` covers both `changed` and `signature changed`; `rename candidate`, `usages renamed`, `uncertain` and `unanalyzed` have no type, which is why they are never hidden. A node that has at least one grouped usage is also exempt: it stays in the rows and the summary even when every label it would otherwise show is a hidden type, it is not counted as hidden, and `HiddenLines` reads the same predicate so the native diff keeps its lines.

### Labels added by later features

[Relationship model](../relationships/02-relationship-model.md) replaces `renamedFrom`, `extractedFrom` and `extractedTo` with relationships while keeping these labels. [Rename grouping](../relationships/04-rename-grouping.md) adds the `N usages` note and the `usages renamed` label; it never removes a change from the summary or the native diff. [Inlining detection](../relationships/05-inlining-detection.md) adds `inlined` to the summary and the note `inlined into <target>`; this feature is updated with it, not duplicated. [Refactorings view](../relationships/06-refactorings-view.md) adds confidence and evidence to the row; those are not labels of this feature.

### Edge cases

- A rename candidate that also changed signature shows both `rename candidate` and `signature changed`, and counts once as `rename candidate` plus once as `changed`.
- A rename candidate with grouped usages shows `rename candidate` and the note `12 usages`; the rename itself still counts as `rename candidate`, not as `usages renamed`.
- A declaration whose only changed spans are grouped usages shows `usages renamed` and counts once as `usages renamed`, not as `changed`.
- A declaration with grouped usages and another change keeps `changed` and counts as `changed`; its grouped usages are still in its row, its `OutlineItem.details` and the native diff, and the declaration is never hidden by the changed filter, so R14 holds.
- A moved declaration that also changed shows both `moved` and `changed`, and counts once in each.
- A hidden change inside a shown container leaves the container as a row without labels, so the container remains reachable.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `OutlineModelTest` `every change label appears when it applies`: added, removed, changed, moved, formatting-only, signature changed, rename candidate, usages renamed, uncertain and unanalyzed; a declaration with grouped usages from two renames maps both and still shows `usages renamed`, and a string or comment with an old name is unchanged |
| R2 | `OutlineModelTest` `labels list presence, moved, content and signature changed`: a signature change also shows `changed` |
| R3 | `OutlineModelTest` `a kind change shows both kinds and carries signature changed`: `trait → class Shape` and `signature changed` |
| R4 | `OutlineModelTest` `a rename candidate shows both names, is never hidden and is counted`: `method total → sum()  rename candidate`; with grouped usages, `method total → sum(int)  rename candidate, 12 usages` |
| R5 | `OutlineModelTest` `extractions name both ends, qualified by container only when it differs`: `extracted from` and `code extracted to` |
| R6 | `OutlineModelTest` `the summary counts every label in a fixed order`: all ten counts in order, with zero counts left out |
| R7 | `OutlineModelTest` `extractions name both ends, qualified by container only when it differs`: an extraction counts as `extracted`, not `added` |
| R8 | `OutlineModelTest` `a signature change and a kind change count as changed` |
| R9 | `OutlineModelTest` `a change whose every label is hidden is left out, and the summary says how many are hidden`: every change is counted, and a usages-only declaration counts as `usages renamed`, not `changed` |
| R10 | `OutlineModelTest` `the filter hides changed, added, removed, moved and formatting-only`: changed covers signature changes |
| R11 | `OutlineModelTest` `hiding changed covers signature changes, and rename candidate, usages renamed, uncertain and unanalyzed changes are never hidden`; a `changed` declaration with a grouped usage also stays |
| R12 | `OutlineModelTest` `a change whose every label is hidden is left out, and the summary says how many are hidden`: a change with another visible label stays |
| R13 | `OutlineModelTest` `every row's label is its title followed by its change labels`: every row's label is the title followed by its change labels, so the meaning is text, not color alone |
| R13 | Manual check: (1) open a Java diff with the outline; (2) move focus to a row; (3) inspect its accessible name with a screen reader or the IDE's accessibility inspector; (4) confirm it is the row's label |
| R14 | `OutlineModelTest` `a grouped usage stays a counted change and is never hidden`: hiding `changed` leaves a usages-only declaration and a `changed` declaration with a grouped usage in the rows and summary, while a `changed` declaration without one is hidden; the hidden count excludes the exempt one; `OutlineIntegrationTest`: the filter leaves them in place |
