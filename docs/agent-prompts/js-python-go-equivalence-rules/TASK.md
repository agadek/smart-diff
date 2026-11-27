# Task: equivalence rules for JavaScript and TypeScript, Python and Go

Written 2026-11-27. Feature task for a coding agent; the paste-in prompt is [PROMPT.md](PROMPT.md). Work on branch `feat/js-python-go-equivalence-rules`, which already holds this file.

## Why

The opt-in "Mark rewrite-only changes" option ([declarations/08](../../specs/declarations/08-equivalence-rules.md), [ADR 0012](../../adr/0012-opt-in-equivalence-rules.md)) has rules for Java and, since [declarations/09](../../specs/declarations/09-kotlin-scala-equivalence-rules.md), for Kotlin and Scala. JavaScript and TypeScript, Python and Go, three of the most used languages the plugin supports, have none, so a diff where Prettier, Black or `gofmt -s` and a clean-up pass touched the code shows every touched declaration as `changed`. Comparable tools ([docs/RESEARCH.md](../../RESEARCH.md), SemanticDiff's invariances list) treat quote style, trailing commas, literal forms and redundant parentheses as equivalent in these languages. This is the largest gap the latest competitor comparison found.

## What to build

A new feature spec, `docs/specs/declarations/11-javascript-python-go-equivalence-rules.md`, and its details file, written first, then the rules, following declarations/09 exactly: the same option, filter, labels, summary and settings, and nothing else changes. Each rule is a pure token function (`EquivalenceRuleImpl`) on the language's lexer tokens as `Token.type` records them (the token type's `toString()`), with no PSI, types or name resolution, and no class from the JavaScript, Python or Go plugin, so the rules load without them and the Plugin Verifier sees no new API.

### Rules

Below, "same" means the rule makes the two forms equal and "not" means they must stay different. Every "not" is a fixture the rule's test must reject. Where a case is not listed, decide it in the spec, conservatively: when in doubt, the rewrite stays a change ([ADR 0003](../../adr/0003-conservative-classification.md)).

#### JavaScript and TypeScript (JS, TS, JSX, TSX; one settings group, "JavaScript and TypeScript")

1. **Numeric literals.** Bases (`0x`, `0o`, `0b`, any letter case), numeric separators, exponents, leading and trailing dots, compared by exact decimal value as Java does. Every non-BigInt literal is a Number, so `31`, `31.0`, `0x1F`, `0o37`, `0b1_1111` and `3.1e1` are the same. Not: `31` and `31n` (BigInt); legacy octal `017` and `08` keep their text; two decimals that round to the same double but differ exactly, such as `9007199254740993` and `9007199254740992`. `0x1Fn` and `31n` are the same.
2. **String quotes and escapes.** `'…'` and `"…"` literals compared by the characters they stand for: `'a'` and `"a"`, `"\x41"`, `"A"`, `"\u{41}"` and `"A"`, `'\''` and `"'"` are the same. Not: template literals (out of scope); a line continuation, a legacy octal escape (`\1` to `\7`, `\0` followed by a digit) or an unknown escape keeps the literal's text; a directive keeps its meaning, so `'use strict'` and `"use strict"` are the same but `'use\x20strict'` and `'use strict'` are not; JSX attribute values (`<a b="\x41"/>` against `<a b="A"/>`), which have no escapes. A string key and an identifier key (`{a: 1}` against `{'a': 1}`) are out of scope.
3. **Trailing commas.** A comma right before `]`, `}` or `)` removed in array and object literals, destructuring patterns, import and export braces, call arguments, parameter lists, TS enums, interfaces and type literals. `[1, 2,]` and `[1, 2]`, `f(a, b,)` and `f(a, b)` are the same. Not: an array hole, so `[1,,]` and `[1,]` stay different (never remove a comma whose previous meaningful token is `,` or `[`); a type parameter list, where `<T,>` is required in TSX.
4. **Redundant parentheses.** The declarations/09 scan (`ParenthesesScan` in `EquivalenceRuleSupport.kt`) with a JS table: after `return`, `throw`, `=` and every compound assignment (`+=` … `**=`, `&&=`, `||=`, `??=`), around a whole argument and directly inside another pair, when `;`, `}`, `,`, `)` or the end of the declaration's tokens follows. Not: a top-level `,` (comma operator, `(0, obj.f)()`), a top-level `in` (it can matter in a `for` header), an operand, a call's own or an IIFE's parentheses, an arrow body `=> ({})`, and a pair after `return` or `throw` whose content starts on another line: JavaScript's whitespace policy is `INSIGNIFICANT`, so without this exclusion `return (\n x)` would wrongly match `return x`, which returns `undefined` (automatic semicolon insertion; see `JavaScriptInterop.significantWhitespace`).

#### Python (one settings group, "Python")

1. **Numeric literals.** Integers in `0x`, `0o`, `0b` (any case) and decimal with underscores; floats with exponents, leading or trailing dots; imaginary literals with `j` or `J`. Same type and exact value: `0x1F`, `0o37`, `0b1_1111` and `31`; `1e3` and `1000.0`; `1j` and `1.0J`. Not: `1` and `1.0`, `1.0` and `1j`.
2. **String quotes and escapes.** Single-line `'…'` and `"…"` literals, with no prefix or a `u`/`U` prefix (the same in Python 3), compared by the characters they stand for: `\\ \' \" \a \b \f \n \r \t \v`, octal `\ooo`, `\xhh`, `\uXXXX`, `\UXXXXXXXX`. Bytes literals (`b`, `B`) compare only with bytes literals, by byte value. Not: raw strings (`r`), f-strings and t-strings, triple-quoted strings, `\N{…}`, a line continuation, and an unknown escape such as `\d`, which all keep their text; implicit concatenation (`'a' 'b'` against `'ab'`) is out of scope.
3. **Trailing commas.** Removed before `)` of a call, a `def` parameter list, a class's bases or a parenthesized `import`, before `]` of a list display, and before `}` of a dict or set display. Not: the comma that makes a tuple, so `(x,)` and `(x)`, `return x,` and `return x`, `a[1,]` and `a[1]`, and `tuple[int,]` and `tuple[int]` stay different. A comma before `)` of a parenthesized tuple or before `]` of a subscript may go only when another top-level comma stays (`(a, b,)` and `(a, b)` are the same).
4. **Redundant parentheses.** The shared scan with a Python table: after `return`, `=`, an augmented assignment and an annotated assignment's `=`, around a whole argument and directly inside another pair, when the logical line ends, or `,` or `)` follows. Not: a top-level `,` (tuple), `for` anywhere at the top level (a generator expression is only bare as a sole argument), `yield`, `:=` (`x = (y := 1)` needs them), `*` or `**` at the start, an operand, and a pair whose content spans lines (Python's implicit line joining ends with the brackets; compare under `INDENTATION_SIGNIFICANT` as the provider does).

#### Go (one settings group, "Go")

1. **Numeric literals.** Decimal, `0x`, `0o`/`0O`, legacy `0` octal and `0b` integers with underscores; decimal and hexadecimal floats (`0x1p-2`); imaginary literals with `i`. Same kind and exact value: `0755`, `0o755` and `493`; `0x1p-2` and `0.25`; `1e3` and `1000.0`. Not: `1` and `1.0` (an untyped integer and an untyped float constant have different default types), `'a'` and `97`, and an imaginary literal against a real one. A decimal imaginary literal with leading zeros, `0123i`, is decimal `123i`, not octal.
2. **String literals.** Interpreted (`"…"`) and raw (`` `…` ``) string literals compared by the **bytes** they stand for, and rune literals by the rune: `"\x41"`, `"\101"`, `"A"`, `` `A` `` and `"A"` are the same, as are `'\x61'` and `'a'`. `\x` and octal escapes are single bytes, so `"\xff"` and `"ÿ"` (two bytes in UTF-8) are not the same. A raw string drops carriage returns, as the compiler does. A struct tag is a string literal and follows the same rule.
3. **Trailing commas.** A comma right before `)` or `}` removed in call arguments, parameter and result lists and composite literals. Not: a comma before `]`, which can be required in a type parameter list (`type T[P *C,] …`). A line break before the closing bracket is compared as the provider's `LINE_BREAKS_SIGNIFICANT` policy compares it; say in the spec whether `{1, 2,\n}` and `{1, 2}` are the same, with fixtures.
4. **Redundant parentheses.** The shared scan with a Go table: after `return`, `=`, `:=` and the compound assignments (`+=` … `&^=`), around a whole argument and directly inside another pair, when `;`, `}`, `,`, `)`, a line break (Go inserts a semicolon after `)` at a line break) or the end follows. Not: a top-level `,`, content with a `{` anywhere (a composite literal in an `if`, `for` or `switch` header needs its parentheses), a conversion such as `(*T)(x)`, and an operand.
5. **Import order.** The specs of one parenthesized `import ( … )` block compared as a set: `"fmt"` then `"os"` and `"os"` then `"fmt"` are the same, with any aliases kept with their path. Not: a block with a comment inside, specs spread over several `import` declarations, and a changed alias. The rule claims only that the same packages are imported under the same names (declarations/08 R19). Imports are not Go declarations (languages/17 R9), so this applies to the [file region](../../GLOSSARY.md#file-region) (declarations/08 R9); if the file region's tokens make this rule impractical as a token function, keep the other Go rules, move import order to the spec's open questions, and say so in the PR.

### Out of scope (write these into the spec)

- Reordered object keys (JS property enumeration order and Python dict order are observable) and reordered imports in JavaScript and Python (module evaluation order and side effects change).
- Optional semicolons in JavaScript, `'…'` against `` `…` `` template literals, and Python's `r`, f- and triple-quoted strings.
- Anything that needs types or name resolution, as in declarations/08 and 09.
- The Changes tree, merge outline, three-sided diffs, relationship benchmark and corpus validation, as in declarations/08.

## Where it plugs in

Everything around the rules already exists; read [declarations/08-equivalence-rules-details.md](../../specs/declarations/08-equivalence-rules-details.md) and [09-kotlin-scala-equivalence-rules-details.md](../../specs/declarations/09-kotlin-scala-equivalence-rules-details.md) first.

| What | Where |
| --- | --- |
| Rule enum (append after the Scala entries, in settings order) | `src/main/kotlin/io/github/agadek/smartdiff/engine/api/EquivalenceRule.kt` |
| Rule interface | `src/main/kotlin/io/github/agadek/smartdiff/engine/declarations/model/EquivalenceRuleImpl.kt` |
| Pattern to copy | `src/main/kotlin/io/github/agadek/smartdiff/engine/declarations/psi/kotlin/KotlinEquivalenceRules.kt`, `…/psi/scala/ScalaEquivalenceRules.kt` (token-name-only rules, the model for all three languages), `…/psi/java/JavaEquivalenceRules.kt` |
| Shared helpers (`ParenthesesScan`, `printableInLiteral`, `isHexDigit`) | `src/main/kotlin/io/github/agadek/smartdiff/engine/declarations/psi/EquivalenceRuleSupport.kt` |
| Providers: override `equivalenceRules` | `src/main/kotlin/…/psi/python/PythonDeclarationProvider.kt`, `javascript/src/main/kotlin/…/psi/javascript/JavaScriptDeclarationProvider.kt`, `go/src/main/kotlin/…/psi/go/GoDeclarationProvider.kt` |
| New rule objects | `PythonEquivalenceRules` in `…/psi/python/`, `JavaScriptEquivalenceRules` in the `javascript` module, `GoEquivalenceRules` in the `go` module |
| Settings UI (groups by `EquivalenceRule.language`, should need no change) | `src/main/kotlin/io/github/agadek/smartdiff/ui/SmartDiffConfigurable.kt`, `SmartDiffSettings.kt` |
| Rule test pattern (real lexer, `sameUnder`, `rewrittenTexts`) | `src/test/kotlin/…/psi/kotlin/KotlinRuleTestSupport.kt`, `…/psi/LanguageTokens.kt`, `Kotlin*RuleTest.kt`, `Scala*RuleTest.kt` |
| Engine-level tests | `src/test/kotlin/…/psi/RewriteOnlyEngineTest.kt` (its "a Python file with the same edits stays changed" case must move to a language that still has no rules, as 09 moved it from Kotlin to Python); JS and Go engine tests go in their own modules' test folders |
| Classifier, outline and settings tests | `src/test/kotlin/…/analysis/RewriteRulesClassifierTest.kt`, `src/test/kotlin/…/ui/RewriteOnlyOutlineTest.kt`, `src/test/kotlin/…/ui/SmartDiffSettingsTest.kt` |

Token types are named by their `toString()` (for example the JS lexer's `JSTokenTypes`, `PyTokenTypes`, `GoTypes`/`GoTokenTypes`). Every rule test lexes its inputs with the real lexer of the language (JS, TypeScript, JSX and TSX separately where their lexers differ), so a wrong name fails a test instead of matching nothing.

## Spec and docs changes

- New: `docs/specs/declarations/11-javascript-python-go-equivalence-rules.md` and `…-details.md`, in the format of [docs/SPEC-GUIDE.md](../../SPEC-GUIDE.md), modelled on declarations/09: requirements with IDs, every accepted and rejected example above, the out-of-scope list, the order of each language's `ALL` and why, and a tests table mapping each test to requirements.
- Amend declarations/09 R16 ("Languages other than Java, Kotlin and Scala never produce rewrite-only declarations") and declarations/08's out-of-scope line so they name the new languages.
- `docs/specs/README.md`: list declarations/11 with its status.
- `docs/specs/languages/16-javascript-typescript.md` (its out-of-scope "Opt-in equivalence rules for JavaScript"), `04-python.md` and `17-go.md`: link declarations/11.
- `docs/ROADMAP.md` "Opt-in equivalence rules" and `CHANGELOG.md` `[Unreleased]` → `### Added`, in user terms.

## Acceptance criteria

1. The spec and details files are committed before the code (a separate first commit) and set to `implemented, <date>` in the last commit.
2. Each listed "same" and "not" example is a test, named with the requirement it checks (`// declarations/11 R…`), using the real lexer.
3. The settings page shows "JavaScript and TypeScript", "Python" and "Go" groups after "Scala", every new rule on by default, also for a user who turned another rule off; `SmartDiffSettingsTest` covers it.
4. `RewriteOnlyEngineTest` (and the modules' engine tests) show a declaration rewrite-only under each rule, a declaration with a rule turned off staying changed, a comment change staying changed, and each language never using another language's rules.
5. Classification does not change: golden fixtures pass without `-Psmartdiff.updateGolden=true`, and the corpus validation and relationship benchmark outputs are unchanged.
6. No test is skipped, disabled or weakened.
7. `./gradlew buildPlugin check` and `./gradlew verifyPlugin` (every IDE family: idea, pycharm, rustrover, webstorm, goland, phpstorm, datagrip) pass locally, with no new internal, experimental or deprecated API use and no change to `docs/PLATFORM-API.md`.
8. Manual check in a running IDE (`./gradlew runIde`, with the JavaScript, Python and Go plugins available, for example IntelliJ IDEA Ultimate): with "Mark rewrite-only changes" on, a JS, a Python and a Go file with one rewrite per rule and one real change show the rewritten declarations as `rewrite-only` and the real change as `changed`; turning one rule off turns its declaration back to `changed` without comparing again. Record the result in the PR; a check you could not run is reported as not run, never as passed.
9. One PR from `feat/js-python-go-equivalence-rules` to `main`, which names declarations/11, lists what was verified locally and how, and is not merged by the agent.
