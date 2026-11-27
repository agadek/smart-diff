# Opt-in equivalence rules

Status: implemented, 2026-11-17

**Description:** Opt-in, language-specific [equivalence rules](../../GLOSSARY.md#equivalence-rule) mark a [changed](../../GLOSSARY.md#changed) [declaration](../../GLOSSARY.md#declaration) as [rewrite-only](../../GLOSSARY.md#rewrite-only) when every difference in its own source is a rewrite one of the user's enabled rules names, such as `0x10` written as `16`. The first rules are for Java.

**Use case:** A reviewer opens a diff where a teammate replaced octal and hex constants with decimal ones, dropped parentheses around return values and replaced `\u0041`-style escapes with the characters themselves, and fixed one method. With the option on, the rewritten methods show as `rewrite-only`, the reviewer hides them with the filter, and the outline lists the fix alone.

## Requirements

- **R1** "Mark rewrite-only changes" is off by default, and with it off the outline is the same as without this feature.
- **R2** The option is a checkbox in the outline's change type filter menu and on the settings page, and both are the same setting.
- **R3** The settings page lists each language's rules with one checkbox per rule, all on by default, enabled only while the option is on.
- **R4** With the option on, a changed declaration whose own source is the same on both sides once every difference an enabled rule names is rewritten, and whitespace is compared as for [formatting-only](../../GLOSSARY.md#formatting-only), has the label `rewrite-only` instead of `changed`.
- **R5** A declaration with its [signature changed](../../GLOSSARY.md#signature-changed), a [parse error](../../GLOSSARY.md#parse-error) on either side, or a comment change is never rewrite-only.
- **R6** A declaration whose only differences are formatting and rewrites an enabled rule names is rewrite-only.
- **R7** A declaration whose differences need a rule the user turned off stays changed.
- **R8** A rewrite-only declaration keeps its other labels, such as `moved`, and the changes reported inside it.
- **R9** The [file region](../../GLOSSARY.md#file-region) is rewrite-only under the same rule.
- **R10** The Java **numeric literal** rule: an integer or floating-point literal written in another base, with or without underscores, or with another letter case in its prefix, digits, exponent or suffix, is the same when it has the same type and the same exact value. `0x1F`, `0X1f`, `037`, `0b1_1111` and `31` are the same; `31` and `31L`, and `1.0` and `1.0f`, are not.
- **R11** The Java **string escape** rule: a string or character literal written with other escapes is the same when it stands for the same characters. `"\u0041"`, `"\101"` and `"A"` are the same, as are `'\''` and `'\047'`; text blocks are out of scope and stay changed.
- **R12** The Java **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses where they cannot change how the expression is grouped: the value of `return` or `throw`, the right side of `=` or a compound assignment, a variable initializer, a whole method argument, and a pair directly inside another pair. Parentheses anywhere else, such as around an operand of an operator, a lambda's parameters or a cast's type, stay a change.
- **R13** With the option on, the change type filter can hide rewrite-only changes, off by default, and "Hide from the outline" on the settings page has the same checkbox.
- **R14** With the option off, the filter has no rewrite-only entry, and a hidden rewrite-only type hides nothing.
- **R15** The [summary line](../../GLOSSARY.md#summary-line) counts rewrite-only declarations as `rewrite-only`, not `changed`, after the comment-only count.
- **R16** The [refactorings view](../../GLOSSARY.md#refactorings-view) shows the same `rewrite-only` label in its other changes.
- **R17** Turning the option or a rule on or off updates open outlines without comparing the file again, and keeps the selection when the selected row is still shown.
- **R18** A language without rules never produces rewrite-only declarations.
- **R19** A rule claims only what it names: that the rewritten literal has the same value, or that the removed parentheses group the same expression. The label never claims that the two versions behave the same in any other way.

## Out of scope

- Languages other than Java; Kotlin and Scala get theirs in [declarations/09](09-kotlin-scala-equivalence-rules.md), JavaScript and TypeScript, Python and Go in [declarations/11](11-javascript-python-go-equivalence-rules.md), and the others each get their own rules and fixtures later, one language at a time.
- The Changes tree summary and its declaration rows, the merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff), which keep counting rewrite-only declarations as changed.
- The relationship benchmark and the corpus validation, whose classification does not change.
- Rewrites that need types or name resolution, such as `this.` qualification, a redundant cast, `final` on a local variable, or a constant replaced by its value.
- Text blocks, and literals whose value changes with the compiler's target, such as a character outside the source encoding.
- Collapsing rewrite-only changes into a group, as formatting-only changes are.

## References

- [Details: design and tests](08-equivalence-rules-details.md)
- [ADR 0012](../../adr/0012-opt-in-equivalence-rules.md), amending [ADR 0003](../../adr/0003-conservative-classification.md)
- [Change classification](02-change-classification.md), which this option does not change
- [Comment-only changes](../outline/11-comment-only-changes.md), the pattern this follows
- [Change type filter](../outline/03-change-type-filter.md)
- [Settings page](../settings/01-settings-page.md) R22
- [Roadmap: Opt-in equivalence rules](../../ROADMAP.md#opt-in-equivalence-rules)
