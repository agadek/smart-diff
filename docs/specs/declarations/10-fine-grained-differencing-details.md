# Fine-grained differencing: details

Status: implemented, 2026-11-17, except the validation against Difftastic and GumTree (R21, R22), which is still proposed

**Feature:** [Fine-grained differencing](10-fine-grained-differencing.md)

## Design

### Where it plugs in

The roadmap first described this as a second [comparison engine](../../GLOSSARY.md#comparison-engine). [ADR 0014](../../adr/0014-changed-runs-come-from-the-declaration-engine.md) decides instead that the declaration engine computes the [changed runs](../../GLOSSARY.md#changed-run) itself, as one more field of its result, and that only the SMART Diff viewer and the outline's selection read it. A second engine would repeat the matching, and the [engine dropdown](../../GLOSSARY.md#engine-dropdown) picks one engine per language, so choosing it would lose the declaration outline.

- `ChangeNode` gets `changedRuns: List<RangePair> = emptyList()`, documented as "only for `Presence.MATCHED` with `Content.CHANGED` or `Content.FORMATTING_ONLY`, and the file region: each run of tokens the token comparison found different, old and new side; empty when the node is formatting-only or not compared by tokens (R9, R10)". A boolean `comparedByTokens` beside it says whether the comparison ran, so the viewer can tell a formatting-only node, whose native changes lose their word highlights (R12), from a node over the limits, which keeps them (R13). A run that only removes tokens has `new = null`, and one that only adds tokens has `old = null`. Runs come in old-side order, then new-side order for runs without an old side.
- Every range of a run lies inside the node's own range on its side, so the nothing-hidden rule ([engine contract](../engines/02-engine-contract.md) R1) is unchanged: a changed node's own ranges already count. The contract suite gains one check, that every run lies inside its node's range on its side and outside every child's range.
- `DeclarationEngine.VERSION` goes up by one, because results change.
- Other engines return no runs. The [line comparison](../engines/04-moved-lines.md) and the [structure-view fallback](../languages/06-structure-view-fallback.md) are left as they are.

### The token comparison

`ContentClassifier.changedRuns(old: Declaration, new: Declaration, policy: WhitespacePolicy, renamedFrom: String?): List<RangePair>`, called from `DeclarationAnalysis` for each node it classifies `Content.CHANGED` or `Content.FORMATTING_ONLY` (a formatting-only node gives no runs by definition, but is marked compared), and `changedRuns(old: FileRegion, new: FileRegion, policy)` for the file region (R2). `FileRegion` has no token offsets today, so it gets `tokenStarts: List<Int> = emptyList()`, filled from the same lexer pass by every provider that fills `ownTokenStarts`; a file region without them has no runs. It returns the empty list at once when either side `hasParseError`, the content is `UNANALYZED`, or either side's `ownTokenStarts` is empty because its provider gives no offsets (R9).

1. **Sequence.** Pair each own token with its start from `ownTokenStarts`; its end is the start plus the source text's length, which for a rename candidate's old side is the old name's (step 3). Leave out the tokens of `leadingWhitespace`, which belong to the line before (§6.1 of the [technical specification](../../TECHNICAL-SPECIFICATION.md#61-structure)). Children's tokens are already not own tokens (R3).
2. **Normalize.** `ContentClassifier.normalize` merges whitespace runs into new `⏎…` tokens without offsets, so the comparison uses a variant, `normalizeWithOffsets(tokens, starts, policy): List<Pair<Token, TextRange>>`, that gives each merged token the range from the first to the last whitespace token it replaces, and applies `applyIndentShift` for the Scala 3 shift as `comparable` does, with the shift measured over the whole declaration, leading whitespace included, as classification measures it. It applies the whitespace policy exactly as step 4 of classification does (§8): an `INSIGNIFICANT` whitespace token is dropped, a `LINE_BREAKS_SIGNIFICANT` one becomes `⏎` or is dropped, and so on, with the Kotlin and Scala variants of [declarations/07](07-kotlin-scala-formatting-details.md). A whitespace token that survives keeps its source range, so a significant line break or indentation can be a changed run (R4). Comments stay one token each, compared by exact text (R5). `TokenKind.SEPARATOR` tokens are left out, as in classification, so a separator never splits a whitespace run or makes a run by itself.
3. **Rename.** For a rename candidate, the old side's tokens equal to the old name are compared as the new name, as classification does, so the rename alone gives no run; the name already has its own label and is framed on selection.
4. **Compare.** Run Myers' difference algorithm, linear-space variant, over the two normalized sequences, with tokens equal when their type and text are equal. Ties go to the earliest match on the old side, so the result is deterministic. Of two runs of tokens that swapped places, the longest common subsequence keeps one matched; the other comes out as removed on one side and added on the other, so a reordering gives at least one run on each side (R7).
5. **Runs.** Each maximal stretch of the edit script that is not a match is one candidate run, with the range from its first token's start to its last token's end on each side. Two candidate runs are joined when only whitespace tokens, dropped or not, lie between them on both sides (R6). A run of only dropped whitespace cannot occur, because dropped tokens are not in the sequences.
6. **Limits.** Return the empty list when either sequence has more than 20 000 tokens or the edit distance, removed plus added tokens, passes 2 000 (R10). Myers needs time proportional to the sum of the sequence lengths times the edit distance, so these bounds keep one declaration under about 80 million steps, which the analysis time limit of [analysis limits](../engines/03-analysis-limits.md) covers on the corpus's largest files. A declaration over the bounds keeps the native word highlights, which is the conservative fallback. Both values are constants in `ContentClassifier` and are checked by tests.
7. Call `cancellation.checkCanceled()` once per declaration and every 1 000 steps of the algorithm.

Every token that differs is in a run by construction of the edit script (R8). A run can be larger than the edit a reviewer would name, for example when Myers aligns a common `)` differently, but never smaller.

### The SMART Diff viewer

`FilteringDiffViewer` gets `@Volatile var changedRuns: ChangedRuns` next to `hiddenLines`. `hide(lines, runs = ChangedRuns.NONE)` sets both and compares the lines again once, and `forgetHidden()` forgets both, so every place that clears the hidden lines clears the runs too. `ChangedRuns` is a new class in `ui`, built by `ChangedRuns.of(result, oldText, newText)` from the nodes compared by tokens: for each side, the line ranges each such node owns (its range minus its reported children's, as `HiddenLines` computes extents) and the runs inside them. `OutlineController` passes the runs where it calls `hide` with a result; the calls with `HiddenLines.NONE` and `forgetHidden()` clear them (R16). With the setting off, `ChangedRuns.of` is not called and the viewer keeps `NONE` (R1, R18).

`FilteringDiffViewer.apply(changes, isContentsEqual)` already maps each `SimpleDiffChange` to fade hidden ones. It now also replaces the change's `LineFragment` when:

- the viewer's highlight policy, read from `TextDiffSettings`, is `HighlightPolicy.BY_WORD` or `BY_CHAR`; with `BY_LINE`, `DO_NOT_HIGHLIGHT` or `BY_WORD_SPLIT` the change is left as it is (R15). `BY_WORD_SPLIT` splits a change into smaller ones by its inner fragments, so replacing them afterwards would leave the split and the highlights out of step; and
- every line of the change on both sides lies in lines owned by nodes compared by tokens (R11, R13). A change with lines on one side only, a pure insertion or deletion, is checked on that side alone; it gets the runs inside it, or an empty inner list (R12).

The replacement is a `LineFragmentImpl` with the same lines and offsets and new inner fragments: one `DiffFragmentImpl` per run part inside the change's offsets, relative to the change's start offsets on each side, with a run's old part and new part paired when both lie in the change and given alone otherwise. A change with no run inside gets an empty inner fragment list, so it keeps its line colour with no word highlights (R12). The change's index, `isExcluded` and `isSkipped` are kept, so next and previous change, the gutter actions and fading work as before (R14). Fading from `hiddenLines` is applied after, and a faded change keeps its replaced fragment.

A run that spans the boundary of a native change is cut to the part inside it; the rest falls in another native change or in lines the native diff shows as unchanged, which can happen only for whitespace the native diff ignores.

### The outline

`OutlineNavigator` highlights `changedRuns` with the existing `DETAIL` attributes next to `details` when a row built from a node is selected (R17). Nothing else in the outline, the summary, the Changes tree, the merge join or the benchmark reads the field (R19). The native viewers are not touched (R20).

### The setting

`SmartDiffSettings.highlightChangedTokens: Boolean = true`, shown on the settings page under the SMART Diff viewer as "Highlight changed tokens in the SMART Diff viewer" ([settings/01](../settings/01-settings-page.md) R23). It is a display setting: applying it rebuilds open outlines from the kept result, which calls `show` with new runs or with `NONE` and makes the viewer compare its lines again, without running the engine (R18).

### Validation against Difftastic and GumTree

A Gradle task `fineGrainedBaselines`, not part of `check` or the release, reads the validation corpus's changed declarations and writes, for each one, the characters inside changed runs, inside the native word highlights, inside Difftastic's changed tokens and inside GumTree's changed nodes. Difftastic and GumTree are run as external tools found on the `PATH` of the developer's machine; neither is a dependency of the plugin (out of scope), and the task stops with a message when either is missing. The record in [VALIDATION.md](../../VALIDATION.md) gives, per language, the median share of each declaration's characters each tool highlights, and lists the declarations where the changed runs highlight more than both baselines (R21).

A review of 30 declarations per language, chosen from the list where the tools disagree most, checks each by hand: a real edit outside the changed runs fails the validation (R22). A run that only highlights more than an edit is recorded but does not fail it.

### Edge cases

- **A formatting-only declaration:** compared with no runs, so its native changes keep their line colour without word highlights, and fade when the filter hides formatting-only changes (R2, R12).
- **Only reformatted lines between two edits:** their tokens match, so the native change over them keeps its colour without word highlights (R12).
- **A re-indented block in Python or Scala 3:** indentation is significant there, so the indentation tokens are runs and stay highlighted (R4).
- **A changed declaration with a changed child:** the child's lines are the child's; a native change across both is shown with the runs of both when both have runs, and with native highlights when the child has none, such as an unanalyzed child (R13).
- **An edit while the viewer shows runs:** `forgetRuns()` clears them before the viewer's own rediff, so the edited lines show native highlights until the new result arrives (R16).
- **A file over the analysis limits or a failed analysis:** no result, no runs, native highlights.

### Failure

An exception inside `changedRuns` other than cancellation is caught there: the node gets no runs and keeps its classification, which a test checks. The viewer falls back to the native fragment for any change it cannot map, for example when the document changed between the result and `apply`.

## Tests

Each test names its requirement, for example `// declarations/10-fine-grained-differencing R6`.

- `ChangedRunsClassifierTest`: one changed operator gives one run on each side (R2, R3); a re-wrapped argument list in Java gives no run (R4); a formatting-only declaration is compared and has no runs, and one over the limits is not compared (R2, R10); a re-indented Python body gives runs on the indentation (R4); a changed comment is a run (R5); two edits separated by spaces form one run, and by an unchanged token two runs (R6); two swapped statements give runs on both sides (R7); every differing token lies in a run, checked by a property test over random token edits (R8); a parse error, unanalyzed content and a provider without offsets give no runs (R9); 20 001 tokens and an edit distance of 2 001 give no runs (R10); a rename alone gives no run; equal inputs give equal results; an exception gives no runs and keeps the classification.
- Engine contract suite: every run lies inside its node's range and outside its children's, for every engine.
- Golden fixtures under `src/test/testData/declarations/changed-runs/` for Java, Kotlin, Scala 2, Scala 3 and Python, with the runs printed under each changed row: a reformatted method with one edit, a re-indented block, a changed comment, reordered statements.
- `FilteringDiffViewerRunsTest` (platform test): a native change inside a declaration with runs shows exactly the runs as inner fragments (R11); a change with only reformatted lines, a change inside a formatting-only declaration and a pure insertion without runs show none (R12); a change reaching into the file region without runs, or an added method, keeps the native inner fragments (R13); next and previous change, gutter actions and fading are unchanged (R14); highlighting by lines shows no inner fragments (R15); an edit and the outline turned off restore native fragments (R16); the native side-by-side and unified viewers show native fragments (R20).
- `OutlineNavigatorTest`: selecting a changed row highlights its runs on both sides (R17).
- `OutlineControllerTest`: turning the setting off and applying it restores native highlights without running the engine (R1, R18); the outline text, summary and Changes tree rows of the golden fixtures are unchanged with the setting on (R19).
- `SmartDiffConfigurableTest`: the checkbox is on by default and stored in `SmartDiffSettings` (R1).
- The validation record for R21 and R22, written by `fineGrainedBaselines` and the manual review.

## Open questions

- Whether a run should widen to the smallest enclosing expression, such as a whole argument, when it covers most of it. Token runs are kept for now; the validation's list of runs larger than both baselines will show whether reviewers would read expressions more easily.
- Whether the unified viewer should get the same highlights. It is a separate viewer class, so it would need its own SMART Diff variant.
