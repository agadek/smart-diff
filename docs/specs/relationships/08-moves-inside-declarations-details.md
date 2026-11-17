# Moves inside declarations: details

Status: proposed, 2026-11-17

**Feature:** [Moves inside declarations](08-moves-inside-declarations.md)

## Design

### Where it plugs in

A [code move](../../GLOSSARY.md#code-move) is a [relationship](../../GLOSSARY.md#relationship) of a new kind, so it follows [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md): it adds to the change tree and never replaces or hides a change (R19).

- `RelationshipKind` gets `CODE_MOVE`, documented as "statements removed from one matched declaration and added to the same or another matched declaration (relationships/08)". The [relationship model](02-relationship-model.md) R3 gains it.
- A code move's endpoints name the source declaration on the old side and the target declaration on the new side, each with the declaration's whole range, as for every other kind: `RelationshipEndpoint.range` identifies the node, and `OutlineModel` and `RefactoringsModel` key rows and covered ranges by it. Both endpoints name the same declaration for a move inside one declaration.
- The moved statements are in the evidence, in `EvidenceOrder`: `Evidence.Content(shared, total, oldRange, newRange)`, whose ranges are the moved statements on each side, then `Evidence.Score(similarity)`, the lowest statement similarity of the move. The contract check of relationships/02 R17 gains one rule for `CODE_MOVE`: the content evidence's ranges lie inside the endpoint declarations' ranges.
- `ChangeNode` gets `codeMovedTo: List<Subject> = emptyList()` (only for `MATCHED`: targets of code moved out of it, for R13's note) and `codeMoves: List<CodeMove> = emptyList()` (only for `MATCHED`: the moves into it, for R12's rows), both computed from the relationships, with their ranges from the content evidence, as relationships/02 R8 requires once it lists them (this change extends R8 with R3). `CodeMove(source: Subject, oldRange: TextRange, newRange: TextRange, content: Content, changedRuns: List<RangePair>)` is a new `engine.api` class; `content` is `UNCHANGED`, `FORMATTING_ONLY` or `CHANGED` under the language's whitespace policy, as [change classification](../declarations/02-change-classification.md) step 4 decides it for a declaration.
- `DeclarationEngine.VERSION` goes up by one, because results change (relationships/02 R24).
- The code moves change no `presence`, `content`, `moved` or other field of any node, so the labels, the summary line (R20), the Changes tree and the merge join are unchanged (R19).

### Statements

A `DeclarationProvider` gets `statements(declaration: Declaration, file: PsiFile): List<Statement>`, empty by default, so only the providers of R22 return any. `Statement(range: TextRange, depth: Int, parent: Int?)` is a new class in `engine.declarations.model`; a statement is a direct child of a code block in the declaration's body, at any depth of nesting, except the bodies of child declarations, which are the children's own.

| Language | Statements |
| --- | --- |
| Java | `PsiStatement` children of a `PsiCodeBlock`, and the statements of a switch rule's block |
| Kotlin | Children of a `KtBlockExpression`, including declarations of local variables, but not local functions or classes, which are child declarations where the provider has them |
| Scala 2 and Scala 3 | Children of a `ScBlockExpr` or of a Scala 3 indented block, through the adapter the Scala provider already uses |
| Python | Children of a `PyStatementList` |

The provider reads them in the same read action that builds the declaration tree. A statement's tokens are the declaration's own tokens inside its range, from `ownTokenStarts`.

### The algorithm

`CodeMoveDetector`, in `engine.declarations.analysis`, runs after matching, classification, extraction and inlining detection, and the reference pass, so R10 can leave out what those link, and before the result is built.

1. **Token runs.** For each matched declaration whose content is `CHANGED`, take its [changed runs](../../GLOSSARY.md#changed-run) from the token comparison of [fine-grained differencing](../declarations/10-fine-grained-differencing-details.md#the-token-comparison). A declaration that comparison does not run on, because of a parse error or its limits (20 000 own tokens per side, an edit distance of 2 000), takes no part (R25). The edit-distance limit means a declaration whose moved and other edited code exceeds about 1 000 tokens per side has no code moves; the moves of such rewrites are rare and stay ordinary changes, and the benchmark records any it misses.
2. **Removed and added statements.** An old-side statement is *removed* when at least 0.8 of its tokens that are not whitespace or comments lie in the old parts of the declaration's changed runs; a new-side statement is *added* the same way with the new parts. The share allows a moved statement to keep a few tokens that the comparison matched elsewhere by chance, such as a `return` or a closing brace. Only the outermost such statement counts: a removed statement inside a removed statement is part of it (R7). Code linked as an extraction's or an inlining's content evidence is left out (R10). Removed and added declarations take no part, since their code is already one change (R9).
3. **Limit.** When either side has more than 2 000 removed or added statements, stop with no code moves (R21). Step 4 compares every removed with every added statement, so 2 000 bounds it at 4 million scores; the file is then a rewrite rather than a reorder.
4. **Similarity.** For each removed and added statement pair with the same statement kind (the PSI class, so an `if` never pairs with a `for`), score the Dice coefficient of their bags of adjacent token pairs, tokens other than whitespace and comments, as the matcher's content similarity does ([technical specification §7](../../TECHNICAL-SPECIFICATION.md#7-declaration-engine-matching)). Pairs from 0.8 up are options (R5).
5. **Assignment.** Pair statements with the global assignment of [relationship matching](03-relationship-matching-details.md): the largest total score whose chosen pairs are each at least 0.1 ahead of every rival, where a rival is any option, below the threshold too, of either statement (R6, R8). Equal totals go to old-side source order, then new-side order.
6. **Runs.** Join paired statements into code moves: two pairs join when their old statements are next to each other in the same block, and their new statements are too, in the same order, with only whitespace and comments between them. A code move is one such maximal run (R2).
7. **Size.** Drop a code move with fewer than 12 tokens that are not whitespace or comments (R4). Twelve tokens is about one full statement such as `total += item.price() * item.count();`; shorter single statements such as `i++` or `return x;` repeat too often to pair safely.
8. **Content.** Compare the move's old and new statements with the token comparison of fine-grained differencing and classify them with the whitespace policy, giving `content` and `changedRuns` (R14, R15). Before comparing, the indentation of the first statement's line on each side is removed from every line break's indentation inside the move, with `applyIndentShift` as Scala 3 uses it, so under a policy that counts indentation (`WhitespacePolicy.countsIndentation`: `INDENTATION_SIGNIFICANT` for Python, `SCALA3_INDENTATION` for Scala 3) a block moved into or out of a nesting level with its lines indented alike is formatting-only, while a change of indentation inside the block stays a change (R27). The move's confidence is `HIGH` when `content` is `UNCHANGED` or `FORMATTING_ONLY` and `MEDIUM` otherwise (R11).
9. Call `cancellation.checkCanceled()` once per declaration and every 1 000 scores.

The steps are deterministic: every sort has a total order on source offsets, so equal requests give equal relationships (relationships/02 R19).

### Outline

- **Container tree.** `OutlineModel` adds one row per `CodeMove` under its target declaration's row, after the target's child declarations, sorted by new-side start. The title is `moved code lines A–B → C–D` (R12); the row's tags are `Tag.MOVED`, plus `Tag.CHANGED` when `content` is `CHANGED` (R14). The row's frames are the move's old and new ranges and its details are `changedRuns` (R15). A move whose source differs from the target gets the note `from <source>`, the source's label as the outline shows it; the source's row gets `code moved to <target>`, listing at most 3 names then `+N more`, as extraction notes do (R13). A code move row is not a change of its own for the summary (R20), and `HiddenLines` gives it no extent of its own: its lines belong to the target declaration (and on the old side to the source), so hiding the row fades nothing while that declaration is shown (R28).
- **Refactorings view.** `RefactoringsModel` gives a `CODE_MOVE` relationship the row text `moved code in parse` when both endpoints name one declaration and `moved code parse → finish` otherwise, with the confidence word, and frames both endpoint ranges on selection (R16). Its declarations stay in `Other changes` with their labels (relationships/06 R15).
- **Filters.** `RelationshipKind.CODE_MOVE` is a new entry `Moved code` in the relationship kind filter and the settings page's "Hide from the refactorings view" ([settings/01](../settings/01-settings-page.md) R17, one checkbox per kind). In the container tree the change type filter hides a code move row by its tags, so hiding `moved` hides a move without edits and keeps one with `changed` (R17).
- **Markers.** `MovedCodeMarkers.targets` reads shown rows whose `moved` is true and that have both ranges, so code move rows are built with `moved = true` and their two ranges, and get markers on the first line of each range with `Moved from line N` and `Moved to line N` tooltips, and clicking one selects the row (R18).
- **Setting.** `SmartDiffSettings.detectCodeMoves: Boolean = true`, "Detect code moved inside declarations" ([settings/01](../settings/01-settings-page.md) R24). It is read into the request (a new `ComparisonRequest.detectCodeMoves`, default true, so other engines and tests see no change); changing it compares open diffs again (R1, R24).

### Benchmark

[RELATIONSHIP-BENCHMARK.md](../../RELATIONSHIP-BENCHMARK.md) gains the label `code-move` with the source declaration, the target declaration and the moved line ranges, and a case set per language of R22: a statement block reordered in one method, a block moved between two methods, a moved block with a renamed variable inside, a moved `if` with a changed condition, and the near misses that must give none: a duplicated block left in both places, two equal logging statements swapped, a block moved into a new method (an extraction), a short statement moved alone. The benchmark scores code moves as it scores other relationships: precision, recall and wrong pairs, and the gate is no wrong code move (R23).

### Edge cases

- **Two statements swapped:** the token comparison keeps one in place and reports the other as removed and added, so one code move is listed, as the longest common subsequence does for declarations.
- **A block moved and its enclosing `if` changed:** the outer `if` is not removed as a whole, so its moved inner statements can still form a move.
- **The same block in two places on the new side:** the two pairs score within 0.1 of each other, so neither is paired (R6).
- **A move from a method into its own lambda:** the lambda's statements are statements of the same declaration, so the move is listed as `moved code in <method>`.
- **Statements with a parse error nearby:** the declaration has no changed runs and takes no part (step 1).
- **A block moved deeper in Python:** the relative indentation is the same, so the move is formatting-only and high (R27).
- **A large moved block:** a move whose declaration passes the token comparison's limits is not found (R25), and stays a removed and added part of the changed declarations.
- **The setting off:** no code move relationships, and no fields set, so the outline is as before (R1).

### Failure

An exception in `CodeMoveDetector` other than cancellation is caught: the result has no code moves and is otherwise unchanged, which a test checks. A provider whose `statements` throws gives that declaration no statements.

## Tests

Each test names its requirement, for example `// relationships/08-moves-inside-declarations R6`.

- `CodeMoveDetectorTest`, per language of R22: a reordered block in one method (R2, R3, R12); a block moved between two methods with both notes (R3, R13); a block of 11 tokens gives none and of 12 gives one (R4); a moved statement at similarity 0.79 gives none and 0.8 one (R5); a duplicated target within 0.1 gives none (R6); a nested statement is part of its parent's move (R7); a statement is in at most one move (R8); code moved into an added method or out of a removed one gives none (R9); an extraction's code gives no code move (R10); a move with only formatting changes is high and one with a renamed variable is medium (R11); a 2 001-statement rewrite gives none (R21); a declaration over the token comparison's limits gives none (R25); a Python block moved one level deeper with the same relative indentation is formatting-only and high, and one with an inner line re-indented is changed (R27); a Groovy or JSON file gives none (R22); equal requests give equal relationships; an exception gives no moves.
- Golden fixtures under `src/test/testData/declarations/code-moves/` for Java, Kotlin, Scala 2, Scala 3 and Python, with the expected container tree and refactorings view text (R12 to R16, R20).
- Engine contract suite: the content evidence ranges of every `CODE_MOVE` relationship lie inside its endpoint declarations' ranges.
- `OutlineModelCodeMoveTest`: row title, tags, note and frames (R12 to R15); the change type filter hides a move without edits and keeps one with edits (R17); the summary line is the same with and without moves (R20); hiding a code move row fades no lines in the SMART Diff viewer (R28); the labels of the source and target are unchanged (R19).
- `RefactoringsModelTest`: the row text, the confidence word and the `Moved code` kind filter (R16, R17).
- `MovedCodeMarkersTest`: a code move gets markers on both sides and clicking selects its row (R18).
- `OutlineControllerTest`: the setting off gives the outline without code moves, and applying it compares again (R1, R24).
- The relationship benchmark's code move cases (R23).

## Open questions

- Whether a block of statements whose order changed inside the move should still be one move. It is two moves for now, joined only when the order is kept (step 6).
- Whether the Changes tree's declaration rows should list code moves. They are out of scope until the outline's rows have been used.
