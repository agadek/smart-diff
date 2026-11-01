# SMART Diff

A structure-aware addition to the IntelliJ diff that shows which declarations changed between two versions of a source file.

This glossary is the dictionary for the [feature specifications](specs/README.md). Each term is a heading, so a spec can link to it, for example `GLOSSARY.md#declaration`.

## Comparison

### Old side

The earlier of the two versions of a file being compared, as supplied by the IDE diff.
_Avoid_: left, base, before

### New side

The later of the two versions of a file being compared, as supplied by the IDE diff. It is often editable.
_Avoid_: right, target, after

### Native diff

The IDE's own line and word diff for the same comparison.
_Avoid_: text diff, default diff, raw diff

### Comparison engine

One interchangeable way of comparing the old side and new side and producing a structural outline. A user can choose between engines that support the same language.
_Avoid_: diff engine, matcher, backend, algorithm, strategy

### Declaration engine

The comparison engine that SMART Diff ships. It compares the declarations of both sides, using the IDE's parser for the language.
_Avoid_: PSI engine, default engine

### Supported language

A language for which at least one comparison engine works in the current IDE.
_Avoid_: enabled language

### Analysis limits

The largest file size, the most declarations per side and the longest time a comparison may take. A comparison over a limit is not analyzed.
_Avoid_: thresholds, caps

## Structure

### Declaration

A named construct a reviewer would look for by name, such as a class, method, function, field or property. Local variables, lambdas and anonymous classes are part of their containing declaration, not declarations themselves.
_Avoid_: entity, node, element, member, symbol

### Kind

What sort of declaration it is, such as class, interface, trait, method, function, field or property.
_Avoid_: type, category

### Container

The declaration that directly encloses another declaration. A top-level declaration has no container.
_Avoid_: parent node, owner, scope

### Signature

The parameter types of a declaration as written in the source, used to tell overloads apart.
_Avoid_: descriptor, type signature

### File region

Source that belongs to no declaration, such as the package line, imports and top-level statements.
_Avoid_: header, preamble, top-level code

### Parse error

Source that the language's parser could not read. Code with a parse error that changed is unanalyzed.
_Avoid_: syntax error, broken code

## Matching

### Counterpart

The declaration on the other side that a declaration is matched to.
_Avoid_: pair, mapping, partner

### Match

A declaration on the old side paired with its counterpart on the new side.
_Avoid_: mapping, correspondence

### Uncertain

A declaration whose counterpart cannot be determined unambiguously. It is always shown, never guessed.
_Avoid_: ambiguous, fuzzy match, unresolved

### Candidate

A declaration on the other side that could be the counterpart of an uncertain declaration.
_Avoid_: option, possible match

### Rename candidate

A removed and an added declaration of the same kind with similar content, shown as one declaration whose name changed, such as `method total → sum()`. The pairing may be wrong, so it is always shown.
_Avoid_: rename, renamed declaration

## Change kinds

### Added

A declaration with no counterpart on the old side.
_Avoid_: inserted, new

### Removed

A declaration with no counterpart on the new side.
_Avoid_: deleted, gone

### Changed

A matched declaration whose own source differs beyond formatting.
_Avoid_: modified, updated, edited

### Signature changed

A matched declaration whose signature differs, such as a method with a new parameter. It also counts as changed.
_Avoid_: overload change, new signature

### Kind change

A matched declaration whose kind differs, such as a class turned into a trait. It is shown as `trait → class Shape` and counts as a signature change.
_Avoid_: type change, converted

### Formatting-only

A matched declaration whose own source differs only in whitespace the language treats as insignificant. It says nothing about runtime behaviour.
_Avoid_: whitespace change, cosmetic change, equivalent, semantically equal

### Moved

A matched declaration whose container or order among its siblings differs. It may also be changed or formatting-only.
_Avoid_: relocated, refactored

### Extraction

An added method or function whose body is mostly code that a changed declaration on the old side had and no longer has. It stays added and is linked to that declaration, its extraction source. Specified in [extraction detection](specs/declarations/05-extraction-detection.md).
_Avoid_: refactored, moved code, split

### Extraction source

The changed declaration that an extraction's code came from. It is labelled `code extracted to <declaration>`.
_Avoid_: origin, original method

### Unanalyzed

Source that could not be parsed reliably, shown without any classification.
_Avoid_: broken, error region, unknown

## Merges

### Base

The common ancestor of the two versions being merged. In a merge, the old side of both comparisons is the base.
_Avoid_: ancestor, original, common version

### Side

One of the two versions being merged into the base, shown in the left or the right pane of the merge window. A side is named after the branch or revision it comes from, never "ours" or "theirs".
_Avoid_: ours, theirs, mine, local, remote

### Merge window

The IDE window that opens when a merge, rebase, cherry-pick or unstash stops on a conflict: the two sides around an editable result pane.
_Avoid_: merge dialog, conflict editor

### Result pane

The editable middle pane of the merge window, which starts as the base and holds the merged file.
_Avoid_: output, merged pane, center editor

### Three-sided diff

A read-only diff of three versions side by side, with the base in the middle, such as a patch or shelf conflict.
_Avoid_: three-way diff, 3-pane diff

### Changed on both sides

A base declaration that each side changed. It is where a merge is most likely to need the reviewer's attention, whether or not the merge window shows a conflict there.
_Avoid_: conflict, conflicting declaration

### Same change on both sides

A declaration changed on both sides whose text is the same on the left and the right.
_Avoid_: duplicate change, identical conflict

### Merged without conflict

A declaration changed on both sides with no conflict of the merge window inside it.
_Avoid_: auto-merged, safe merge

## Relationships

### Relationship

A link between declarations on the two sides that belong together, such as a rename, a move, a kind change, an extraction or an inlining. It adds to the change tree and never replaces or hides a change.
_Avoid_: mapping, correspondence, semantic match

### Confidence

How strong the evidence for a relationship is: high, medium or low. It is never a claim that the two versions behave the same.
_Avoid_: probability, certainty, score

### Evidence

The facts a relationship rests on, such as the old and new names, the shared code or the containers, with the ranges they compare.
_Avoid_: reason, proof

### Usage

A place in the compared file that refers to a declaration by name, such as a call or a type reference.
_Avoid_: call site, reference, occurrence

### Inlining

A removed method or function whose body is mostly code that a changed declaration on the new side has and did not have. It stays removed and is linked to that declaration, its inlining target. Specified in [inlining detection](specs/relationships/05-inlining-detection.md).
_Avoid_: merged method, moved code

### Inlining target

The changed declaration that an inlining's code went into. It is labelled `code inlined from <declaration>`.
_Avoid_: destination, caller

## Presentation

### Structural outline

The tree of changed declarations and file regions shown for one comparison.
_Avoid_: semantic view, structure diff, change tree

### Refactorings view

The view of the structural outline that lists relationships first and the other changes after them. A toolbar button switches between it and the container tree.
_Avoid_: refactoring panel, semantic view

### Summary line

One line that counts the changes of a file by type, such as `2 changed · 1 added`. It starts the structural outline and follows the file in the Changes tree.
_Avoid_: stats, totals

### Label

A word after a declaration's name that says how it changed, such as `added`, `moved` or `rename candidate`.
_Avoid_: tag, badge, status

### Change type

A label the user can hide with the change type filter: changed, added, removed, moved or formatting-only.
_Avoid_: change category

### Change type filter

The outline's menu of change types to hide from the structural outline.
_Avoid_: hide filter, outline filter

### Outline toggle

The Structural Outline toolbar button that shows or hides the structural outline in one diff window.
_Avoid_: outline switch, show outline button

### Engine dropdown

The outline toolbar's list of the comparison engines that support the file's language.
_Avoid_: engine picker, engine menu

### SMART Diff viewer

The side-by-side viewer "Side-by-side viewer (SMART Diff)": the native side-by-side viewer, with the changes the change type filter hides shown faded.
_Avoid_: filtering viewer, custom viewer

### Changes tree

The tree of changed files in the Local Changes view and the Commit tool window.
_Avoid_: change list, commit tree
