# JavaScript, Python and Go equivalence rules: details

Status: implemented, 2026-11-27

**Feature:** [JavaScript, Python and Go equivalence rules](11-javascript-python-go-equivalence-rules.md)

## Design

### Where it plugs in

Everything around the rules is already built by [declarations/08](08-equivalence-rules-details.md) and [09](09-kotlin-scala-equivalence-rules-details.md): `ChangeNode.rewriteRules`, `ContentClassifier.rewriteRules`, the `rewrite-only` tag, the filter entry, the summary count and the settings. This feature adds rules and nothing else (R1).

- `EquivalenceRule` gets, after the Scala entries and in this order:
  - `JS_NUMERIC_LITERALS("JavaScript and TypeScript", "Numeric literals in another base or format")`, `JS_STRING_QUOTES("JavaScript and TypeScript", "Strings with other quotes or escapes")`, `JS_TRAILING_COMMAS("JavaScript and TypeScript", "Trailing commas")`, `JS_REDUNDANT_PARENTHESES("JavaScript and TypeScript", "Redundant parentheses")`;
  - `PYTHON_NUMERIC_LITERALS("Python", "Numeric literals in another base or format")`, `PYTHON_STRING_QUOTES("Python", "Strings with other quotes or escapes")`, `PYTHON_TRAILING_COMMAS("Python", "Trailing commas")`, `PYTHON_REDUNDANT_PARENTHESES("Python", "Redundant parentheses")`;
  - `GO_NUMERIC_LITERALS("Go", "Numeric literals in another base or format")`, `GO_STRING_LITERALS("Go", "Strings and runes with other escapes or quotes")`, `GO_TRAILING_COMMAS("Go", "Trailing commas")`, `GO_REDUNDANT_PARENTHESES("Go", "Redundant parentheses")`, `GO_IMPORT_ORDER("Go", "Import order in an import block")`.

  `SmartDiffConfigurable` groups `EquivalenceRule.entries` by language in declaration order, so the groups appear after Scala with no UI change (R2). `disabledEquivalenceRules` stores rule names, so the new rules are on for every user, also one who turned another rule off (R3).
- `JavaScriptEquivalenceRules` in the `javascript` module, `PythonEquivalenceRules` in `engine.declarations.psi.python` and `GoEquivalenceRules` in the `go` module, each an object with `ALL` in its fixed order. `JavaScriptDeclarationProvider`, `PythonDeclarationProvider` and `GoDeclarationProvider` return them from `equivalenceRules` (R21). JavaScript, TypeScript, JSX and TSX share one provider and one lexer family, so R20 needs no extra code.
- The rules match `Token.type`, which records each lexer token type's `toString()` (`JS:NUMERIC_LITERAL`, `Py:INTEGER_LITERAL`, `int`, …), and use no class from the JavaScript, Python or Go plugin, so they load without them and the Plugin Verifier sees no new API. Each rule test lexes its inputs with the real lexer, so a wrong name fails a test instead of silently matching nothing.
- `ParenthesesScan` gets four optional parameters, each with a default that keeps the Kotlin and Scala rules unchanged: `excludedFirst` (content that starts with one of these tokens keeps its pair), `contentOnOneLine` (content with a line break keeps its pair), `sameLineAfter` (for these tokens before `(`, a line break between `(` and the content keeps the pair) and `layout` (the tokens the scan skips, whitespace and comments by default).
- `EquivalenceRuleSupport.kt` also gets `canonicalDecimal`, `hexFloatValue`, `meaningfulIndices` and `removeTrailingCommas`, and its helpers become public, because the `javascript` and `go` modules compile against the root plugin as a separate Kotlin module and cannot see `internal` ones.
- The JavaScript provider marks the whitespace of languages/16 R14 and R15 significant by turning it into `TokenKind.OTHER` tokens. The JavaScript rules skip a token whose text is only whitespace as layout, so `return (x)` still has `return` before its `(`, and never remove it.
- `LanguageTokens.kt` moves from the root test sources to the root test fixtures, so the `javascript` and `go` modules' rule tests lex with the same helper.

Whitespace is compared under each provider's policy, unchanged: `INSIGNIFICANT` with the significant ranges of languages/16 R14 and R15 for JavaScript, `INDENTATION_SIGNIFICANT` for Python, `LINE_BREAKS_SIGNIFICANT` for Go. A rule never adds or removes whitespace, except the Go import order rule, which writes its block in one layout (below).

### Order and the smallest set

Each language's `ALL` is: parentheses, trailing commas, numeric literals, strings, and for Go import order last.

- Parentheses run before trailing commas, so `f((a),)` loses the pair while its comma is still there to show it is an argument, then loses the comma.
- Literals rewrite single tokens and touch no bracket, so their place after the bracket rules changes nothing.
- Go's import order runs last, so it sorts specs by their canonical string text and `"fmt"` and `` `fmt` `` sort together.

`ContentClassifier.rewriteRules` keeps its leave-one-out computation of the smallest set unchanged.

### JavaScript numeric literals (R4)

Each `JS:NUMERIC_LITERAL` token is replaced by a token of the same type whose text is a canonical form of its value:

- Separators (`_`) are removed. A literal whose digits start with `0` followed by another digit is a legacy octal or a legacy decimal (`017`, `08`) and keeps its text.
- A literal ending in `n` is a BigInt: the prefix `0x`, `0o` or `0b` in any case is read as its base, and the value is written in decimal followed by `n`.
- Any other literal is a Number: a prefixed literal is read as an integer in its base, a decimal one with `java.math.BigDecimal` (which reads `.5`, `5.` and exponents), and the value is written as `stripTrailingZeros().toString()`. The exact decimal value is compared, not the rounded double, so `9007199254740993` and `9007199254740992` stay different. `toString` keeps a huge exponent short.
- A literal that does not parse keeps its text.

### JavaScript string quotes and escapes (R5)

Each `JS:STRING_LITERAL` token, which holds its quotes, is decoded and written back as `"`, the canonical characters, and `"`:

- `\n \t \r \b \f \v \' \" \\`, `\0` not followed by a digit, `\xHH`, `\uHHHH` and `\u{H…}` up to `10FFFF` are decoded.
- A line continuation (`\` before a line break), `\1` to `\9`, `\0` followed by a digit, and any other escape keep the literal's text: some are legacy octal, the rest identity escapes whose meaning a reviewer should see.
- The canonical text writes `"` and `\` as `\"` and `\\`, a line feed and a carriage return as `\n` and `\r`, every other non-printable character, U+2028 and U+2029 included, as `\uXXXX`, and every other character as itself.
- **Directives.** A string literal that can be a directive keeps its meaning: one whose previous meaningful token is `{`, `;` or none, and whose next is `;`, `}`, a line break or none. Such a literal is rewritten only when neither its body has a backslash nor a quote character of either kind, and then only its quotes change: `'use strict'` and `"use strict"` are the same, `'use\x20strict'` and `'use strict'` are not. Other expression statements in that position are treated the same way, which is conservative.
- Template literals are `JS:BACKQUOTE` and `JS:STRING_TEMPLATE_PART` tokens and are never rewritten. JSX attribute values are `XML_ATTRIBUTE_VALUE_TOKEN` tokens in JSX and TSX and are never rewritten either; in a `.ts` file, where `<a b="c"/>` is not JSX, the lexer reads `"c"` as a string literal of a comparison.

### JavaScript trailing commas (R6)

Removes a `JS:COMMA` whose next meaningful token is `JS:RPAR`, `JS:RBRACKET` or `JS:RBRACE`, and whose previous meaningful token is none of `JS:COMMA`, `JS:LBRACKET`, `JS:LPAR` and `JS:LBRACE`. JavaScript allows a trailing comma in every list closed by those brackets (array and object literals, destructuring patterns, import and export braces, arguments, parameters, TS enums, interfaces and type literals), and in each it means nothing. In an array, the comma after a hole counts: `[1,,]` has length 2, so a comma after a comma, or right after `[`, is never removed. A comma before `>` is never removed, so `<T,>`, which TSX needs, stays.

### JavaScript redundant parentheses (R7, R8)

`ParenthesesScan` on `JS:LPAR` … `JS:RPAR`, with `(`, `[` and `{` as brackets, a top-level `JS:COMMA` or `JS:IN_KEYWORD` excluded, and this table:

| Before the `(` | After the `)` |
| --- | --- |
| `JS:RETURN_KEYWORD`, `JS:THROW_KEYWORD` | statement end |
| `JS:EQ` and every compound assignment (`+=` to `**=`, `<<=`, `>>=`, `>>>=`, `&=`, `\|=`, `^=`, `&&=`, `\|\|=`, `??=`) | statement end, `JS:COMMA`, `JS:RPAR` |
| `JS:LPAR`, `JS:COMMA` | `JS:COMMA`, `JS:RPAR` |

A statement end is `JS:SEMICOLON`, `JS:RBRACE`, the end of the tokens, or a line break followed by a keyword that starts a statement (`const`, `let`, `var`, `function`, `class`, `return`, `throw`, `if`, `for`, `while`, `do`, `try`, `switch`, `break`, `continue`, `import`, `export`) outside `(` and `[`. An identifier on the next line is not trusted: with automatic semicolon insertion and TypeScript's `as` and `satisfies`, it can continue the expression.

After `return` and `throw`, `sameLineAfter` keeps a pair whose content starts on the line after the `(`: without the parentheses, `return` followed by a line break returns `undefined`. This keeps the common `return (` + JSX + `)` layout a change, which is the conservative answer.

What the table never matches (R8): an operand, a call's own parentheses and an IIFE (followed by `(` or preceded by a name), an arrow function's parameters (followed by `=>`) and its body (preceded by `=>`), `(0, obj.f)()` (a comma inside), `delete`, `typeof`, `void`, `new` and `await` operands (preceded by the keyword), and a `for` header (preceded by `for`). Template substitutions open with their own token, which is a bracket for the scan.

### Python numeric literals (R9)

Each `Py:INTEGER_LITERAL`, `Py:FLOAT_LITERAL` and `Py:IMAGINARY_LITERAL` token is replaced by a token of the same type:

- Integers: underscores removed, `0x`, `0o` and `0b` in any case read as their base, the value written in decimal. A literal with a leading `0` and another digit is a Python 2 octal, an error in Python 3, and keeps its text.
- Floats: underscores removed, parsed with `BigDecimal`, written as `stripTrailingZeros().toString()`.
- Imaginary: the `j` or `J` dropped, the rest read as a float, written as for floats followed by `j`.
- The token type keeps the kinds apart, so `1` and `1.0` stay different; a literal that does not parse keeps its text.

### Python string quotes and escapes (R10)

Each `Py:SINGLE_QUOTED_STRING` token (prefix, quotes and body in one token) is rewritten when its prefix, in any case, is none, `u` or `b`, and it is not triple-quoted:

- `\\ \' \" \a \b \f \n \r \t \v`, octal `\o` to `\ooo` up to `\377`, and `\xhh` are decoded; in a string also `\uXXXX` and `\UXXXXXXXX` up to `10FFFF`.
- `\N{…}`, a line continuation, a larger octal escape and any other escape (such as `\d`) keep the text. In a bytes literal, `\u`, `\U` and `\N` are unknown escapes and keep the text, as does any character outside ASCII.
- A string is written as `"`, the canonical characters as for JavaScript, and `"`. A bytes literal is written as `b"`, each byte as itself when it is printable ASCII other than `"` and `\`, as `\"`, `\\` or `\xHH` otherwise, and `"`. The `b` keeps a string and a bytes literal apart.
- Raw strings are never rewritten. f-strings (`Py:FSTRING_START` … `Py:FSTRING_END`) and triple-quoted strings (`Py:TRIPLE_QUOTED_STRING`) are other token types and are never rewritten either.

### Python trailing commas (R11)

A `Py:COMMA` whose previous meaningful token is not `(`, `[`, `{` or `,` is looked at when its next meaningful token is `Py:RPAR`, `Py:RBRACKET` or `Py:RBRACE`:

| Closing bracket | The comma is removed when |
| --- | --- |
| `)`, its `(` preceded by a name other than `match` and `case`, by `)` or `]` (a call, a `def`, a class's bases), or by `import` | always |
| `)`, any other `(` (a parenthesized tuple or expression) | another top-level comma stays in the pair |
| `]`, its `[` preceded by a name, `)`, `]`, `}` or a string (a subscript) | another top-level comma stays in the pair |
| `]`, any other `[` (a list display) | always |
| `}` (a dict or set display) | always |

So `(x,)`, `return x,` (no closing bracket at all), `a[1,]` and `tuple[int,]` stay a change. `match` and `case` are names to the lexer, and `match (x,):` matches a tuple. An f-string's replacement field ends with `Py:FSTRING_FRAGMENT_END`, not `Py:RBRACE`, so `f"{a,}"`, which formats a tuple, keeps its comma. A comma inside brackets on its own line leaves its line break: under `INDENTATION_SIGNIFICANT`, `f(\n    a,\n)` and `f(a)` stay different, as line breaks inside brackets already count for formatting-only.

### Python redundant parentheses (R12, R13)

`ParenthesesScan` on `Py:LPAR` … `Py:RPAR`, with `(`, `[` and `{` as brackets, a top-level `Py:COMMA`, `Py:FOR_KEYWORD`, `Py:YIELD_KEYWORD` and `Py:COLONEQ` (`:=`) excluded, `*` and `**` excluded as the first content token, `contentOnOneLine` on, and this table:

| Before the `(` | After the `)` |
| --- | --- |
| `Py:RETURN_KEYWORD` | statement end |
| `=` and every augmented assignment (`+=`, `-=`, `*=`, `/=`, `//=`, `%=`, `**=`, `@=`, `&=`, `\|=`, `^=`, `<<=`, `>>=`) | statement end, `,`, `)` |
| `(`, `,` | `,`, `)` |

A statement end is the end of the tokens, `Py:SEMICOLON`, or `Py:STATEMENT_BREAK`, the zero-length token the lexer puts at the end of each logical line. A line break alone is never a statement end, so a backslash continuation and an implicit line join keep the pair. The `,` row needs the pair directly inside `(` or `[`, as in declarations/09.

### Go numeric literals (R14)

`int`, `hex`, `oct` and `bin` tokens are integers: underscores removed, `0x`, `0o`/`0O`, `0b` and a leading `0` read as their base, the value written in decimal, the type set to `int`. `float` tokens are read as decimal or, with `0x`, as hexadecimal floats (mantissa times a power of two, exact), and written as `stripTrailingZeros().toString()`. Imaginary tokens (`decimali` and `floati`, which the lexer also gives prefixed imaginary literals) drop the `i`, read the rest as an integer or float, a body of decimal digits with a leading `0` as decimal, and write the value followed by `i` with the type set to `decimali`. Integer, float and imaginary results keep different token types, so `1` and `1.0` stay different; `char` tokens are not numeric literals, so `'a'` and `97` stay different. A literal that does not parse keeps its text and type.

### Go strings and runes (R15)

`string` and `raw_string` tokens are decoded to bytes and written as a `string` token with the text `"`, each byte as itself when it is printable ASCII other than `"` and `\`, as `\"`, `\\` or `\xHH` otherwise, and `"`:

- In an interpreted string: characters are their UTF-8 bytes; `\a \b \f \n \r \t \v \\ \"` are their byte; `\xHH` and `\ooo` (exactly three octal digits, at most `\377`) are single bytes; `\uXXXX` and `\UXXXXXXXX` are the UTF-8 bytes of the code point. A surrogate, a code point past `10FFFF`, `\'` and any other escape keep the text.
- In a raw string: the UTF-8 bytes of the text, carriage returns dropped.

`char` tokens are decoded to one code point (`\x`, octal, `\u` and `\U` read as the code point, `\'` allowed, `\"` not) and written as `'`, the character when printable and not `'` or `\`, otherwise `\U` and eight hex digits, and `'`.

### Go trailing commas (R16)

Removes a `,` whose next meaningful token is `)` or `}` and whose previous meaningful token is none of `,`, `(` and `{`. A comma before `]` is never removed: `type T[P *C,] …` needs it. The lexer inserts no semicolon after a comma, so the line break before a closing bracket on its own line stays where it was and is compared as `LINE_BREAKS_SIGNIFICANT` compares it: `T{1, 2,}` and `T{1, 2}` are the same, `T{1, 2,\n}` and `T{1, 2}` are not.

### Go redundant parentheses (R17, R18)

`ParenthesesScan` on `(` … `)`, with `(`, `[` and `{` as brackets, a top-level `,` excluded and `{` excluded anywhere, and this table:

| Before the `(` | After the `)` |
| --- | --- |
| `return` | statement end, `,` |
| `=`, `:=` and every compound assignment (`+=`, `-=`, `*=`, `/=`, `%=`, `&=`, `\|=`, `^=`, `<<=`, `>>=`, `&^=`) | statement end, `,`, `)` |
| `(`, `,` | `,`, `)` |

A statement end is `;`, `}`, the lexer's zero-length `new line` token (Go inserts a semicolon after `)` at a line break), or the end of the tokens. `return (x), nil` loses the pair; `(*T)(x)` keeps it, being followed by `(`; content with `{` keeps it, because a composite literal in an `if`, `for` or `switch` header needs its parentheses.

### Go import order (R19)

Imports belong to the [file region](../../GLOSSARY.md#file-region) (languages/17 R9), whose own tokens are what the rule sees. For each `import` followed by `(`:

1. Collect the meaningful tokens up to the matching `)`. If a comment is among them, or a token other than an `identifier`, `.`, a string token, `;` or `new line`, leave the block alone.
2. Split them into specs, each an optional alias (`identifier` or `.`) and a string, ended by `;`, `new line` or `)`.
3. Sort the specs by path text, then alias, and write the block back as `import`, a space, `(`, then for each spec a line break, a tab, the alias and a space when there is one, the path and a `new line` token, then a line break and `)`.

Line breaks between specs are written the same way on both sides, so blank lines between groups do not count; their only meaning is to gofmt. Specs in separate `import` declarations are never merged, and an alias stays with its path.

### Failure

As in declarations/08: the rules are pure token functions run after classification, and a rule that throws leaves the node with an empty set, shown as changed ([ADR 0003](../../adr/0003-conservative-classification.md)).

## Tests

| Test | Checks |
| --- | --- |
| `JavaScriptNumericLiteralRuleTest`: the lexer's type name, bases, case, separators, exponents, leading and trailing dots, BigInt against Number, BigInt bases, legacy octal and `08` kept, exact values that round together | R4, R20 |
| `JavaScriptStringQuoteRuleTest`: quotes, each simple escape, `\x`, `\u`, `\u{…}`, quotes inside, a line continuation, legacy octal and unknown escapes kept, a directive, a template literal and a JSX attribute value untouched, in JS, TS, JSX and TSX | R5, R20 |
| `JavaScriptTrailingCommaRuleTest`: arrays, objects, calls, parameters, imports, TS enums and interfaces, an array hole, `<T,>` in TSX | R6, R20 |
| `JavaScriptRedundantParenthesesRuleTest`: each row, each statement end, an identifier on the next line, a line break after `return (`, a comma, `in`, an operand, a call, an IIFE, an arrow's parameters and body, nested pairs | R7, R8 |
| `JavaScriptRewriteOnlyEngineTest` (javascript module): a function with each rule in `.js`, `.ts`, `.jsx` and `.tsx` files, a comment change, a value on the line after `return (`, Java's octal and Kotlin's `\$` escapes staying changed | R1, R4 to R8, R20, R21 |
| `PythonNumericLiteralRuleTest`, `PythonStringQuoteRuleTest`, `PythonTrailingCommaRuleTest`, `PythonRedundantParenthesesRuleTest`: every example of R9 to R13 and their nearest rejected cases, f-strings left alone | R9 to R13 |
| `GoNumericLiteralRuleTest`, `GoStringLiteralRuleTest`, `GoTrailingCommaRuleTest`, `GoRedundantParenthesesRuleTest`, `GoImportOrderRuleTest`: every example of R14 to R19, `{1, 2,\n}` against `{1, 2}`, a comment in an import block, a changed alias | R14 to R19 |
| `GoRewriteOnlyEngineTest` (go module): a function with each rule, the file region with import order, a comment change, JavaScript's and Kotlin's escapes staying changed | R1, R14 to R17, R19, R21 |
| `RewriteOnlyEngineTest`: a Python function with each Python rule, a tuple comma and a comment change staying changed, Kotlin's `\$` escape and a quoted character against a number staying changed; the existing case "a Python file with the same edits stays changed" becomes a Groovy file, since Python now has rules | R1, R9 to R12, R21, R22 |
| `RewriteRulesClassifierTest`: the smallest set with Python parentheses and a trailing comma together, and a comment change with no rules | R1 |
| `RewriteOnlyOutlineTest`: a disabled Python rule keeps a Python row changed and leaves Java, JavaScript and Go rows rewrite-only | R3 |
| `SmartDiffSettingsTest`: the new groups and their order on the settings page, all new rules on with a Java and a Kotlin rule turned off before, a Python rule turned off leaving the others on | R2, R3 |

The golden fixtures, the relationship benchmark and the corpus validation must give the same output as before, since classification does not change.

## Open questions

- **Object keys.** `{'a': 1}` and `{a: 1}` are the same object in JavaScript, but a quoted key can be any string. The options are a fifth JavaScript rule for keys that are valid identifiers, or leaving it out. Left out.
- **Python implicit concatenation.** `'a' 'b'` and `'ab'` are the same string. Left out until a reviewer asks.
