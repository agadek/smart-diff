# Comment-only changes: details

Status: implemented, 2026-11-16

**Feature:** [Comment-only changes](11-comment-only-changes.md)

## Design

### Where it plugs in

The engine computes one more fact per matched node; the outline decides from a setting whether to use it. [Change classification](../declarations/02-change-classification.md) is unchanged: a comment change is still `Content.CHANGED` (R6 there), so the Changes tree, the merge outline, the benchmark and the corpus validation see no difference (R1, out of scope).

- `ChangeNode` gets `commentOnly: Boolean = false`, documented as "only for `Presence.MATCHED` with `Content.CHANGED`". The file region's node gets it too (R7).
- `ContentClassifier` gets `isCommentOnly(old, new, policy, parseError, signatureChanged): Boolean`, called from the two `DeclarationAnalysis` call sites of `classify` only when the result is `Content.CHANGED`.
- `OutlineModel` gets `Tag.COMMENT_ONLY("comment-only")` and `ChangeType.COMMENT_ONLY("Comment-only", setOf(Tag.COMMENT_ONLY))`.
- `SmartDiffSettings` gets `markCommentOnly: Boolean = false`, persisted with the other outline settings and compared in the display-settings check that outline/10 R5 uses, so applying it redraws open outlines without comparing again (R12).

### The rule (R3, R4, R5)

`isCommentOnly` returns true when all of these hold:

1. `parseError` is false and `signatureChanged` is false (R4).
2. Both token lists, with `TokenKind.SEPARATOR` dropped as in `classify`, are not equal by text (otherwise the node is not changed).
3. After dropping every `TokenKind.COMMENT` token, `normalize(tokens, policy)` is equal on both sides. Dropping a comment can leave two whitespace runs next to each other; `normalize` already merges adjacent whitespace tokens into one run, so a removed line comment leaves at most one line break. This also makes a node whose comments and formatting changed comment-only (R5).
4. Under `WhitespacePolicy.SIGNIFICANT`, where `normalize` keeps whitespace as it is, step 3 instead compares each merged whitespace run that touched a removed comment by its line breaks only, and every other run exactly. Without this, deleting a trailing `# note` would leave `"  \n"` against `"\n"` and the node would stay changed.
5. At least one side has a comment token in the node's own tokens; a node with no comments on either side is never comment-only.

String tokens are never dropped, so a change inside a string, and a Python docstring, keeps the node changed (R4, out of scope). Doc comments (Javadoc, KDoc, Scaladoc, Rust `///`) are comment tokens in every provider and count as comments; a provider whose lexer reports a comment as another kind simply never produces comment-only nodes, which is the safe direction (R13).

### Labels and summary (R3, R6, R10, R11)

In `OutlineModel`'s tag computation, when `markCommentOnly` is on and `node.commentOnly` is true, `Content.CHANGED` adds `Tag.COMMENT_ONLY` instead of `Tag.CHANGED`. `moved`, usages and children are untouched (R6). A node with grouped usages keeps its `usages renamed` handling: comment-only replaces only the `changed` tag.

The summary line's order list puts `comment-only` right after `formatting-only` (R10). The refactorings view builds its other-changes rows from the same tags, so it shows the label without extra code (R11); `RefactoringsModel` needs only the setting passed through.

Comment-only rows are shown inline, never in the formatting-only group (out of scope).

### Filter (R2, R8, R9)

- The outline's filter menu in `OutlinePanel` lists `ChangeType.COMMENT_ONLY` only when `markCommentOnly` is on, and ends with a separator and a "Mark Comment-Only Changes" checkbox bound to the same setting (R2).
- When `markCommentOnly` is off, no node has `Tag.COMMENT_ONLY`, so a remembered hidden `COMMENT_ONLY` type hides nothing (R9). The remembered type is kept, so turning the option on again restores it.
- `SmartDiffConfigurable` adds "Mark comment-only changes" under the formatting-only choice, and a "Comment-only" checkbox in "Hide from the outline" that is enabled only while the option is on (R8, settings/01 R19).
- `ChangesTreeActions` and `MergeOutlinePanel` iterate `ChangeType.entries` today; they switch to an explicit list without `COMMENT_ONLY`, so neither the Changes tree filter nor the merge outline filter gains the entry (out of scope).

### Failure

`isCommentOnly` is a pure token comparison run after classification. If it throws, the node keeps `commentOnly = false` and is shown as changed, the conservative result ([ADR 0003](../../adr/0003-conservative-classification.md)).

## Tests

| Test | Checks |
| --- | --- |
| `CommentOnlyClassifierTest`: comment reworded, added and removed under every `WhitespacePolicy`, a block comment between tokens, comment and formatting together, comment and code changed, string changed, signature changed, parse error, identical and comment-free sources, whitespace the policy counts, significant indentation after a removed comment, a multi-line block comment | R3, R4, R5 |
| `CommentOnlyEngineTest`: a Javadoc rewrite and a removed line comment in Java, a code change next to a comment change, a KDoc rewrite in Kotlin, a line comment and a docstring in Python, a header comment in the file region | R3, R4, R7 |
| `CommentOnlyOutlineTest`: option off shows `changed` and the same rows and summary as before; option on shows `comment-only` with `moved` kept; summary order; hiding with the option on and off; only the outline offers the type; the refactorings view's other changes | R1, R3, R6, R7, R8, R9, R10, R11 |
| `SmartDiffSettingsTest`: off by default, persisted, counted as a display setting, and round-tripped on the settings page | R1, R2, R12 |
| `OutlineIntegrationTest` outline 11 R2 R12: the toggle redraws without a new comparison, keeps the selection, and the filter hides comment-only rows only while it is on | R2, R8, R9, R12 |
| `ChangesTreeFilterTest` and `MergeOutlineIntegrationTest`: no comment-only entry | Out of scope |

The relationship benchmark and the corpus validation must give the same output as before, since classification does not change.
