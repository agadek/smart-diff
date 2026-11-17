# Moves inside declarations

Status: proposed, 2026-11-17

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) finds [code moves](../../GLOSSARY.md#code-move): statements removed from one place in a [matched](../../GLOSSARY.md#match) [declaration](../../GLOSSARY.md#declaration) and added at another place in the same declaration or another matched declaration of the file. The [structural outline](../../GLOSSARY.md#structural-outline) lists each code move as a [relationship](../../GLOSSARY.md#relationship), frames both positions, and shows the edits made inside the moved code.

**Use case:** A reviewer reads a change where a teammate moved a validation block from the end of `process` to its start, renamed a variable inside it, and moved a logging loop from `process` into `finish`. The native diff shows both blocks as removed lines and added lines. The outline lists `moved code in process` and `moved code process → finish` under their declarations, and selecting one frames the old and new positions and highlights only the renamed variable.

## Requirements

- **R1** "Detect code moved inside declarations" is on by default, and with it off the outline is the same as without this feature.
- **R2** A code move is a run of one or more whole statements that the declaration engine finds removed from a matched declaration on the [old side](../../GLOSSARY.md#old-side) and added in one run to a matched declaration on the [new side](../../GLOSSARY.md#new-side).
- **R3** The source and the target of a code move are the same declaration or two declarations of the same file.
- **R4** A code move holds at least 12 tokens that are not whitespace or comments.
- **R5** Each statement of a code move is similar to its counterpart: at least 0.8 of their adjacent token pairs are shared.
- **R6** A statement that is as similar to another statement, within 0.1, is not part of a code move.
- **R7** A statement that lies inside a statement of a code move is not a code move of its own.
- **R8** Each statement belongs to at most one code move.
- **R9** Code added to an [added](../../GLOSSARY.md#added) declaration, or removed from a [removed](../../GLOSSARY.md#removed) one, is never a code move.
- **R10** Code that [extraction detection](../../GLOSSARY.md#extraction) or [inlining detection](../../GLOSSARY.md#inlining) already links is not a code move.
- **R11** A code move whose statements are the same on both sides apart from formatting has high [confidence](../../GLOSSARY.md#confidence), and one with other edits has medium confidence.
- **R12** The container tree lists each code move as a row under its target declaration, titled `moved code lines A–B → C–D`, with the old side's line numbers first, counted from 1.
- **R13** A code move row whose source is another declaration has the note `from <source>`, and the source declaration has the note `code moved to <target>`.
- **R14** A code move row has the `moved` label, and also `changed` when its statements differ beyond formatting.
- **R15** Selecting a code move row frames the moved statements on both sides and highlights the tokens that differ between them.
- **R16** The [refactorings view](../../GLOSSARY.md#refactorings-view) lists each code move as `moved code in <declaration>` or `moved code <source> → <target>`, with its confidence word.
- **R17** The [relationship kind filter](../../GLOSSARY.md#relationship-kind-filter) has a `Moved code` entry, and the [change type filter](../../GLOSSARY.md#change-type-filter) hides code move rows by their labels.
- **R18** Code moves get [moved-code markers](../../GLOSSARY.md#moved-code-marker) in both editors, as moved declarations do.
- **R19** A code move never changes how its source or target is matched, classified or labelled.
- **R20** The [summary line](../../GLOSSARY.md#summary-line) counts declarations as before and does not count code moves.
- **R21** A file with more than 2 000 statements on either side that the engine finds removed or added has no code moves.
- **R22** Code moves are found for Java, Kotlin, Scala 2, Scala 3 and Python.
- **R23** The [relationship benchmark](../relationships/01-relationship-benchmark.md) has labelled code move cases in each of those languages, and reports no wrong code move.
- **R24** Changing the setting applies to open outlines when applied.
- **R25** Code moves are found only after [fine-grained differencing](../declarations/10-fine-grained-differencing.md) gives the declarations their changed runs.
- **R26** A code move never says that two versions behave the same, and the native diff keeps showing every changed line.

## Out of scope

- Code moved between files.
- Code moved into a new declaration, which [extraction detection](../declarations/05-extraction-detection.md) covers, or out of a removed one, which [inlining detection](05-inlining-detection.md) covers.
- Moved expressions or parts of a statement.
- The Changes tree, the merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff).
- Reviewed marks on code move rows of their own; a row follows its target declaration's mark.
- Languages other than those in R22, and files compared by the [line comparison](../../GLOSSARY.md#line-comparison).

## References

- [Details: design and tests](08-moves-inside-declarations-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
- [Relationship model](02-relationship-model.md)
- [Refactorings view](06-refactorings-view.md)
- [Moved-code markers](../outline/13-moved-code-markers.md)
- [Fine-grained differencing](../declarations/10-fine-grained-differencing.md)
- [Settings page](../settings/01-settings-page.md) R24
- [Structural relationships plan](../../STRUCTURAL-RELATIONSHIPS.md)
- [Roadmap: Moves inside declarations](../../ROADMAP.md#moves-inside-declarations)
