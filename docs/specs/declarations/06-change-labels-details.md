# Change labels: details

Status: accepted, 2026-11-01

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
| R1–R4 | `OutlineModelTest` label cases, including a kind change and a rename candidate |
| R5, R7 | `OutlineModelTest` and the extraction golden fixtures |
| R6, R8, R9 | `OutlineModelTest` summary cases, including hidden changes |
| R10–R12 | `OutlineModelTest` filter cases |
| R13 | `OutlineModelTest` accessible names and the label rendering tests |

The tests above check the behavior but do not name these requirements; requirement-named tests are still needed before the feature moves to implemented.
