# SMART Diff Roadmap

Status: 2026-11-26. The open items are candidates, not commitments, and their order is a proposal. Scope details live in the [product scope](specs/README.md#product-scope), the [feature specifications](specs/README.md) and the [technical specification](TECHNICAL-SPECIFICATION.md); what each release contains is in the [changelog](../CHANGELOG.md).

The latest release is 0.5.0, published on 2026-11-26 on GitHub and on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36327-smart-diff).

## Releases

| Release | Date | Theme | Record |
| --- | --- | --- | --- |
| 0.1.0 | 2026-10-22 | Structural outline for Java, Kotlin and Scala, Changes tree summary, noise pass | [Validation](VALIDATION.md#2026-10-22-010-noise-pass) |
| 0.1.1 | 2026-10-23 | Extraction detection | [Changelog](../CHANGELOG.md#011---2026-10-23) |
| 0.2.0 | 2026-11-05 | Three-way merge outline, declaration rows in the Changes tree | [Merge validation](VALIDATION.md#2026-11-02-merge-validation-rerun), [corpus rerun](VALIDATION.md#2026-11-04-two-sided-corpus-rerun) |
| 0.3.0 | 2026-11-10 | Changes tree in the IDE: hide and filter, caret follow, rename grouping step 1 | [Plan](releases/0.3.0-plan.md), [manual check](releases/0.3.0-manual-check.md) |
| 0.4.0 | 2026-11-20 | Structural relationships, Python, Rust, data formats, Swift | [Plan](releases/0.4.0-plan.md), [validation](VALIDATION.md#2026-11-19-040-release-validation) |
| 0.5.0 | 2026-11-26 | JavaScript and TypeScript, Go, CSS, PHP, SQL, C#, C and C++, moves inside declarations, one shared filter, Marketplace distribution | [Validation](VALIDATION.md#2026-11-25-050-release-validation) |

## Open

Items marked *(gap review)* came from a comparison with [SemanticDiff](RESEARCH.md#semanticdiff) on 2026-10-22, and items marked *(weak-point review)* from a review of the plugin's strengths and weaknesses on 2026-11-13.

| Item | Spec |
| --- | --- |
| [Close the open manual checks](#close-the-open-manual-checks) | Each feature's details file |
| [Usability study sessions](#usability-validation) | [outline/06](specs/outline/06-usability-validation.md) (accepted) |
| [Relationship recall](#relationship-recall) | [relationships/03](specs/relationships/03-relationship-matching.md), [relationships/05](specs/relationships/05-inlining-detection.md) |
| [Moves inside declarations in Tree-sitter languages](#moves-inside-declarations-in-tree-sitter-languages) | [engines/05](specs/engines/05-tree-sitter.md), [relationships/08](specs/relationships/08-moves-inside-declarations.md) |
| [Ruby](#ruby) | [Format coverage plan](FORMAT-COVERAGE.md) phase B5; not specified yet |
| [Rename grouping in more languages](#rename-grouping-in-more-languages) | [relationships/04](specs/relationships/04-rename-grouping.md) |
| [Later](#later) | Not specified yet |

### Close the open manual checks

*(weak-point review)* Several physical IDE rows are recorded as partial, inconclusive or pending. The code for each is merged and covered by automated tests; what is missing is a run in a real IDE:

- **Changes tree caret follow** ([changes-tree/05](specs/changes-tree/05-changes-tree-caret-follow.md), issue #110, closed as delivered): R3 through the Local Changes splitter preview, R5 commit inclusion and R11. The splitter preview could not be produced on IC 2025.1 in the 0.3.0 runs ([manual check](releases/0.3.0-manual-check.md#d-changes-tree-caret-follow-110-r1r11)).
- **Other 0.3.0 rows:** the outline filter toggle, the Structural Outline shortcut and its toolbar position, result-edit following in the merge window and the merge window's `not tracked` note, listed in the [0.3.0 manual check](releases/0.3.0-manual-check.md).
- **The [refactorings view manual check](VALIDATION.md#2026-11-09-refactorings-view-manual-check).**
- **The 0.4.0 and 0.5.0 physical checks** on IC 2025.1.3 and IU 2026.3, pending since each release's validation.

Re-run them, fix any real failure, and move the rows that can be driven through the real action path into UI tests, so fewer release rows depend on a manual run.

### Usability validation

Decided 2026-11-01 as the check that the outline meets its main goal. A small, repeatable study compares finding changed declarations with the outline and with the native diff, and records time and accuracy. Specified in [outline/06](specs/outline/06-usability-validation.md) (accepted, 2026-11-10; revised 2026-11-26 so each participant does each task once); the study kit is prepared in [docs/usability-study](usability-study/README.md), and the sessions, with at least 6 participants, are pending. No release text may claim that the outline makes reviews faster until the study passes outline/06 R7. Its results also show which outline controls are used, which decides whether more of them can be merged after the [shared filter](#fewer-controls).

### Relationship recall

*(weak-point review)* The 0.5.0 benchmark run reports precision 1.000 and recall 0.966 (0.924 held-out), with 12 labelled relationships still missed, after the [2026-11-23 recall work](VALIDATION.md#2026-11-23-relationship-recall). Work through the missed relationships listed in [VALIDATION.md](VALIDATION.md) by kind and language, for example extraction to several sources, and raise recall without lowering precision or forcing an ambiguous pair, under the benchmark's `no-new-wrong-pairs` gate.

### Moves inside declarations in Tree-sitter languages

[Moves inside declarations](#moves-inside-declarations) work for Java, Kotlin, Scala 2, Scala 3 and Python. Swift, C#, C and C++ need changed runs from the Tree-sitter engine first.

### Ruby

Phase B5 of the [format coverage plan](FORMAT-COVERAGE.md#5-phases): a Ruby provider on the JetBrains Ruby plugin's PSI (`org.jetbrains.plugins.ruby`), bundled in RubyMine, built on the paid-IDE pattern the other B phases use, with a RubyMine verifier family. Modules, classes, methods, singleton methods and constants are declarations; `attr_*` and DSL calls stay content. RubyMine's licensing is open question F5.

### Rename grouping in more languages

Renames are grouped with their usages for Java, Kotlin K2, Scala 2, Scala 3 and Python. Rust, JavaScript and TypeScript, Go, PHP, CSS and SQL pair renames as rename candidates but leave usages as ordinary changes. The Rust, Go and PHP spikes showed that in-file references resolve without an SDK, so each needs only its own reference resolver for [relationships/04](specs/relationships/04-rename-grouping.md). The Tree-sitter languages have no reference resolution, so they stay out.

### Later

- **Relationships across files and cross-file extraction:** not specified; only if the product scope is extended, with a separate design and benchmark ([extraction detection §11](EXTRACTION-DETECTION.md#11-later)).
- **More stylesheet coverage:** rule sets inside SCSS control directives and the indented Sass syntax ([languages/18](specs/languages/18-css.md)).
- **Third-party engines:** consider making the `comparisonEngine` extension point public once a second production engine has used the result model without breaking changes (technical spec Q5).
- **Licence:** whether the PolyForm Noncommercial license, which rules out most use at work, stays as it is or gains a commercial option is the maintainer's decision and stays an open question.

## Delivered

Each item below is implemented and released; its spec holds the requirements and the design.

### Structural relationships

Relationships between declarations (renames, moves, kind changes, extractions, inlinings) are one list with a confidence and evidence, measured by the benchmark before any matcher change ([STRUCTURAL-RELATIONSHIPS.md](STRUCTURAL-RELATIONSHIPS.md), [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md), [ADR 0008](adr/0008-relationships-are-the-source-of-truth-for-pairing.md)): the benchmark ([relationships/01](specs/relationships/01-relationship-benchmark.md)), the model ([relationships/02](specs/relationships/02-relationship-model.md)), whole-file matching ([relationships/03](specs/relationships/03-relationship-matching.md)), rename grouping ([relationships/04](specs/relationships/04-rename-grouping.md)), inlining ([relationships/05](specs/relationships/05-inlining-detection.md)), the refactorings view ([relationships/06](specs/relationships/06-refactorings-view.md)) and the release validation ([relationships/07](specs/relationships/07-relationship-validation.md)). Shipped across 0.3.0 and 0.4.0.

### Rename candidates

A removed and an added declaration of the same kind in the same container, with the same or a similar body, pair as an uncertain rename candidate, classes included ([declarations/03](specs/declarations/03-rename-candidates.md), 0.1.0). Overloads that all change signature at once pair by similarity ([declarations/01](specs/declarations/01-declaration-matching.md) R5).

### Rename grouping across usages

*(gap review)* A rename is read once with every usage it changed, each rename with its own highlight colour, for Java, Kotlin K2, Scala 2, Scala 3 and Python ([relationships/04](specs/relationships/04-rename-grouping.md), 0.3.0 and 0.4.0). Other languages are [open](#rename-grouping-in-more-languages).

### Extraction detection

An added method linked to the code it was extracted from, labelled "extracted from <declaration>" and framed on the old side ([EXTRACTION-DETECTION.md](EXTRACTION-DETECTION.md), [declarations/05](specs/declarations/05-extraction-detection.md), 0.1.1). The reverse, inlining, is [relationships/05](specs/relationships/05-inlining-detection.md) (0.4.0).

### Moves inside declarations

*(gap review)* Statements moved within or between declarations become `CODE_MOVE` relationships, listed as `moved code lines A–B → C–D` with markers and the edits inside highlighted, for Java, Kotlin, Scala 2, Scala 3 and Python ([relationships/08](specs/relationships/08-moves-inside-declarations.md), 0.5.0).

### Fine-grained differencing

*(gap review)* The declaration engine compares the tokens of each changed declaration under its whitespace policy and reports changed runs, which the SMART Diff viewer highlights in place of the native word highlights ([declarations/10](specs/declarations/10-fine-grained-differencing.md), [ADR 0014](adr/0014-changed-runs-come-from-the-declaration-engine.md), 0.5.0). The [validation against Difftastic and GumTree](VALIDATION.md#2026-11-23-fine-grained-differencing-against-difftastic-and-gumtree) found no edit outside the changed runs.

### Multi-file summary in the Changes tree

A summary line and expandable declaration rows for each file in Local Changes and the Commit tool window, through the public `changesViewModifier` extension point ([changes-tree/01](specs/changes-tree/01-changes-tree-summary.md), [changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md), [ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md), 0.1.0 and 0.2.0), with hide and filter ([changes-tree/04](specs/changes-tree/04-hide-and-filter.md)) and caret follow ([changes-tree/05](specs/changes-tree/05-changes-tree-caret-follow.md)) in 0.3.0. The modal commit dialog has no hook and shows no summary.

### Summaries for added and deleted files

Added and deleted files get a summary and declaration rows, as every declaration added or removed ([changes-tree/06](specs/changes-tree/06-added-deleted-file-summaries.md), 0.4.0).

### Summary in pull request reviews and the Git log

*(gap review)* The Git log's commit details and GitHub and GitLab pull request changes show the same summary and declaration rows as the Changes tree, through the tree's `model` property ([changes-tree/07](specs/changes-tree/07-review-tree-summaries.md), [ADR 0013](adr/0013-review-tree-summaries-through-the-tree-model-property.md), 0.4.0).

### Structural outline for three-way merges

The declarations changed on each side of a merge, in the merge window and in three-sided diffs, grouped by side, for Java, Kotlin, Scala 2 and Scala 3 and later every language with a provider ([THREE-WAY-MERGE.md](THREE-WAY-MERGE.md), [merge/01](specs/merge/01-merge-join.md) to [merge/06](specs/merge/06-merge-validation.md), [ADR 0007](adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md), 0.2.0). SMART Diff never resolves or edits anything.

### Scala

Scala 2 and Scala 3 through the Scala plugin's PSI, in an optional descriptor, with the dialect chosen from both texts ([languages/03](specs/languages/03-scala.md), 0.1.0).

### Python

Python through `PythonCore`, with the indentation-significant whitespace policy ([languages/04](specs/languages/04-python.md), 0.4.0).

### Rust

Rust through the JetBrains Rust plugin, in a `rust/` subproject on IntelliJ IDEA Ultimate ([languages/05](specs/languages/05-rust.md), [ADR 0011](adr/0011-rust-provider-build-target-spike.md), 0.4.0).

### More languages

The [format coverage plan](FORMAT-COVERAGE.md) is delivered except phase B5, [Ruby](#ruby): the structure-view fallback for every other language ([languages/06](specs/languages/06-structure-view-fallback.md)); JSON, YAML, properties, TOML, XML and HTML with keys as declarations ([ADR 0009](adr/0009-structured-data-keys-are-declarations.md), [languages/07](specs/languages/07-json.md) to [languages/15](specs/languages/15-html.md)); Groovy and Gradle scripts, shell and Markdown (0.4.0); and, on the paid-IDE build pattern, JavaScript and TypeScript, Go, CSS, SCSS and Less, PHP and SQL ([languages/16](specs/languages/16-javascript-typescript.md) to [languages/20](specs/languages/20-sql.md), 0.5.0).

### Tree-sitter support

A `Declarations (Tree-sitter)` engine with bundled grammars for Swift (0.4.0) and C#, C and C++ (0.5.0) in files the IDE opens as plain text or TextMate files, run in a native helper process ([engines/05](specs/engines/05-tree-sitter.md), [ADR 0015](adr/0015-tree-sitter-languages-are-chosen-by-file-name.md), [ADR 0016](adr/0016-tree-sitter-runs-in-a-native-helper-process.md)). Moves inside declarations for these languages are [open](#moves-inside-declarations-in-tree-sitter-languages).

### C and C++ macro recovery

*(T3 validation)* A C or C++ file that the grammar cannot fully parse is parsed again with common macro patterns, such as export and `constexpr` macros, namespace macros on lines of their own, `foreach`-style block macros and conditionals inside function bodies, read as blank space and still compared ([engines/06](specs/engines/06-c-cpp-macro-recovery.md), next release). Nothing is expanded. The [validation](VALIDATION.md#2026-11-26-c-and-c-macro-recovery) reran the T3 corpus.

### Moved lines in unsupported files

*(gap review)* Files no engine can analyze are compared by lines, and moved blocks of at least 3 lines are listed and framed ([engines/04](specs/engines/04-moved-lines.md), 0.4.0).

### Hide comment-only changes

*(gap review)* An opt-in `comment-only` label that the change type filter can hide ([outline/11](specs/outline/11-comment-only-changes.md), 0.4.0).

### Opt-in equivalence rules

*(gap review)* Opt-in rules that mark numeric literal, string escape and redundant parenthesis rewrites `rewrite-only`, for Java ([declarations/08](specs/declarations/08-equivalence-rules.md), [ADR 0012](adr/0012-opt-in-equivalence-rules.md)) and Kotlin and Scala ([declarations/09](specs/declarations/09-kotlin-scala-equivalence-rules.md)), 0.4.0.

### Formatting-only recall in Kotlin and Scala

*(weak-point review)* Line breaks inside brackets and next to continuing tokens are formatting, and a Scala 3 declaration re-indented as a whole is formatting-only ([declarations/07](specs/declarations/07-kotlin-scala-formatting.md), 0.4.0).

### Reviewed marks in the outline

Mark changes reviewed, see `N of M reviewed` in the summary line and hide reviewed rows ([outline/12](specs/outline/12-reviewed-marks.md), 0.4.0).

### Visual links for moved code

*(gap review)* Gutter icons and scrollbar marks on both sides of a moved declaration ([outline/13](specs/outline/13-moved-code-markers.md), 0.4.0).

### Expand context to the enclosing declaration

*(gap review)* An "Expand Declaration" action expands every collapsed unchanged fragment in a changed declaration ([outline/14](specs/outline/14-expand-to-declaration.md), 0.4.0).

### Settings button in the outline

A toolbar button that opens *Settings | Tools | SMART Diff*, with open outlines picking up applied settings ([outline/10](specs/outline/10-outline-settings-button.md), 0.4.0).

### Platform API stability

*(weak-point review)* A weekly early-access Plugin Verifier run, an inventory in [PLATFORM-API.md](PLATFORM-API.md) with one adapter per API, and a guard that turns off only the feature whose API is missing ([platform/01](specs/platform/01-platform-api-stability.md), 0.4.0).

### Fewer controls

*(weak-point review)* The outline and the Changes tree share one change type filter and one settings group ([changes-tree/04](specs/changes-tree/04-hide-and-filter.md) R19 to R21, [outline/03](specs/outline/03-change-type-filter.md) R11), replacing the tree's separate filter (0.5.0). Whether more controls can be merged waits for the [usability study](#usability-validation).

### Marketplace distribution

*(weak-point review)* The plugin is published on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36327-smart-diff), so it can be found and updated from the IDE ([MARKETPLACE.md](MARKETPLACE.md), [RELEASING.md](RELEASING.md)).
