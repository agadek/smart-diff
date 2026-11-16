# Kotlin and Scala formatting: details

Status: accepted, 2026-11-16

**Feature:** [Kotlin and Scala formatting](07-kotlin-scala-formatting.md)

## Design

### Where it plugs in

Today Kotlin and Scala 2 use `WhitespacePolicy.LINE_BREAKS_SIGNIFICANT`, which counts every line break, and Scala 3 uses `INDENTATION_SIGNIFICANT`, which counts every line break with its indentation as written ([technical specification §8](../../TECHNICAL-SPECIFICATION.md#8-declaration-engine-classification)). Wrapping an argument list therefore makes a declaration `Content.CHANGED`.

Three new `WhitespacePolicy` values carry the new rules, so TOML (which also uses `LINE_BREAKS_SIGNIFICANT`) and Python (`INDENTATION_SIGNIFICANT`) stay as they are (R11):

| Value | Used by | Rule |
| --- | --- | --- |
| `KOTLIN_LINE_BREAKS` | `KotlinDeclarationProvider`, `KotlinReferenceResolver`, the outline's `"kotlin"` mapping | `LINE_BREAKS_SIGNIFICANT` plus the ignorable line breaks below |
| `SCALA2_LINE_BREAKS` | `ScalaDeclarationProvider` and `ScalaReferenceResolver` for Scala 2, the outline's `"Scala"` mapping when the dialect is Scala 2 | as Kotlin, with the Scala 2 token sets |
| `SCALA3_INDENTATION` | `ScalaDeclarationProvider` and `ScalaReferenceResolver` for Scala 3 | `INDENTATION_SIGNIFICANT` plus the bracket line breaks below and the relative indentation of R8 |

Every place that tests for `INDENTATION_SIGNIFICANT` or `LINE_BREAKS_SIGNIFICANT` by identity (`ContentClassifier`, `DeclarationAnalysis` around the leading-indentation check, `RenameNameMapping`, `OutlineController`'s language mapping) handles the new values the same way as the value they extend, then applies the extra rule. Add `WhitespacePolicy.extends` or two small predicates (`countsLineBreaks`, `countsIndentation`) instead of repeating `when` branches.

### One normalizer for both paths (R10)

`ContentClassifier.normalize` works on PSI tokens; `RenameNameMapping.tokens` re-tokenizes text. Both decide per whitespace run whether it counts. Move that decision into one function, `LineBreakRules.ignorable(policy, previous: String?, next: String?, depth: BracketDepth): Boolean`, that takes the text of the token before and after the run and the bracket state, and call it from both. `RenameNameMapping`'s text tokenizer already splits on whitespace outside strings and comments; it must also split punctuation (`(`, `)`, `[`, `]`, `{`, `}`, `,`, `.`) into their own tokens for the new policies, so the previous and next tokens are the same as on the PSI path. A test runs both paths over every fixture in the Tests table and expects the same verdict.

### Bracket state (R1, R4, R6)

While walking a declaration's own tokens, keep a stack of the open brackets by text: `(`, `[` and `{`. A line break run is "inside brackets" when the top of the stack is `(` or `[`. An opening `{` pushes a block, so inside a lambda or block within parentheses the top is `{` and the run is compared as in a block (R4). A closing token pops its match; an unbalanced closing token (own tokens skip children, so a nested declaration's brackets are absent) empties the stack instead of failing, which only makes later runs count, the conservative direction. Brackets inside string and comment tokens are not counted because those are single tokens.

Kotlin string templates are split into several tokens by the lexer (`${`, `}`); `${` counts as `{`.

### Ignorable line breaks (R1, R2, R3, R5)

A whitespace run containing a line break is dropped, as if it had none, when any of these holds, and otherwise becomes `⏎` as today (R5):

1. The run is inside brackets (R1).
2. The previous token is one of the "continues" set (R2).
3. The next token is one of the "closes" set (R3).

| Set | Kotlin | Scala 2 |
| --- | --- | --- |
| continues | `,` `(` `[` `{` `=` `->` `.` `?.` `+` `-` `/` `%` `&&` `\|\|` `?:` `==` `!=` `===` `!==` `<=` `>=` `..` `..<` `+=` `-=` `*=` `/=` `%=` | `,` `(` `[` `{` `=` `=>` `<-` `.` |
| closes | `)` `]` `}` `,` `.` `?.` `?:` `&&` `\|\|` | `)` `]` `}` `,` `.` `=>` |

Left out on purpose, because a line break next to them can end a statement or change parsing: `<` and `>` (generics), `*` (star imports and projections), `!`, `++`, `--`, `as`, `in`, `is`, and a line break before `{` (a trailing lambda on the next line is a separate statement in Kotlin) or before `=`. Scala 2's infix operators are identifiers and get no entry; an operator line break stays changed, which is conservative.

A run that is dropped is dropped entirely; a run that counts is one `⏎`, so blank lines are still formatting as today.

### Scala 3 (R6, R7, R8)

Under `SCALA3_INDENTATION` a line-break run becomes `⏎` plus its indentation as today, except that it is dropped when it is inside brackets and also directly after `,`, `(` or `[` or directly before `)` or `]` (R6). Only those positions, because a line break after `=>` or `:` inside parentheses can open an indented region. Every other run keeps its indentation as written (R7).

For R8, before comparing, compute for each side the smallest indentation of any counted line-break run in the declaration's own tokens together with its leading whitespace's indentation, and strip that many characters from every counted run on that side, only when every counted run starts with the same characters (spaces or tabs) up to that length. If the two sides then compare equal, the declaration is formatting-only. If the stripped prefixes are not uniform (tabs on one line, spaces on another), compare as written. The existing `sameIndentation` check in `DeclarationAnalysis` for the leading run is relaxed under `SCALA3_INDENTATION` only when the R8 shift applies to the whole declaration.

A whole-declaration shift that moves it into another block changes its container, so matching reports a move; the shift rule never decides structure.

### Parse errors (R9)

`ContentClassifier.classify` already returns `Content.UNANALYZED` for a parse error before step 4; the new rules run only in step 4 and add nothing there.

### Failure

The rules are pure token comparisons. If one throws, the classifier catches it and returns `Content.CHANGED`, the conservative result ([ADR 0003](../../adr/0003-conservative-classification.md)).

## Tests

| Test | Checks |
| --- | --- |
| `ContentClassifierTest`: per new policy, wrapped argument list, wrapped parameter list, split call chain on `.` and `?.`, line break after `=` and after `->`/`=>`, line break before `?:`, `&&`, `\|\|`, line break before `+` (changed), line break before `{` (changed), line break added between two statements in a lambda inside parentheses (changed), blank lines added inside parentheses | R1–R5 |
| `ContentClassifierTest`: Scala 3 wrapped arguments (formatting-only), line break after `=>` inside parentheses (changed), indentation of an ordinary line changed (changed), whole declaration shifted by two spaces (formatting-only), mixed tabs and spaces shift (changed) | R6–R8 |
| `ContentClassifierTest`: parse error with wrapped arguments is unanalyzed; TOML and Python cases from the existing suite unchanged | R9, R11 |
| Golden fixtures in `src/test/testData/kotlin`, `scala2` and `scala3`: one file each with a wrapped function, a split chain and a real change, checked for the labels | R1–R8 |
| `RenameNameMappingTest` and a rename-grouping fixture per language: a renamed function whose call sites were also re-wrapped stays a rename with grouped usages; the PSI and text paths agree on every `ContentClassifierTest` input | R10 |
| Existing suites for Java, Python, Rust, TOML, YAML, shell, properties, JSON and Groovy pass unchanged | R11 |
| `RelationshipBenchmarkTest` with the default corpus and `relationships-corpus-full.txt`: no case labelled `CHANGED` becomes `FORMATTING_ONLY`, and per-language formatting-only recall is not lower; record the run in VALIDATION.md | R12 |
