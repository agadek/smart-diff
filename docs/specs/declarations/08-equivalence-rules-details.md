# Opt-in equivalence rules: details

Status: proposed, 2026-11-17

**Feature:** [Opt-in equivalence rules](08-equivalence-rules.md)

## Design

### Where it plugs in

As for [comment-only changes](../outline/11-comment-only-changes-details.md), the engine computes one more fact per matched node and the outline decides from the settings whether to use it. [Change classification](02-change-classification.md) is unchanged: a rewritten literal is still `Content.CHANGED`, so the Changes tree, the merge outline, the benchmark and the corpus validation see no difference, and `DeclarationEngine.VERSION` stays the same.

- A new `engine.api` enum `EquivalenceRule(language, displayName)` with `JAVA_NUMERIC_LITERALS`, `JAVA_STRING_ESCAPES` and `JAVA_REDUNDANT_PARENTHESES`.
- `ChangeNode` gets `rewriteRules: Set<EquivalenceRule> = emptySet()`, documented as "only for `Presence.MATCHED` with `Content.CHANGED`; empty when the node is not rewrite-only under any set of rules". The file region's node gets it too (R9).
- A `DeclarationProvider` gets `equivalenceRules: List<EquivalenceRuleImpl> = emptyList()`; only `JavaDeclarationProvider` returns any (R18). `EquivalenceRuleImpl` has `rule: EquivalenceRule` and `rewrite(tokens: List<Token>): List<Token>`.
- `ContentClassifier` gets `rewriteRules(old, new, policy, parseError, signatureChanged, rules): Set<EquivalenceRule>`, called from the two `DeclarationAnalysis` call sites next to `isCommentOnly`, only when the result is `Content.CHANGED`.
- `OutlineModel` gets `Tag.REWRITE_ONLY("rewrite-only")` and `ChangeType.REWRITE_ONLY("Rewrite-only", setOf(Tag.REWRITE_ONLY))`.
- `SmartDiffSettings` gets `markRewriteOnly: Boolean = false` and `disabledEquivalenceRules: Set<String> = emptySet()` (rule names, so a rule added later is on by default, R3), persisted with the other outline settings and compared in the display-settings check, so applying either redraws open outlines without comparing again (R17).

### The rule set of a node (R4 to R7)

`rewriteRules` returns the empty set unless all of these hold:

1. `parseError` and `signatureChanged` are false (R5).
2. The token texts, with `TokenKind.SEPARATOR` dropped as in `classify`, differ.
3. The `TokenKind.COMMENT` tokens are equal by text, in order, on both sides (R5: a comment change is never rewrite-only).
4. With every rule of the provider applied to both sides, in a fixed order (parentheses, then numeric literals, then string escapes), `comparable(normalize(tokens, policy), policy)` is equal on both sides (R4, R6).

It then returns the rules that are needed: a rule stays in the set when leaving it out (and applying the others) makes the sides differ. The three Java rules rewrite disjoint token types, so this is the one smallest set. The outline treats a node as rewrite-only when `rewriteRules` is not empty and is a subset of the enabled rules (R7).

A formatting-only node never reaches this, since its content is not `CHANGED`.

### Java rules

Token types are the Java lexer's (`JavaTokenType`), as `Token.type` already records them.

**Numeric literals (R10).** Each `INTEGER_LITERAL`, `LONG_LITERAL`, `FLOAT_LITERAL` and `DOUBLE_LITERAL` token is replaced by a token of the same type whose text is a canonical form of its value:

- Integers: underscores removed, the `0x`/`0X`, `0b`/`0B` or leading `0` prefix read as its base, then the value written in decimal with the type's suffix in upper case (`L` for long). An `int` or `long` literal is read as the two's-complement value the compiler gives it, so `0xFFFFFFFF` and `-1` stay different tokens (one is a literal, the other a unary minus and a literal) while `0xFFFFFFFF` and `037777777777` are the same.
- Floating point: underscores removed, then parsed with `java.math.BigDecimal` for decimal literals and by the hexadecimal float rules for `0x…p…` literals, and written as `BigDecimal.stripTrailingZeros().toPlainString()` plus `F` or `D` for the type. A literal without a suffix is `D`. `1e3`, `1000.0` and `1000d` are the same; `1.0` and `1.0f` are not (R10).
- A literal that does not parse (it cannot, without a parse error, but the rule never assumes it) keeps its text.

**String escapes (R11).** Each `STRING_LITERAL` and `CHARACTER_LITERAL` token's text between its quotes is decoded: `\uXXXX` (any number of `u`), octal escapes, `\b \t \n \f \r \s \" \' \\` are turned into the characters they stand for, and the token's text becomes the quote, the decoded characters with quotes and the backslash written as `\'`, `\"` and `\\`, every other non-printable character written as `\uXXXX`, and the quote. The canonical text is only compared, never compiled, so it need not be valid Java; a unicode escape of a quote or line terminator is already a parse error, since Java translates unicode escapes before it lexes. `TEXT_BLOCK_LITERAL` is never rewritten (out of scope). A literal with an escape the decoder does not know keeps its text.

**Redundant parentheses (R12).** Removes a matched `LPARENTH` … `RPARENTH` pair whose content is not empty and has no top-level `COMMA` or `ARROW`, when the meaningful tokens around it are, before and after:

| Before the `(` | After the `)` |
| --- | --- |
| `RETURN_KEYWORD`, `THROW_KEYWORD` | `SEMICOLON` |
| `EQ` or a compound assignment (`PLUSEQ`, `MINUSEQ`, `ASTERISKEQ`, `DIVEQ`, `PERCEQ`, `ANDEQ`, `OREQ`, `XOREQ`, `LTLTEQ`, `GTGTEQ`, `GTGTGTEQ`) | `SEMICOLON`, `COMMA`, `RPARENTH` |
| `LPARENTH`, `COMMA` | `COMMA`, `RPARENTH` |

Whitespace and comments are skipped when looking for the tokens around the pair. The table never matches a cast (a cast's `)` is followed by its operand), a call's own parentheses (preceded by a name), a lambda's parameters (followed by `->`) or an operand of an operator. `if ((x))` and `foo((a + b))` match through the `LPARENTH` row, and the `if`'s own parentheses never do, since `IF_KEYWORD` is not in the table. A pair is removed together with no whitespace; `normalize` already merges what is left. Pairs are removed from the inside out until none matches, so `return ((a));` becomes `return a;`.

### Labels and summary (R4, R8, R15, R16)

In `OutlineModel`'s tag computation, when `markRewriteOnly` is on and the node's `rewriteRules` is a non-empty subset of the enabled rules, `Content.CHANGED` adds `Tag.REWRITE_ONLY` instead of `Tag.CHANGED`. Comment-only is checked first; a node cannot be both, since rewrite-only needs equal comments and comment-only needs different ones. `moved`, usages and children are untouched (R8). The summary line's order list puts `rewrite-only` right after `comment-only` (R15). The refactorings view builds its other-changes rows from the same tags (R16).

### Filter and settings (R2, R3, R13, R14)

- The outline's filter menu lists `ChangeType.REWRITE_ONLY` only when `markRewriteOnly` is on, and has a "Mark Rewrite-Only Changes" checkbox next to "Mark Comment-Only Changes", bound to the same setting (R2).
- With the option off, no node has `Tag.REWRITE_ONLY`, so a remembered hidden `REWRITE_ONLY` type hides nothing, and it is kept for when the option is on again (R14).
- `SmartDiffConfigurable` adds "Mark rewrite-only changes" under "Mark comment-only changes", with an indented "Java" group of one checkbox per rule ("Numeric literals in another base or format", "Strings and characters with other escapes", "Redundant parentheses"), enabled only while it is on (R3), and a "Rewrite-only" checkbox in "Hide from the outline", enabled only while it is on (R13, [settings/01](../settings/01-settings-page.md) R22).
- `ChangesTreeActions` and `MergeOutlinePanel` keep their explicit lists without `COMMENT_ONLY`; `REWRITE_ONLY` is left out of them the same way (out of scope).

### Failure

`rewriteRules` and each rule's `rewrite` are pure token functions run after classification. If one throws, the node keeps an empty set and is shown as changed, the conservative result ([ADR 0003](../../adr/0003-conservative-classification.md)).

## Tests

| Test | Checks |
| --- | --- |
| `JavaNumericLiteralRuleTest`: every base, underscores, prefix, digit and suffix case, `int` against `long`, `float` against `double`, exponents, hex floats, two's-complement hex and octal, values that differ | R10 |
| `JavaStringEscapeRuleTest`: unicode escapes with several `u`, octal, every simple escape, `\s`, quotes in strings and characters, a different character, a text block left unchanged, an unknown escape | R11 |
| `JavaRedundantParenthesesRuleTest`: each row of the table, nested pairs, a cast, a call, a lambda, an operand, `if ((x))`, a comma inside, comments and whitespace around the pair | R12 |
| `RewriteRulesClassifierTest`: the smallest set, a comment change, signature changed, parse error, formatting and a rewrite together, a rewrite and a real change together, a provider without rules | R4, R5, R6, R18 |
| `RewriteOnlyEngineTest`: a Java method with each rule, a field initializer, the file region, a Kotlin file with the same edits staying changed | R4, R9, R10, R11, R12, R18 |
| `RewriteOnlyOutlineTest`: option off shows `changed` and the same rows and summary as before; option on shows `rewrite-only` with `moved` kept; a disabled rule keeps the row changed; summary order; hiding with the option on and off; only the outline offers the type; the refactorings view's other changes | R1, R4, R7, R8, R13, R14, R15, R16 |
| `SmartDiffSettingsTest`: off by default, rules on by default, persisted, counted as display settings, round-tripped on the settings page, rule checkboxes enabled only with the option | R1, R2, R3, R17 |
| `OutlineIntegrationTest` declarations 08 R2 R17: the toggle and a rule redraw without a new comparison and keep the selection | R2, R17 |
| `ChangesTreeFilterTest` and `MergeOutlineIntegrationTest`: no rewrite-only entry | Out of scope |

The relationship benchmark and the corpus validation must give the same output as before, since classification does not change.
