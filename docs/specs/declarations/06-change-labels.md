# Change labels

Status: implemented, 2026-11-04; the `usages renamed` additions (R1, R4, R6, R9, R11 and R14) arrive with [rename grouping](../relationships/04-rename-grouping.md), 2026-11-05; the Kotlin and Scala step 2 is implemented, 2026-11-07; the inlining note and count (R5, R6 and R15) arrive with [inlining detection](../relationships/05-inlining-detection.md), 2026-11-08; the [refactorings view](../relationships/06-refactorings-view.md) shows the same labels under `Other changes`, 2026-11-09.

**Description:** One set of user-facing [labels](../../GLOSSARY.md#label) says how each change is classified, how the [summary line](../../GLOSSARY.md#summary-line) counts it, and whether the [change type filter](../../GLOSSARY.md#change-type-filter) can hide it.

**Use case:** A reviewer reads `method total → sum(int)  rename candidate · signature changed` and `2 changed · 1 extracted · 1 added` and can tell what each word means without reconstructing it from several documents.

## Requirements

- **R1** A change shows every label that applies: `added`, `removed`, `changed`, `moved`, `formatting-only`, `signature changed`, `rename candidate`, `usages renamed`, `uncertain` or `unanalyzed`.
- **R2** A signature change also carries `changed`.
- **R3** A [kind change](../../GLOSSARY.md#kind-change) shows both kinds in the row title, such as `trait → class Shape`, and carries `signature changed`.
- **R4** A [rename candidate](../../GLOSSARY.md#rename-candidate) shows the old name before the new one, such as `method total → sum(int)  rename candidate`, and the number of grouped [usages](../../GLOSSARY.md#usage) after it, such as `method total → sum(int)  rename candidate, 12 usages`.
- **R5** An [extraction](../../GLOSSARY.md#extraction) stays `added` and gets the note `extracted from <source>`; its [source](../../GLOSSARY.md#extraction-source) gets `code extracted to <declaration>`. An [inlining](../../GLOSSARY.md#inlining) stays `removed` and gets the note `inlined into <target>`; its [target](../../GLOSSARY.md#inlining-target) gets `code inlined from <declaration>`.
- **R6** The summary line counts `changed`, `extracted`, `added`, `inlined`, `removed`, `moved`, `formatting-only`, `rename candidate`, `usages renamed`, `uncertain` and `unanalyzed`, in that order, and leaves out a count of zero.
- **R7** An extraction counts as `extracted` instead of `added`.
- **R8** A signature change and a kind change count as `changed`.
- **R9** The summary counts every change, hidden or not, and ends with the number hidden, such as `· 2 hidden`; a declaration whose only changes are grouped usages counts as `usages renamed` instead of `changed`.
- **R10** The filter can hide `changed` (including signature changes), `added`, `removed`, `moved` and `formatting-only`.
- **R11** `rename candidate`, `usages renamed`, `uncertain` and `unanalyzed` are never hidden.
- **R12** A change is hidden only when every label it shows is of a hidden type.
- **R13** Labels are text, not color alone, and each row's accessible name is its label.
- **R14** (with [rename grouping](../relationships/04-rename-grouping.md)) A declaration with grouped usages is never hidden, even when its other labels are of a hidden type, and it stays a change in the summary and the native diff.
- **R15** (with [inlining detection](../relationships/05-inlining-detection.md)) An inlining counts as `inlined` instead of `removed`.

## Out of scope

- Relationship confidence and evidence inside a declaration row; the [refactorings view](../relationships/06-refactorings-view.md) shows them on its own relationship rows.
- Changing matching or classification. This feature names the current result; it adds no rule.

## References

- [Details: design and tests](06-change-labels-details.md)
- [Product scope](../README.md#product-scope)
- [Structural outline](../outline/01-structural-outline.md)
- [Change classification](02-change-classification.md)
- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
- [ADR 0003](../../adr/0003-conservative-classification.md)
