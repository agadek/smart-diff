# JavaScript, Python and Go equivalence rules

Status: implemented, 2026-11-27

**Description:** Adds JavaScript and TypeScript, Python and Go [equivalence rules](../../GLOSSARY.md#equivalence-rule) to the opt-in "Mark rewrite-only changes" option of [declarations/08](08-equivalence-rules.md), so a [changed](../../GLOSSARY.md#changed) [declaration](../../GLOSSARY.md#declaration) whose only differences are literal, trailing comma or parenthesis rewrites, and in Go also the order of an import block, can be [rewrite-only](../../GLOSSARY.md#rewrite-only), as Java, Kotlin and Scala ones can.

**Use case:** A reviewer opens a diff where a formatter and a clean-up pass touched a TypeScript service, a Python module and a Go package: Prettier added trailing commas and switched quotes, Black rewrote `0XFF` and `'a'`, `gofmt -s` and a linter dropped parentheses around returned values, and one function in each file also got a real fix. With the option on, the rewritten declarations show as `rewrite-only`, the reviewer hides them with the filter, and the outline lists the three fixes alone.

## Requirements

- **R1** Every requirement of declarations/08 other than its Java rules (R10 to R12) holds for JavaScript, TypeScript, Python and Go declarations and file regions, with the rules below.
- **R2** The settings page lists a "JavaScript and TypeScript" group, a "Python" group and a "Go" group after the "Scala" group, with one checkbox per rule, all on by default, enabled only while the option is on.
- **R3** Each rule has its own checkbox, and turning a rule off in one language never changes another language's rules.
- **R4** The JavaScript **numeric literal** rule: a numeric literal written in another base, with or without separators, or with another letter case or form of its prefix, digits, exponent or dot, is the same when it has the same type and the same exact value, every literal without an `n` suffix being a Number. `31`, `31.0`, `0x1F`, `0o37`, `0b1_1111` and `3.1e1` are the same, as are `0x1Fn` and `31n`; `31` and `31n`, `017` and `15`, and `9007199254740993` and `9007199254740992` are not.
- **R5** The JavaScript **string quote and escape** rule: a string literal in single or double quotes written with other quotes or escapes is the same when it stands for the same characters. `'a'` and `"a"`, `"\x41"`, `"\u{41}"` and `"A"`, and `'\''` and `"'"` are the same; template literals, a line continuation, a legacy octal escape, an unknown escape and a string that can be a directive with an escape in it keep their text.
- **R6** The JavaScript **trailing comma** rule: a comma right before `)`, `]` or `}` is the same as no comma. `[1, 2,]` and `[1, 2]`, `f(a, b,)` and `f(a, b)`, and `{a, b,}` and `{a, b}` are the same; an array hole (`[1,,]` and `[1,]`) and a type parameter list (`<T,>`) stay a change.
- **R7** The JavaScript **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses after `return`, `throw`, `=` or a compound assignment, around a whole argument, and directly inside another pair, when the statement or the argument ends right after them.
- **R8** Under the JavaScript parentheses rule, parentheses around a comma expression, an expression with a top-level `in`, an operand, a call's own parentheses, an arrow function's body or parameters, or a value that starts on the line after `return` or `throw` stay a change.
- **R9** The Python **numeric literal** rule: an integer written in another base or with underscores, a float written in another form, and an imaginary literal written in another form are the same when they have the same type and the same exact value. `0x1F`, `0o37`, `0b1_1111` and `31` are the same, as are `1e3` and `1000.0`, and `1j` and `1.0J`; `1` and `1.0`, and `1.0` and `1j`, are not.
- **R10** The Python **string quote and escape** rule: a single-line string literal in single or double quotes, with no prefix or a `u` prefix, or with a `b` prefix, written with other quotes or escapes is the same when it stands for the same characters or bytes. `'a'` and `"a"`, `"\x41"`, `"\101"` and `"A"`, and `u"a"` and `"a"` are the same, and `b'a'` and `b"\x61"`; a string and a bytes literal are never the same. Raw, f- and t-strings, triple-quoted strings, `\N{…}`, a line continuation and an unknown escape keep their text.
- **R11** The Python **trailing comma** rule: a comma right before `)` of a call, a `def` parameter list, a class's bases or a parenthesized `import`, before `]` of a list display, and before `}` of a dict or set display is the same as no comma. A comma before `)` of a parenthesized tuple or before `]` of a subscript is the same as none only when another top-level comma stays. `f(a, b,)` and `f(a, b)`, `[1,]` and `[1]`, and `(a, b,)` and `(a, b)` are the same; `(x,)` and `(x)`, and `a[1,]` and `a[1]`, are not.
- **R12** The Python **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses after `return`, `=` or an augmented assignment, around a whole argument, and directly inside another pair, when the line ends right after them or a `,` or `)` follows.
- **R13** Under the Python parentheses rule, parentheses around a tuple, a generator expression, a `yield`, an assignment expression (`:=`), a starred expression, an operand, or content that spans lines stay a change.
- **R14** The Go **numeric literal** rule: an integer, floating-point or imaginary literal in another base, with or without underscores, or in another float form, are the same when they have the same kind and the same exact value. `0755`, `0o755` and `493` are the same, as are `0x1p-2` and `0.25`, `1e3` and `1000.0`, and `0123i` and `123i`; `1` and `1.0`, `'a'` and `97`, and `1i` and `1` are not.
- **R15** The Go **string literal** rule: an interpreted or raw string literal is the same as another when it stands for the same bytes, and a rune literal when it stands for the same rune. `"\x41"`, `"\101"`, `"A"` and `` `A` `` are the same, as are `'\x61'` and `'a'`; `"\xff"` and `"ÿ"` are not.
- **R16** The Go **trailing comma** rule: a comma right before `)` or `}` is the same as no comma. `f(a, b,)` and `f(a, b)`, and `T{1, 2,}` and `T{1, 2}`, are the same; a comma before `]` stays a change, and a line break before the closing bracket counts as for [formatting-only](../../GLOSSARY.md#formatting-only).
- **R17** The Go **redundant parentheses** rule: parentheses around a whole expression are the same as no parentheses after `return`, `=`, `:=` or a compound assignment, around a whole argument, and directly inside another pair, when the statement or the argument ends right after them.
- **R18** Under the Go parentheses rule, parentheses around a comma list, around content with a `{`, around a conversion's type such as `(*T)(x)`, and around an operand stay a change.
- **R19** The Go **import order** rule: the specs of one parenthesized `import ( … )` block are the same in another order. A block with a comment inside, specs in several `import` declarations and a changed alias stay a change. The label claims only that the same packages are imported under the same names, and nothing about the order in which packages are initialized.
- **R20** The JavaScript rules apply in JavaScript, JSX, TypeScript and TSX files.
- **R21** A JavaScript, TypeScript, Python or Go declaration never uses another language's rules, and Java, Kotlin and Scala declarations are marked as before.
- **R22** Languages other than Java, Kotlin, Scala, JavaScript, TypeScript, Python and Go never produce rewrite-only declarations.

## Out of scope

- Reordered object keys, and reordered imports in JavaScript and Python: property order, dict order and module evaluation order are observable.
- Optional semicolons in JavaScript, `'…'` against `` `…` `` template literals, implicit concatenation of Python strings (`'a' 'b'` against `'ab'`), and Python's raw, f-, t- and triple-quoted strings.
- A string key against an identifier key in JavaScript (`{'a': 1}` against `{a: 1}`).
- Rewrites that need types or name resolution, as in declarations/08 and 09.
- The Changes tree, the merge outline, [three-sided diffs](../../GLOSSARY.md#three-sided-diff), the relationship benchmark and the corpus validation, as in declarations/08.

## References

- [Details: design and tests](11-javascript-python-go-equivalence-rules-details.md)
- [Opt-in equivalence rules](08-equivalence-rules.md), whose option, filter, labels and summary this reuses
- [Kotlin and Scala equivalence rules](09-kotlin-scala-equivalence-rules.md), whose pattern this follows
- [ADR 0012](../../adr/0012-opt-in-equivalence-rules.md): each further language gets its own rules and fixtures
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Python](../languages/04-python.md), [JavaScript and TypeScript](../languages/16-javascript-typescript.md) and [Go](../languages/17-go.md)
- [Settings page](../settings/01-settings-page.md) R22
- [Roadmap: Opt-in equivalence rules](../../ROADMAP.md#opt-in-equivalence-rules)
