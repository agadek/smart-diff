# Comment-only changes

Status: implemented, 2026-11-16

**Description:** An opt-in option marks [changed](../../GLOSSARY.md#changed) [declarations](../../GLOSSARY.md#declaration) whose only difference is in comments as [comment-only](../../GLOSSARY.md#comment-only), and the [change type filter](../../GLOSSARY.md#change-type-filter) can then hide them from the [structural outline](../../GLOSSARY.md#structural-outline).

**Use case:** A reviewer opens a diff where a teammate rewrote the documentation comments of a class and fixed one method. With the option on, the reworded methods show as `comment-only`, the reviewer hides them with the filter, and the outline lists the fix alone.

## Requirements

- **R1** "Mark comment-only changes" is off by default, and with it off the outline is the same as without this feature.
- **R2** The option is a checkbox in the outline's change type filter menu and on the settings page, and both are the same setting.
- **R3** With the option on, a changed declaration whose own source is the same on both sides once comments are left out and whitespace is compared as for [formatting-only](../../GLOSSARY.md#formatting-only) has the label `comment-only` instead of `changed`.
- **R4** A declaration with its [signature changed](../../GLOSSARY.md#signature-changed), a [parse error](../../GLOSSARY.md#parse-error) on either side, or a change inside a string is never comment-only.
- **R5** A declaration whose comments and formatting both changed, and nothing else, is comment-only.
- **R6** A comment-only declaration keeps its other labels, such as `moved`, and the changes reported inside it.
- **R7** The [file region](../../GLOSSARY.md#file-region) is comment-only under the same rule.
- **R8** With the option on, the change type filter can hide comment-only changes, off by default, and "Hide from the outline" on the settings page has the same checkbox.
- **R9** With the option off, the filter has no comment-only entry, and a hidden comment-only type hides nothing.
- **R10** The [summary line](../../GLOSSARY.md#summary-line) counts comment-only declarations as `comment-only`, not `changed`, after the formatting-only count.
- **R11** The [refactorings view](../../GLOSSARY.md#refactorings-view) shows the same `comment-only` label in its other changes.
- **R12** Turning the option on or off updates open outlines without comparing the file again, and keeps the selection when the selected row is still shown.
- **R13** Comment-only never claims that two versions behave the same.

## Out of scope

- The Changes tree summary and its declaration rows, which keep counting comment-only declarations as changed.
- The merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff).
- Docstrings and other comments the language parses as strings, which stay changed.
- Collapsing comment-only changes into a group, as formatting-only changes are.

## References

- [Details: design and tests](11-comment-only-changes-details.md)
- [Change classification](../declarations/02-change-classification.md) R6, which this option does not change
- [Change type filter](03-change-type-filter.md)
- [Settings page](../settings/01-settings-page.md) R19
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Roadmap: Hide comment-only changes](../../ROADMAP.md#hide-comment-only-changes)
