# Kotlin and Scala equivalence rules

Status: proposed, 2026-11-17

**Description:** Adds Kotlin and Scala [equivalence rules](../../GLOSSARY.md#equivalence-rule) to the opt-in "Mark rewrite-only changes" option of [declarations/08](08-equivalence-rules.md), so a [changed](../../GLOSSARY.md#changed) Kotlin or Scala [declaration](../../GLOSSARY.md#declaration) whose only differences are literal, string template or parenthesis rewrites can be [rewrite-only](../../GLOSSARY.md#rewrite-only), as Java ones can.

**Use case:** A reviewer opens a Kotlin diff where a teammate applied the IDE's clean-up inspections: `${name}` became `$name` in log messages, `0x0A` became `10`, `é` escapes became the characters, and parentheses around returned values went away, and one function also got a real fix. With the option on, the cleaned-up functions show as `rewrite-only`, the reviewer hides them with the filter, and the outline lists the fix alone. A Scala reviewer gets the same for literals, escapes and parentheses.

## Requirements

- **R1** Every requirement of declarations/08 other than its Java rules (R10 to R12) holds for Kotlin and Scala declarations and file regions, with the rules below.
- **R2** The settings page lists a "Kotlin" group and a "Scala" group after the "Java" group, with one checkbox per rule, all on by default, enabled only while the option is on.
- **R3** Each rule has its own checkbox, and turning a rule off in one language never changes another language's rules.
- **R4** The Kotlin **numeric literal** rule: an integer or floating-point literal written in another base, with or without underscores, or with another letter case in its prefix, digits, exponent or suffix, is the same when it has the same suffix and the same exact value. `0x1F`, `0X1f`, `0b1_1111` and `31` are the same, as are `0xFFFF_FFFF` and `4294967295`, and `1e3` and `1000.0`; `31` and `31L`, `31` and `31u`, and `1.0` and `1.0f` are not.
- **R5** The Kotlin **string escape** rule: a string or character literal written with other escapes is the same when it stands for the same characters. `"A"` and `"A"`, `'\''` and `'''`, and `"\$"` and `"$"` are the same; an escaped `\$` is never the same as a `$` that starts a template, and raw strings (`"""`) stay changed.
- **R6** The Kotlin **template braces** rule: `${name}` and `$name` in a string template are the same when the braces hold only one simple name or `this`, and the character after them cannot continue a name. `"${x}!"` and `"$x!"` are the same; `"${x}y"` and `"$xy"`, and `"${x.y}"` and `"$x.y"`, are not.
- **R7** The Kotlin **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses after `return`, `throw`, `=` or a compound assignment, around a whole argument, and directly inside another pair, when the statement or the argument ends right after them.
- **R8** Under the Kotlin parentheses rule, parentheses followed on the next line by `.`, `?.`, `::`, `?:`, `&&`, `||`, `as` or any other token that can continue an expression stay a change, as do parentheses around an operand, a lambda's or a destructuring declaration's parameters, a type, or a labelled `return@label`.
- **R9** The Scala **numeric literal** rule: a decimal or hexadecimal integer or floating-point literal, with or without underscores, or with another letter case in its prefix, digits, exponent or suffix, is the same when it has the same type and the same exact value, a hexadecimal literal read as the compiler reads its bits. `0x1F`, `0X1f` and `31` are the same, as are `0x7FFFFFFF` and `2147483647`, and `1.0`, `1d` and `1e0`; `31` and `31L`, and `1.0` and `1.0f`, are not.
- **R10** The Scala **string escape** rule: a plain string literal or a character literal written with other escapes is the same when it stands for the same characters. `"A"` and `"A"`, and `'\t'` and `'\u0009'`, are the same; interpolated strings, triple-quoted strings and a unicode escape of a quote, a backslash or a line break stay changed.
- **R11** The Scala **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses after `return`, `throw` or `=`, around a whole argument, and directly inside another pair, when the statement or the argument ends right after them.
- **R12** Under the Scala parentheses rule, parentheses around a tuple, a type ascription, a lambda, an assignment or an expression with a `_` placeholder stay a change, as do parentheses followed on the next line by anything but `}` or a keyword that starts a statement.
- **R13** The Scala rules are the same in Scala 2 and Scala 3, and line breaks and indentation are compared as for each dialect's [formatting-only](../../GLOSSARY.md#formatting-only) rules ([declarations/07](07-kotlin-scala-formatting.md)).
- **R14** The Kotlin rules apply in `.kt` files and in `.kts` scripts, Gradle build scripts included.
- **R15** A Kotlin or Scala declaration never uses another language's rules, and Java declarations are marked as before.
- **R16** Languages other than Java, Kotlin and Scala never produce rewrite-only declarations.

## Out of scope

- The Changes tree, the merge outline, [three-sided diffs](../../GLOSSARY.md#three-sided-diff), the relationship benchmark and the corpus validation, as in declarations/08.
- Parentheses after `->` in Kotlin and `=>` or `<-` in Scala, around the condition of Scala 3 `if … then`, and after Scala's compound assignment operators, which are method names.
- Template braces and escapes in Scala interpolated strings, whose interpolator decides what an escape means.
- Rewrites that need types or name resolution, such as `it` against a named lambda parameter, `this.` qualification, `.toLong()` against `L`, or a constant replaced by its value.
- Scala binary and octal literals, and Scala 2.12's unicode escapes outside literals.

## References

- [Details: design and tests](09-kotlin-scala-equivalence-rules-details.md)
- [Opt-in equivalence rules](08-equivalence-rules.md), whose option, filter, labels and summary this reuses
- [ADR 0012](../../adr/0012-opt-in-equivalence-rules.md): each further language gets its own rules and fixtures
- [Kotlin and Scala formatting](07-kotlin-scala-formatting.md), whose line break rules the comparison keeps
- [Kotlin](../languages/02-kotlin.md) and [Scala](../languages/03-scala.md)
- [Settings page](../settings/01-settings-page.md) R22
- [Roadmap: Opt-in equivalence rules](../../ROADMAP.md#opt-in-equivalence-rules)
