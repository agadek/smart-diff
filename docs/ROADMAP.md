# SMART Diff Roadmap

Status: 2026-10-23. Items after the first release are candidates, not commitments; their order is a proposal. Scope details live in the [product specification](SPECIFICATION.md) and [technical specification](TECHNICAL-SPECIFICATION.md).

## First release

- Structural outline for two-sided diffs, attached to the native diff, per file only. It opens by default, with a setting to turn it off and a toolbar button in every diff to switch to the plain native diff.
- Comparison engine interface, registry, per-language default, and engine dropdown ([ADR 0004](adr/0004-pluggable-comparison-engines.md)).
- Declaration engine for Java and Kotlin, using IntelliJ PSI ([ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md)).
- Conservative classification: added, removed, changed, moved, formatting-only, uncertain, unanalyzed ([ADR 0003](adr/0003-conservative-classification.md)). Renamed declarations, including classes, stay literal (removed + added).
- IntelliJ Platform 2025.1 and newer, checked by Plugin Verifier on 2025.1 and the latest release.

## 0.1.1: noise pass

Decided 2026-10-22, from the validation on real commits before 0.1.0 ([VALIDATION.md](VALIDATION.md)). It comes before everything under [Later](#later). Implemented; the rerun is in [VALIDATION.md](VALIDATION.md#2026-10-22-011-noise-pass).

### Rename candidates

Pair a removed and an added declaration of the same kind in the same container, with the same or a similar body, as an uncertain rename candidate; includes renamed classes. Renames reported as removed + added were the largest remaining noise in the validation (70 of 1279 files), most at the same lines with the same body (technical spec Q2).

### Overload pairing by similarity

When every overload of a name changes signature at once (an added parameter, `T?` → `T`), pair each with the most similar one by parameter list and body instead of leaving all of them uncertain (7 files in the validation).

### Readable labels

Keep a single space where the source separates words in a type (`Class<out Annotation>`, not `Class<outAnnotation>`), and show a Kotlin receiver before the name (`Date.formatIsoDate()`). Matching keys may stay as they are.

### Full nothing-hidden coverage in the validation

A nothing-hidden check cheap enough for large files, so the corpus validation covers every file (414 of 1279 were too large for the current one).

## Later

Items marked *(gap review)* came from a comparison with [SemanticDiff](RESEARCH.md#semanticdiff) on 2026-10-22: features it has that SMART Diff lacks. Their order among the other items is still open.

### Multi-file summary in the Changes tree

The first item after the 0.1.1 noise pass. Show changed declarations for each file inside the IDE's existing Changes/Commit tree, rather than in a new panel (technical spec Q3).

- **Level 1 (implemented):** a summary next to each file, for example `Parser.kt  2 changed · 1 added`, in the Local Changes view and the Commit tool window ([technical spec §9.4](TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)).
- **Level 2 (implemented):** expandable declarations under each file, the same rows as the file's outline. Double-click or Enter on one opens the file's diff with that declaration selected in the outline; the rows are computed with the summary, so they follow edits the same way ([technical spec §9.4](TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)).
- **Spike (done 2026-10-22, S5):** the Local Changes and Commit tree takes the summary through the public `changesViewModifier` extension point ([ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md)). The modal commit dialog and the Git log's commit details have no such hook, so they show no summary.

### Scala

Planned for the release after the first one (decided 2026-10-21), after the 0.1.1 noise pass. A Scala provider for the declaration engine, using the JetBrains Scala plugin's PSI. It needs no change to the engine interface, the matcher or the UI.

- **Packaging:** the provider registers in its own optional descriptor, `META-INF/smartdiff-scala.xml`, which depends on `org.intellij.scala`. The plugin keeps loading without Scala.
- **Dialects:** Scala 2 and Scala 3. Text-built PSI has no module, so the provider chooses the dialect itself from both texts, by parse errors and Scala 3 syntax (spike S4, done). Scala 3 optional braces make indentation significant, so Scala 3 uses an indentation-significant whitespace policy next to the Kotlin line-break one (technical spec §8).
- **Declaration kinds:** classes, traits, objects (including companions and package objects), enums and enum cases, `def`, `val`/`var`, type aliases, Scala 3 givens and extension methods, and top-level definitions. This adds `TRAIT`, `GIVEN` and `EXTENSION` to `DeclarationKind` (decided 2026-10-22, technical spec §6.8).
- **Naming and signatures:** anonymous givens are named by their type, an extension block is a container named by its receiver type, `val (a, b) = …` is one property named `(a, b)`, and parameter lists are joined with `;`, keeping `using`/`implicit` (decided 2026-10-22, technical spec §6.8).
- **Build and CI:** the Scala plugin is not bundled, so the build pins a Marketplace version compatible with the lowest supported IDE, and Plugin Verifier needs a compatible Scala plugin for every IDE it checks. The Scala PSI is not a public API, so internal-API usage is expected in verifier reports and must be reviewed each release.
- **Fixtures:** the standard set from the product spec for both dialects, plus companion objects, givens, extension methods and a braces-versus-indentation change in Scala 3.

### Structural outline for three-way merges

It comes right after the [Changes tree level 2](#multi-file-summary-in-the-changes-tree) (decided 2026-10-23). Show which declarations changed on each side of a merge, to help the user understand conflicts before resolving them, in the merge window and in three-sided diffs, for Java, Kotlin, Scala 2 and Scala 3. SMART Diff still never resolves or edits anything (merge conflict resolution stays a non-goal). Planned in [THREE-WAY-MERGE.md](THREE-WAY-MERGE.md) (proposal, 2026-10-23).

- **Approach:** run the selected comparison engine twice, base → left and base → right, and join both results on base-side ranges. No change to the engine interface; the request gains an optional hint so the Scala dialect is chosen from all three texts.
- **Groups:** changed on both sides (likely conflicts), changed only on one side, uncertain; the same change on both sides is marked as such.
- **Integration:** a `DiffExtension` for three-sided diffs, and for the merge window a `MergeTool` registered first that decorates the platform's own merge viewer, decided by spike S6 ([ADR 0007](adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)). Base ranges are tracked into the editable result pane.
- **Phases:** spike S6 first (done 2026-10-23, [THREE-WAY-MERGE.md §12](THREE-WAY-MERGE.md#12-spike-s6-results)), then the join (done 2026-10-23), three-sided diffs, the merge window, Scala fixtures and a validation on replayed merge commits.
- **Decided 2026-10-23** ([THREE-WAY-MERGE.md §10](THREE-WAY-MERGE.md#10-decisions)): grouped by side with a toggle to the container tree; sides named by the merge window's titles, shortened; rows changed on both sides that merged without a conflict marked as such; the outline follows the existing setting; the item comes right after the Changes tree level 2.

### More languages

Add declaration-engine providers for further languages the host IDE parses, after [Scala](#scala) (for example JavaScript/TypeScript in Ultimate/WebStorm, Python in PyCharm), each with the fixtures the product spec requires.

*(gap review)* SemanticDiff supports 14+ languages and formats. Structured data files (JSON, XML, YAML) are a cheap addition on top of the IDE's own parsers: keys and elements are the declarations, and reordered keys in formats where order carries no meaning can be reported as moved.

### Tree-sitter support

A Tree-sitter provider or engine for languages the host IDE cannot parse, after native packaging and grammar licence review ([research](RESEARCH.md#difftastic-and-alternatives)).

### Fine-grained differencing

A second comparison engine that highlights changed expressions inside declarations, using the result model's `details`. Compare a project-owned matcher with Difftastic and GumTree as baselines.

*(gap review)* This is SemanticDiff's central feature and the largest gap: inside the diff itself, formatting noise is hidden and only the changed tokens are highlighted, so a reviewer does not have to read every changed line of a declaration that is only partly reformatted. The outline currently judges formatting per declaration while the native diff still shows every line.

### Extraction detection

Link an added method or function to the code it was extracted from. When most of its body repeats one run of code that a changed declaration on the old side no longer has, label it "extracted from <declaration>" and frame that code on the old side when it is selected; the source declaration shows the extracted code in its details. Extracting into a new class is covered through the class's added methods. The first release reports such code as a changed declaration plus an added one, with no link between them (see the `noisy-change` Java fixture). Specified in [EXTRACTION-DETECTION.md](EXTRACTION-DETECTION.md); it comes after the Changes tree summary and Scala. Implemented for Java, Kotlin and Scala; inline detection and cross-file extraction stay [later](EXTRACTION-DETECTION.md#11-later).

### Rename grouping across usages

*(gap review)* Group a rename with every usage it changed, so the reviewer reads "renamed `foo` → `bar`, 12 usages" once instead of each changed call site. The 0.1.1 rename candidates pair the declaration only; edits that only follow a rename stay as ordinary changes. Applies to declarations and local variables, and needs the same conservative confirmation as rename candidates. Like SemanticDiff, give each rename its own highlight color so its occurrences can be followed through the diff.

### Moves inside declarations

*(gap review)* Detect moved statements and blocks within and between declarations, and show the edits made inside the moved code. The first release reports moves for whole declarations only. [Extraction detection](#extraction-detection) covers code moved into a new method; this covers the rest.

### Hide comment-only changes

*(gap review)* An opt-in toggle in the diff that hides declarations whose only change is in comments, or marks them as comment-only. Comments count as changes by default (product spec), and the first release has no global ignore rule, so this stays off unless the user turns it on.

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
