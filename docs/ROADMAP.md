# SMART Diff Roadmap

Status: 2026-11-01. Items after the shipped baseline are candidates, not commitments; their order is a proposal. Scope details live in the [product scope](specs/README.md#product-scope), the [feature specifications](specs/README.md) and the [technical specification](TECHNICAL-SPECIFICATION.md).

## Shipped baseline

0.1.0 and 0.1.1 are released; the "Unreleased" section of [CHANGELOG.md](../CHANGELOG.md) is the next release.

- Structural outline for two-sided diffs, attached to the native diff, per file only. It opens by default, with a setting to turn it off and a toolbar button in every diff to switch to the plain native diff.
- Comparison engine interface, registry, per-language default, and engine dropdown ([ADR 0004](adr/0004-pluggable-comparison-engines.md)).
- Declaration engine for Java, Kotlin, Scala 2 and Scala 3, using IntelliJ PSI ([ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md)); Scala needs the Scala plugin.
- Conservative classification: added, removed, changed, moved, formatting-only, uncertain, unanalyzed ([ADR 0003](adr/0003-conservative-classification.md)), plus rename candidates, kind changes and signature changes. Renamed declarations, classes included, are paired as rename candidates rather than reported literally.
- Extraction detection for Java, Kotlin, Scala 2 and Scala 3 (0.1.1).
- Changes tree summary and declaration rows in Local Changes and the Commit tool window (Unreleased).
- Structural outline for three-way merges and three-sided diffs, for Java, Kotlin, Scala 2 and Scala 3 (Unreleased; the merge validation is not started).
- IntelliJ Platform 2025.1 and newer, checked by Plugin Verifier on 2025.1 and the latest release.

Specified in [outline/01](specs/outline/01-structural-outline.md), [outline/02](specs/outline/02-outline-toggle.md), [outline/05](specs/outline/05-analysis-fallback.md), [engines/01](specs/engines/01-comparison-engines.md) to [engines/03](specs/engines/03-analysis-limits.md), [declarations/01](specs/declarations/01-declaration-matching.md), [declarations/02](specs/declarations/02-change-classification.md), [declarations/06](specs/declarations/06-change-labels.md), [languages/01](specs/languages/01-java.md) to [languages/03](specs/languages/03-scala.md), [changes-tree/01](specs/changes-tree/01-changes-tree-summary.md), [changes-tree/03](specs/changes-tree/03-coverage-and-scale.md) and [merge/01](specs/merge/01-merge-join.md) to [merge/05](specs/merge/05-scala-merges.md).

## 0.1.0: noise pass

Decided 2026-10-22, from the validation on real commits before 0.1.0 ([VALIDATION.md](VALIDATION.md)). It shipped in 0.1.0; the rerun is in [VALIDATION.md](VALIDATION.md#2026-10-22-010-noise-pass).

| Item | Spec |
| --- | --- |
| [Rename candidates](#rename-candidates) | [declarations/03](specs/declarations/03-rename-candidates.md) |
| [Overload pairing by similarity](#overload-pairing-by-similarity) | [declarations/01](specs/declarations/01-declaration-matching.md) R5 |
| [Readable labels](#readable-labels) | [outline/01](specs/outline/01-structural-outline.md), [languages/02](specs/languages/02-kotlin.md) |
| [Full nothing-hidden coverage in the validation](#full-nothing-hidden-coverage-in-the-validation) | [engines/02](specs/engines/02-engine-contract.md) |

### Rename candidates

Pair a removed and an added declaration of the same kind in the same container, with the same or a similar body, as an uncertain rename candidate; includes renamed classes. Renames reported as removed + added were the largest remaining noise in the validation (70 of 1279 files), most at the same lines with the same body (technical spec Q2).

### Overload pairing by similarity

When every overload of a name changes signature at once (an added parameter, `T?` → `T`), pair each with the most similar one by parameter list and body instead of leaving all of them uncertain (7 files in the validation).

### Readable labels

Keep a single space where the source separates words in a type (`Class<out Annotation>`, not `Class<outAnnotation>`), and show a Kotlin receiver before the name (`Date.formatIsoDate()`). Matching keys may stay as they are.

### Full nothing-hidden coverage in the validation

A nothing-hidden check cheap enough for large files, so the corpus validation covers every file (414 of 1279 were too large for the current one).

## 0.2.0: three-way merge outline

Decided 2026-10-30. It comes before everything else under [Later](#later), and ships the [structural outline for three-way merges](#structural-outline-for-three-way-merges) for Java, Kotlin, Scala 2 and Scala 3 together. Its features are specified in [docs/specs/merge/](specs/README.md#merges).

| Work | Spec | State |
| --- | --- | --- |
| Join of the two comparisons | [merge/01](specs/merge/01-merge-join.md) | Implemented, PR #40 |
| Merge outline and three-sided diffs | [merge/02](specs/merge/02-merge-outline.md), [merge/03](specs/merge/03-three-sided-diffs.md) | Implemented, PRs #42 to #44 |
| Merge window | [merge/04](specs/merge/04-merge-window.md) | Implemented, PRs #46 and #47 |
| Scala merges and golden merge fixtures | [merge/05](specs/merge/05-scala-merges.md) | Implemented, PR #49 |
| Validation on real merge commits | [merge/06](specs/merge/06-merge-validation.md) | Implemented, PR #48; results in [VALIDATION.md](VALIDATION.md); flagged files still under review |
| Outline for languages without an engine, and the "not analyzed" reason in the Changes tree | [outline/01](specs/outline/01-structural-outline.md), [changes-tree/01](specs/changes-tree/01-changes-tree-summary.md) | Implemented, PR #37 |

0.2.0 is released once the merge validation meets its criteria (merge/06 R15 to R17), the flagged files are reviewed, and its results are in [VALIDATION.md](VALIDATION.md), following [RELEASING.md](RELEASING.md).

## After 0.2.0: structural relationships

Proposed 2026-11-01 as the next item after 0.2.0. Relationships between declarations (renames, moves, kind changes, extractions, inlinings) become one list with a confidence and evidence, measured by a labelled benchmark before any matcher change. Planned in [STRUCTURAL-RELATIONSHIPS.md](STRUCTURAL-RELATIONSHIPS.md) and [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md); the model is decided in [ADR 0008](adr/0008-relationships-are-the-source-of-truth-for-pairing.md). It supersedes the design of [rename grouping across usages](#rename-grouping-across-usages) and [moves inside declarations](#moves-inside-declarations), and informs extraction detection and the merge outline without rescheduling either.

| Step | Spec | Done when |
| --- | --- | --- |
| Benchmark and today's baseline | [relationships/01](specs/relationships/01-relationship-benchmark.md) | The baseline report of today's engine is in VALIDATION.md |
| Relationship model, with today's fields computed from it | [relationships/02](specs/relationships/02-relationship-model.md) | The outline, Changes tree and merge outline are unchanged, and the contract checks relationships |
| Better pairing over the whole file | [relationships/03](specs/relationships/03-relationship-matching.md) | The benchmark shows no new wrong pairs and no lower held-out precision |
| Rename grouped with its usages | [relationships/04](specs/relationships/04-rename-grouping.md) | The labelled usage cases pass, and unresolved usages stay ordinary changes |
| Inlining detection | [relationships/05](specs/relationships/05-inlining-detection.md) | The inlining cases pass, and every linked declaration stays visible |
| Refactorings view | [relationships/06](specs/relationships/06-refactorings-view.md) | The view shows every change the container tree shows |
| Validation and release | [relationships/07](specs/relationships/07-relationship-validation.md) | The results are in VALIDATION.md and the release checks pass |
| Relationships across files | Not specified; only if the product scope is extended | A separate design and benchmark exist |

It also covers the later [rename grouping across usages](#rename-grouping-across-usages) item, through a separate reference pass.

## Later

Items marked *(gap review)* came from a comparison with [SemanticDiff](RESEARCH.md#semanticdiff) on 2026-10-22: features it has that SMART Diff lacks. Their order among the other items is still open.

| Item | Spec |
| --- | --- |
| [Multi-file summary in the Changes tree](#multi-file-summary-in-the-changes-tree) | [changes-tree/01](specs/changes-tree/01-changes-tree-summary.md), [changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) |
| [Scala](#scala) | [languages/03](specs/languages/03-scala.md) |
| [Structural outline for three-way merges](#structural-outline-for-three-way-merges) | [merge/01](specs/merge/01-merge-join.md) to [merge/06](specs/merge/06-merge-validation.md) |
| [Extraction detection](#extraction-detection) | [declarations/05](specs/declarations/05-extraction-detection.md); inlining in [relationships/05](specs/relationships/05-inlining-detection.md) |
| [Rename grouping across usages](#rename-grouping-across-usages) | [relationships/04](specs/relationships/04-rename-grouping.md) |
| [Usability validation](#usability-validation) | [validation/01](specs/validation/01-usability-validation.md) |
| [Summaries for added and deleted files](#summaries-for-added-and-deleted-files) | Not specified yet |
| Every other item below | Not specified yet |

### Multi-file summary in the Changes tree

Implemented for 0.2.0. Show changed declarations for each file inside the IDE's existing Changes/Commit tree, rather than in a new panel (technical spec Q3).

- **Level 1 (implemented):** a summary next to each file, for example `Parser.kt  2 changed · 1 added`, in the Local Changes view and the Commit tool window ([technical spec §9.4](TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)).
- **Level 2 (implemented):** expandable declarations under each file, the same rows as the file's outline. Double-click or Enter on one opens the file's diff with that declaration selected in the outline, and a row with children expands through its expansion control; the rows are computed with the summary, so they follow edits the same way ([technical spec §9.4](TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)).
- **Spike (done 2026-10-22, S5):** the Local Changes and Commit tree takes the summary through the public `changesViewModifier` extension point ([ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md)). The modal commit dialog and the Git log's commit details have no such hook, so they show no summary.

### Scala

Implemented in 0.1.0, when the Scala plugin is installed ([languages/03](specs/languages/03-scala.md)). A Scala provider for the declaration engine, using the JetBrains Scala plugin's PSI. It needed no change to the engine interface, the matcher or the UI.

- **Packaging:** the provider registers in its own optional descriptor, `META-INF/smartdiff-scala.xml`, which depends on `org.intellij.scala`. The plugin keeps loading without Scala.
- **Dialects:** Scala 2 and Scala 3. Text-built PSI has no module, so the provider chooses the dialect itself from both texts, by parse errors and Scala 3 syntax (spike S4, done). Scala 3 optional braces make indentation significant, so Scala 3 uses an indentation-significant whitespace policy next to the Kotlin line-break one (technical spec §8).
- **Declaration kinds:** classes, traits, objects (including companions and package objects), enums and enum cases, `def`, `val`/`var`, type aliases, Scala 3 givens and extension methods, and top-level definitions. This adds `TRAIT`, `GIVEN` and `EXTENSION` to `DeclarationKind` (decided 2026-10-22, technical spec §6.8).
- **Naming and signatures:** anonymous givens are named by their type, an extension block is a container named by its receiver type, `val (a, b) = …` is one property named `(a, b)`, and parameter lists are joined with `;`, keeping `using`/`implicit` (decided 2026-10-22, technical spec §6.8).
- **Build and CI:** the Scala plugin is not bundled, so the build pins a Marketplace version compatible with the lowest supported IDE, and Plugin Verifier needs a compatible Scala plugin for every IDE it checks. The Scala PSI is not a public API, so internal-API usage is expected in verifier reports and must be reviewed each release.
- **Fixtures:** the standard set of the [technical specification](TECHNICAL-SPECIFICATION.md#12-testing) for both dialects, plus companion objects, givens, extension methods and a braces-versus-indentation change in Scala 3.

### Structural outline for three-way merges

It comes right after the [Changes tree level 2](#multi-file-summary-in-the-changes-tree) (decided 2026-10-23). Show which declarations changed on each side of a merge, to help the user understand conflicts before resolving them, in the merge window and in three-sided diffs, for Java, Kotlin, Scala 2 and Scala 3. SMART Diff still never resolves or edits anything (merge conflict resolution stays a non-goal). Implemented for 0.2.0, except the merge validation, which is specified in [merge/06](specs/merge/06-merge-validation.md) and not started. Designed in [THREE-WAY-MERGE.md](THREE-WAY-MERGE.md) (proposal, 2026-10-23).

- **Approach:** run the selected comparison engine twice, base → left and base → right, and join both results on base-side ranges. No change to the engine interface; the request gains an optional hint so the Scala dialect is chosen from all three texts.
- **Groups:** changed on both sides (likely conflicts), changed only on one side, uncertain; the same change on both sides is marked as such.
- **Integration:** a `DiffExtension` for three-sided diffs, and for the merge window a `MergeTool` registered first that decorates the platform's own merge viewer, decided by spike S6 ([ADR 0007](adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)). Base ranges are tracked into the editable result pane.
- **Phases:** spike S6 (done 2026-10-23, [THREE-WAY-MERGE.md §12](THREE-WAY-MERGE.md#12-spike-s6-results)), the join, three-sided diffs, the merge window and the merge fixtures (done 2026-11-01), then a validation on replayed merge commits. Tracked in [0.2.0](#020-three-way-merge-outline).
- **Decided 2026-10-23** ([THREE-WAY-MERGE.md §10](THREE-WAY-MERGE.md#10-decisions)): grouped by side with a toggle to the container tree; sides named by the merge window's titles, shortened; rows changed on both sides that merged without a conflict marked as such; the outline follows the existing setting; the item comes right after the Changes tree level 2.

### More languages

Add declaration-engine providers for further languages the host IDE parses, after [Scala](#scala) (for example JavaScript/TypeScript in Ultimate/WebStorm, Python in PyCharm), each with the fixtures the [technical specification](TECHNICAL-SPECIFICATION.md#12-testing) requires.

*(gap review)* SemanticDiff supports 14+ languages and formats. Structured data files (JSON, XML, YAML) are a cheap addition on top of the IDE's own parsers: keys and elements are the declarations, and reordered keys in formats where order carries no meaning can be reported as moved.

### Tree-sitter support

A Tree-sitter provider or engine for languages the host IDE cannot parse, after native packaging and grammar licence review ([research](RESEARCH.md#difftastic-and-alternatives)).

### Fine-grained differencing

A second comparison engine that highlights changed expressions inside declarations, using the result model's `details`. Compare a project-owned matcher with Difftastic and GumTree as baselines.

*(gap review)* This is SemanticDiff's central feature and the largest gap: inside the diff itself, formatting noise is hidden and only the changed tokens are highlighted, so a reviewer does not have to read every changed line of a declaration that is only partly reformatted. The outline currently judges formatting per declaration while the native diff still shows every line.

### Extraction detection

Link an added method or function to the code it was extracted from. When most of its body repeats one run of code that a changed declaration on the old side no longer has, label it "extracted from <declaration>" and frame that code on the old side when it is selected; the source declaration shows the extracted code in its details. Extracting into a new class is covered through the class's added methods. Implemented in 0.1.1 for Java, Kotlin, Scala 2 and Scala 3. Specified in [EXTRACTION-DETECTION.md](EXTRACTION-DETECTION.md); inline detection and cross-file extraction stay [later](EXTRACTION-DETECTION.md#11-later).

### Rename grouping across usages

*(gap review)* Group a rename with every usage it changed, so the reviewer reads "renamed `foo` → `bar`, 12 usages" once instead of each changed call site. The 0.1.0 rename candidates pair the declaration only; edits that only follow a rename stay as ordinary changes. Planned in [relationships/04](specs/relationships/04-rename-grouping.md), as part of [STRUCTURAL-RELATIONSHIPS.md](STRUCTURAL-RELATIONSHIPS.md). Applies to declarations and local variables, and needs the same conservative confirmation as rename candidates. Like SemanticDiff, give each rename its own highlight color so its occurrences can be followed through the diff.

### Moves inside declarations

*(gap review)* Detect moved statements and blocks within and between declarations, and show the edits made inside the moved code. The shipped engine reports moves for whole declarations only. [Extraction detection](#extraction-detection) covers code moved into a new method; this covers the rest. The relationship model in [STRUCTURAL-RELATIONSHIPS.md](STRUCTURAL-RELATIONSHIPS.md) is the design it follows.

### Usability validation

Decided 2026-11-01 as the check that the outline meets its main goal. A small, repeatable study compares finding changed declarations with the outline and with the native diff, and records time and accuracy. Specified in [validation/01](specs/validation/01-usability-validation.md). The corpus validation in [VALIDATION.md](VALIDATION.md) stays the record of analysis quality; the study is separate.

### Summaries for added and deleted files

Give added and deleted files in the Changes tree a summary and declaration rows, as every declaration of the file added or removed. Today they get none; 0.2.0 keeps that, and this item revisits it ([changes-tree/03](specs/changes-tree/03-coverage-and-scale.md)).

### Hide comment-only changes

*(gap review)* An opt-in toggle in the diff that hides declarations whose only change is in comments, or marks them as comment-only. Comments count as changes by default ([change classification](specs/declarations/02-change-classification.md)), and the shipped engine has no global ignore rule, so this stays off unless the user turns it on.

### Opt-in equivalence rules

*(gap review)* Language-specific rules that treat some syntax rewrites as unchanged, for example redundant parentheses, the base of an integer literal, or string escapes. [ADR 0003](adr/0003-conservative-classification.md) left these out on purpose because each rule is a behavioural claim; reconsider them as opt-in rules, one language at a time, each with fixtures that prove it.

### Summary in pull request reviews and the Git log

*(gap review)* SemanticDiff has a full pull request review UI. The IDE equivalent is showing the changed-declarations summary in the GitHub and GitLab pull request changes trees and in the Git log's commit details. `changesViewModifier` does not reach these ([ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md)), so this starts with a spike for another hook.

### Reviewed marks in the outline

*(gap review)* Let the reviewer mark a declaration in the outline as reviewed and show progress in the summary line, for example `5 of 8 reviewed`. A mark resets when that declaration changes again. SemanticDiff's pull request viewer tracks reviewed files the same way.

### Expand context to the enclosing declaration

*(gap review)* When the native diff collapses unchanged fragments, offer to expand a fragment to the whole surrounding declaration instead of a fixed number of lines. SemanticDiff expands context by complete blocks.

### Visual links for moved code

*(gap review)* Show where moved code came from without selecting it first: mark moved declarations in both editors and link the old and new positions, for example in the gutter or the scrollbar. SemanticDiff boxes moved blocks and shows them on a minimap. Today the outline frames a moved declaration on both sides only when it is selected.

### Moved lines in unsupported files

*(gap review)* For files no engine can analyze, a fallback that treats each line as one token and still reports moved lines, as SemanticDiff's fallback diff does. These files currently get only the plain native diff.

### Third-party engines

Consider making the `comparisonEngine` extension point public once a second production engine has used the result model without breaking changes (technical spec Q5).
