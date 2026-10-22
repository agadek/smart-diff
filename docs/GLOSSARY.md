# SMART Diff

A structure-aware addition to the IntelliJ diff that shows which declarations changed between two versions of a source file.

## Comparison

**Old side / New side**:
The two versions of a file being compared, as supplied by the IDE diff.
_Avoid_: left/right, base/target, before/after

**Native diff**:
The IDE's own line and word diff for the same comparison.
_Avoid_: text diff, default diff, raw diff

**Comparison engine**:
One interchangeable way of comparing the old side and new side and producing a structural outline. A user can choose between engines that support the same language.
_Avoid_: diff engine, matcher, backend, algorithm, strategy

**Supported language**:
A language for which at least one comparison engine works in the current IDE.
_Avoid_: enabled language

## Structure

**Declaration**:
A named construct a reviewer would look for by name, such as a class, method, function, field or property. Local variables, lambdas and anonymous classes are part of their containing declaration, not declarations themselves.
_Avoid_: entity, node, element, member, symbol

**Container**:
The declaration that directly encloses another declaration. A top-level declaration has no container.
_Avoid_: parent node, owner, scope

**Signature**:
The parameter types of a declaration as written in the source, used to tell overloads apart.
_Avoid_: descriptor, type signature

**File region**:
Source that belongs to no declaration, such as the package line, imports and top-level statements.
_Avoid_: header, preamble, top-level code

## Matching

**Counterpart**:
The declaration on the other side that a declaration is matched to.
_Avoid_: pair, mapping, partner

**Match**:
A declaration on the old side paired with its counterpart on the new side.
_Avoid_: mapping, correspondence

**Uncertain**:
A declaration whose counterpart cannot be determined unambiguously. It is always shown, never guessed.
_Avoid_: ambiguous, fuzzy match, unresolved

## Change kinds

**Added / Removed**:
A declaration with no counterpart on the old side / on the new side.
_Avoid_: inserted, deleted, new, gone

**Changed**:
A matched declaration whose own source differs beyond formatting.
_Avoid_: modified, updated, edited

**Formatting-only**:
A matched declaration whose own source differs only in whitespace the language treats as insignificant. It says nothing about runtime behaviour.
_Avoid_: whitespace change, cosmetic change, equivalent, semantically equal

**Moved**:
A matched declaration whose container or order among its siblings differs. It may also be changed or formatting-only.
_Avoid_: relocated, refactored

**Extracted**:
An added method or function whose body is mostly code that a changed declaration on the old side had and no longer has. It stays added and is linked to that declaration, its source. Specified in [EXTRACTION-DETECTION.md](EXTRACTION-DETECTION.md).
_Avoid_: refactored, moved code, split

**Unanalyzed**:
Source that could not be parsed reliably, shown without any classification.
_Avoid_: broken, error region, unknown

## Presentation

**Structural outline**:
The tree of changed declarations and file regions shown for one comparison.
_Avoid_: semantic view, structure diff, change tree
