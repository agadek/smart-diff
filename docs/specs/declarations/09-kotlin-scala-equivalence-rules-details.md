# Kotlin and Scala equivalence rules: details

Status: proposed, 2026-11-17

**Feature:** [Kotlin and Scala equivalence rules](09-kotlin-scala-equivalence-rules.md)

## Design

### Where it plugs in

Everything around the rules is already built by [declarations/08](08-equivalence-rules-details.md): `ChangeNode.rewriteRules`, `ContentClassifier.rewriteRules`, the `rewrite-only` tag, the filter entry, the summary count and the settings. This feature adds rules and nothing else (R1).

- `EquivalenceRule` gets, after the Java entries and in this order: `KOTLIN_NUMERIC_LITERALS("Kotlin", "Numeric literals in another base or format")`, `KOTLIN_STRING_ESCAPES("Kotlin", "Strings and characters with other escapes")`, `KOTLIN_TEMPLATE_BRACES("Kotlin", "Redundant braces in string templates")`, `KOTLIN_REDUNDANT_PARENTHESES("Kotlin", "Redundant parentheses")`, `SCALA_NUMERIC_LITERALS("Scala", "Numeric literals in another base or format")`, `SCALA_STRING_ESCAPES("Scala", "Strings and characters with other escapes")` and `SCALA_REDUNDANT_PARENTHESES("Scala", "Redundant parentheses")`. `SmartDiffConfigurable` already groups `EquivalenceRule.entries` by language in declaration order, so the groups appear after Java with no UI change (R2). `disabledEquivalenceRules` stores rule names, so the new rules are on for every user, also one who turned a Java rule off (R3).
- `KotlinEquivalenceRules` in `engine.declarations.psi.kotlin` and `ScalaEquivalenceRules` in `engine.declarations.psi.scala`, each an object with `ALL` in its fixed order, as `JavaEquivalenceRules`. `KotlinDeclarationProvider` and `ScalaDeclarationProvider` return them from `equivalenceRules` (R15). The Scala rules match token types by their names only and use no Scala plugin class, so they load safely without it, though only the Scala provider reaches them.
- `ScalaDeclarationProvider` returns the same rules for both dialects; the classifier already compares under the dialect's `WhitespacePolicy` (`SCALA2_LINE_BREAKS` or `SCALA3_INDENTATION`), so R13 needs no rule code.
- Kotlin scripts use `KotlinDeclarationProvider` too (R14), so their declarations and their file region get the rules with no extra code.
- `EquivalenceRuleImpl`'s KDoc says a rule "never changes a token's type or kind". The template braces rule writes `${` as the short form's `$`, a token of another type, so the KDoc becomes "never changes a token's kind, and replaces tokens only by tokens of the same kind".

Token types are named here by their constants, `KtTokens` for Kotlin and `ScalaTokenTypes` for Scala; the rules compare `Token.type`, which records each constant's `toString()`. Each rule test lexes its inputs with the real lexer, so a wrong name fails a test instead of silently matching nothing.

### Order and the smallest set

`KotlinEquivalenceRules.ALL` is: parentheses, template braces, numeric literals, string escapes. `ScalaEquivalenceRules.ALL` is: parentheses, numeric literals, string escapes.

The rules rewrite disjoint tokens, with one interaction: the template braces rule reads the string part after a template to decide whether the next character could continue a name, and the string escape rule later merges that part with its escapes. Running template braces first means it sees the part as written, where an `ESCAPE_SEQUENCE` never continues a name (the lexer ends a short template's name before a backslash), which is what the compiler does. `ContentClassifier.rewriteRules` keeps its leave-one-out computation of the smallest set unchanged.

### Kotlin numeric literals (R4)

Each `INTEGER_LITERAL` and `FLOAT_LITERAL` (`FLOAT_CONSTANT`) token is replaced by a token of the same type whose text is a canonical form of its value:

- Integers: underscores removed, the suffix read as one of none, `L`, `U` or `UL` (`u`, `U`, `uL`, `UL` written in upper case), the `0x`/`0X` or `0b`/`0B` prefix read as its base, then the value written in decimal followed by the suffix. Kotlin has no octal literals, and a hexadecimal or binary literal is its plain value, not a two's-complement bit pattern: `0xFFFF_FFFF` is the `Long` 4294967295. Without a suffix the literal's type follows from its value and the expected type, and both sides have the same value in the same place, so the same value is the same type.
- Floating point: underscores removed, an `f`/`F` suffix read as `F` and no suffix as `D`, then parsed with `java.math.BigDecimal` and written as `stripTrailingZeros().toPlainString()` plus the suffix. `1e3`, `1000.0` and `1_000.0` are the same; `1.0` and `1.0f` are not. As for Java, the exact decimal value is compared, so `0.1f` and `0.100000001f` stay different even though they round to the same `Float`.
- A literal that does not parse keeps its text.

### Kotlin string escapes (R5)

A regular string is `OPEN_QUOTE` (`"`), then parts, then `CLOSING_QUOTE`. Within one, each run of adjacent `REGULAR_STRING_PART` and `ESCAPE_SEQUENCE` tokens is replaced by one `REGULAR_STRING_PART` whose text is the canonical form of the characters the run stands for:

- `\uXXXX` (exactly four hex digits) and `\t \b \n \r \' \" \\ \$` are decoded. An escape the decoder does not know keeps the whole run's text.
- The canonical text writes `"`, `\` and `$` as `\"`, `\\` and `\$`, every other non-printable character as `\uXXXX`, and every other character as itself. Writing every `$` escaped keeps an escaped `\$x` and a literal `$` before a non-name character apart from a template, whose `SHORT_TEMPLATE_ENTRY_START` or `LONG_TEMPLATE_ENTRY_START` token is never part of a run.
- A raw string, whose `OPEN_QUOTE` is `"""`, is never rewritten.

Each `CHARACTER_LITERAL` token, which holds its quotes, is decoded the same way and written back as `'`, the canonical character with `'` and `\` written as `\'` and `\\`, and `'`.

### Kotlin template braces (R6)

A `LONG_TEMPLATE_ENTRY_START` (`${`) … `LONG_TEMPLATE_ENTRY_END` (`}`) pair is replaced by one `SHORT_TEMPLATE_ENTRY_START` token `$` followed by the content, when:

1. the content, with whitespace dropped, is exactly one `IDENTIFIER` that is not backquoted, or one `THIS_KEYWORD`, and
2. the token after the closing `}` is not a `REGULAR_STRING_PART` whose first character is a letter, a digit or `_` (Kotlin's `Character.isJavaIdentifierPart` without `$`).

The short form needs no rewriting. A template with a qualified name, a call, `this@label` or any other expression keeps its braces.

### Kotlin redundant parentheses (R7, R8)

Removes a matched `LPAR` … `RPAR` pair whose content is not empty and has no top-level `COMMA` or `ARROW`, when the meaningful tokens around it fit one row, skipping whitespace and comments:

| Before the `(` | After the `)` |
| --- | --- |
| `RETURN_KEYWORD`, `THROW_KEYWORD` | statement end |
| `EQ`, `PLUSEQ`, `MINUSEQ`, `MULTEQ`, `DIVEQ`, `PERCEQ` | statement end, `COMMA`, `RPAR` |
| `LPAR`, `COMMA` | `COMMA`, `RPAR` |

A statement end is one of:

- `SEMICOLON`, `RBRACE`, `ELSE_KEYWORD`, or the end of the declaration's own tokens;
- a line break followed by an `IDENTIFIER` or one of `VAL_KEYWORD`, `VAR_KEYWORD`, `FUN_KEYWORD`, `CLASS_KEYWORD`, `OBJECT_KEYWORD`, `INTERFACE_KEYWORD`, `RETURN_KEYWORD`, `THROW_KEYWORD`, `IF_KEYWORD`, `WHEN_KEYWORD`, `FOR_KEYWORD`, `WHILE_KEYWORD`, `DO_KEYWORD`, `TRY_KEYWORD`, `THIS_KEYWORD` and `SUPER_KEYWORD`, only when the pair is not inside `(` or `[` (the bracket stack of [declarations/07](07-kotlin-scala-formatting-details.md#bracket-state-r1-r4-r6)).

The list after a line break is an allow list on purpose: Kotlin lets `.`, `?.`, `::`, `?:`, `&&`, `||`, `as` and `as?` continue an expression on the next line, and a `{` there may pass a lambda, so any of them keeps the pair (R8). An identifier cannot continue: Kotlin allows no line break before an infix function's name.

What the table never matches (R8): an operand (preceded or followed by an operator), a call's own parentheses (preceded by a name), an `if`, `when`, `while` or `for` header (preceded by its keyword), a destructuring declaration (`val (a, b)` has a comma and is preceded by `val`; `for ((x) in xs)` and `{ (x) -> }` are followed by `in` and `->`), a function type's parameter list (followed by `->`), a type in a declaration (preceded by `:`), and `return@label (x)` (the label sits between `return` and the pair). `if ((x))` and `foo((a + b))` match through the `LPAR` row.

Pairs are removed from the inside out until none matches, as in Java. Line breaks that were inside a removed pair are then outside brackets for `normalize`, so a pair spread over several lines stays changed unless the line breaks are also ignorable where they now are, which is the conservative result.

### Scala numeric literals (R9)

Each `tINTEGER` and `tFLOAT` token is replaced by a token of the same type with a canonical text:

- Integers: underscores removed, an `l`/`L` suffix read as `L`, a `0x`/`0X` prefix read as hexadecimal, then the value written in decimal followed by the suffix. A hexadecimal literal is the two's-complement bit pattern of an `Int` (32 bits) or a `Long` (64 bits), as the compiler reads it, so `0xFFFFFFFF` is -1. A literal in any other form, such as a binary `0b` literal or one with a leading `0`, keeps its text (out of scope).
- Floating point: underscores removed, an `f`/`F` suffix read as `F` and a `d`/`D` suffix or none as `D`, then parsed with `BigDecimal` and written as for Kotlin. `1.0`, `1d`, `1e0` and `1.0D` are the same.
- A literal that does not parse keeps its text.

### Scala string escapes (R10)

Each `tSTRING` token, which holds the whole plain literal with its quotes, and each `tCHAR` token is decoded and written back in a canonical form, as for Kotlin:

- `\uXXXX` with one or more `u`, and `\b \t \n \f \r \" \' \\`, are decoded. Octal escapes are an error since Scala 2.13 and keep the text.
- A unicode escape that stands for `"`, `'`, `\`, `\n` or `\r` keeps the literal's text as written. Scala 2.12 and earlier translate unicode escapes before lexing, so such an escape means something else there, and the rule never assumes a Scala version.
- The canonical text writes `"`, `'` and `\` as `\"`, `\'` and `\\`, every other non-printable character as `\uXXXX`, and every other character as itself.
- `tMULTILINE_STRING` and every interpolated-string token (`tINTERPOLATED_STRING` and its parts) are never rewritten; an interpolator, such as `raw`, decides what an escape means.

### Scala redundant parentheses (R11, R12)

Removes a matched `tLPARENTHESIS` … `tRPARENTHESIS` pair whose content is not empty and has no top-level `tCOMMA`, `tCOLON`, `tASSIGN` or `tFUNTYPE` (`=>`), and no `tUNDER` (`_`) anywhere in it, when the meaningful tokens around it fit one row:

| Before the `(` | After the `)` |
| --- | --- |
| `kRETURN`, `kTHROW` | statement end |
| `tASSIGN` | statement end, `tCOMMA`, `tRPARENTHESIS` |
| `tLPARENTHESIS`, `tCOMMA` | `tCOMMA`, `tRPARENTHESIS` |

A statement end is one of:

- `tSEMICOLON`, `tRBRACE`, `kELSE`, or the end of the declaration's own tokens;
- a line break followed by one of `kVAL`, `kVAR`, `kDEF`, `kTYPE`, `kCLASS`, `kOBJECT`, `kTRAIT`, `kCASE`, `kIMPORT`, `kRETURN`, `kTHROW`, `kIF`, `kWHILE`, `kFOR`, `kTRY` and `kELSE`, only when the pair is not inside `(` or `[`.

Scala's rules differ from Kotlin's in these ways, each for a reason:

- No identifier after a line break: Scala 3 lets a symbolic operator at the start of the next line continue the expression (`(a + b)` then `* c` on the next line), and its indentation rules make an indented identifier's meaning depend on the region, so no identifier is trusted.
- No compound assignment row: `+=` and the like are method names in Scala (`tIDENTIFIER`), not assignment tokens.
- A top-level `=` is excluded because `f((x = 1))` passes an assignment's `Unit` while `f(x = 1)` is a named argument; a top-level `,` because the pair is then a tuple and auto-tupling may apply; a top-level `:` because `(xs: _*)` and type ascriptions are not plain expressions in every position; `=>` because it makes a lambda; and `_` anywhere because the parentheses can bound a placeholder's anonymous function.
- `==`, `<=` and `>=` are `tIDENTIFIER` tokens, so the `tASSIGN` row is never a comparison.

Line breaks and indentation after removal are compared under `SCALA2_LINE_BREAKS` or `SCALA3_INDENTATION` (R13), as for Kotlin.

### Failure

As in declarations/08: the rules are pure token functions run after classification, and a rule that throws leaves the node with an empty set, shown as changed ([ADR 0003](../../adr/0003-conservative-classification.md)).

## Tests

| Test | Checks |
| --- | --- |
| `KotlinNumericLiteralRuleTest`: hex, binary and decimal, underscores, prefix and digit case, every suffix and its case, `Int` against `Long` and unsigned, `0xFFFF_FFFF` against `4294967295`, exponents, `Float` against `Double`, values that differ | R4 |
| `KotlinStringEscapeRuleTest`: `\u` escapes, every simple escape, `\$` against a literal `$` and against a template, quotes in strings and characters, a raw string left unchanged, an unknown escape | R5 |
| `KotlinTemplateBracesRuleTest`: a name, `this`, whitespace inside the braces, a following letter, digit, `_`, punctuation and escape, a qualified name, a call, a backquoted name, `this@label` | R6 |
| `KotlinRedundantParenthesesRuleTest`: each row of the table, each statement end, each continuing token on the next line, a line break inside an argument list, nested pairs, an operand, a call, an `if` header, a destructuring declaration, a function type, `return@label`, a comma inside | R7, R8 |
| `ScalaNumericLiteralRuleTest`: hex and decimal, underscores, case, `L` and `l`, two's-complement hex `Int` and `Long`, `f`, `d` and no suffix, a binary and a leading-zero literal kept | R9 |
| `ScalaStringEscapeRuleTest`: `\u` with several `u`, every simple escape, a unicode escape of each excluded character kept, an octal escape kept, triple-quoted, `s`, `f` and `raw` strings left unchanged | R10 |
| `ScalaRedundantParenthesesRuleTest`: each row, each statement end, a leading symbolic operator and an identifier on the next line, a tuple, an ascription, `(xs: _*)`, a lambda, an assignment inside, a placeholder, a named argument | R11, R12 |
| `RewriteOnlyEngineTest`: a Kotlin function, a `.kts` script's file region and a Gradle build script section with each Kotlin rule; a Scala 2 and a Scala 3 method with each Scala rule, including a Scala 3 method whose parentheses spanned lines; the same Java edits in a Kotlin and a Scala file staying changed. The existing case "a Kotlin file with the same edits stays changed" becomes a Python file, since Kotlin now has rules | R1, R13, R14, R15, R16 |
| `RewriteRulesClassifierTest`: the smallest set with template braces and escapes together, a Kotlin rewrite with a comment change | R1, R6 |
| `RewriteOnlyOutlineTest`: a disabled Kotlin rule keeps a Kotlin row changed and leaves Java and Scala rows rewrite-only | R3 |
| `SmartDiffSettingsTest`: the Kotlin and Scala groups and their order, all new rules on by default with a Java rule turned off before | R2, R3 |

The relationship benchmark and the corpus validation must give the same output as before, since classification does not change.

## Open questions

- **`->` and `=>` bodies.** Parentheses around a `when` branch's or a `case` clause's value are a common clean-up. The options are to add `ARROW` and `tFUNTYPE` rows with the statement-end column, after fixtures show that function types and lambda parameters never match, or to leave them out as now. Left out until a reviewer asks.
- **Scala 3 `if … then` conditions.** `if (a) then b` and `if a then b` are the same in Scala 3, but removing an `if`'s own parentheses is a different rewrite from R11. The options are a fourth Scala rule or leaving it out. Left out.
