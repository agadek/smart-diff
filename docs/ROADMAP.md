# SMART Diff Roadmap

Status: 2026-10-21. Items after the first release are candidates, not commitments; their order is a proposal. Scope details live in the [product specification](SPECIFICATION.md) and [technical specification](TECHNICAL-SPECIFICATION.md).

## First release

- Structural outline for two-sided diffs, attached to the native diff, per file only. It opens by default, with a setting to turn it off and a toolbar button in every diff to switch to the plain native diff.
- Comparison engine interface, registry, per-language default, and engine dropdown ([ADR 0004](adr/0004-pluggable-comparison-engines.md)).
- Declaration engine for Java and Kotlin, using IntelliJ PSI ([ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md)).
- Conservative classification: added, removed, changed, moved, formatting-only, uncertain, unanalyzed ([ADR 0003](adr/0003-conservative-classification.md)). Renamed declarations, including classes, stay literal (removed + added).
- IntelliJ Platform 2025.1 and newer, checked by Plugin Verifier on 2025.1 and the latest release.

## Later

### Multi-file summary in the Changes tree

The first item after the first release. Show changed declarations for each file inside the IDE's existing Changes/Commit tree, rather than in a new panel (technical spec Q3).

- **Level 1:** a summary next to each file, for example `Parser.kt  2 changed · 1 added`.
- **Level 2:** expandable declarations under each file. Every file is parsed as the tree loads, so performance on large commits needs care.
- **Spike first:** which Changes tree extension points can add text or child nodes to file entries.

### Scala

Planned for the release after the first one (decided 2026-10-21). A Scala provider for the declaration engine, using the JetBrains Scala plugin's PSI. It needs no change to the engine interface, the matcher or the UI.

- **Packaging:** the provider registers in its own optional descriptor, `META-INF/smartdiff-scala.xml`, which depends on `org.intellij.scala`. The plugin keeps loading without Scala.
- **Dialects:** Scala 2 and Scala 3. Text-built PSI has no module, so the provider must choose the dialect itself (spike S4). Scala 3 optional braces make indentation significant, so the formatting-only rule needs an indentation-significant mode next to the Kotlin line-break mode.
- **Declaration kinds:** classes, traits, objects (including companions and package objects), enums and enum cases, `def`, `val`/`var`, type aliases, Scala 3 givens and extension methods, and top-level definitions. This adds `TRAIT`, `GIVEN` and `EXTENSION` to `DeclarationKind` (technical spec Q7).
- **Naming and signatures:** rules for anonymous givens, extension blocks, pattern definitions such as `val (a, b) = …`, and signatures with several parameter lists or `using`/`implicit` clauses (technical spec Q7).
- **Build and CI:** the Scala plugin is not bundled, so the build pins a Marketplace version compatible with the lowest supported IDE, and Plugin Verifier needs a compatible Scala plugin for every IDE it checks. The Scala PSI is not a public API, so internal-API usage is expected in verifier reports and must be reviewed each release.
- **Fixtures:** the standard set from the product spec for both dialects, plus companion objects, givens, extension methods and a braces-versus-indentation change in Scala 3.

### Structural outline for three-way merges

Show which declarations changed on each side of a merge, to help the user understand conflicts before resolving them. SMART Diff still never resolves or edits anything (merge conflict resolution stays a non-goal).

- **Approach:** run the selected comparison engine twice, base → ours and base → theirs, and join both results on base-side ranges. No change to the engine interface; every engine supports it.
- **Groups:** changed on both sides (likely conflicts), changed only in ours, changed only in theirs, added on one side, removed on one side while changed on the other.
- **Integration:** attach to IntelliJ's merge viewer (`MergeTool` / `TextMergeViewer`, to be verified), and keep the outline in sync while the user edits the result pane. The same join also serves three-sided diffs that are not merges.
- **Spike first:** can the outline attach to the merge viewer for VCS merge conflicts and stay correct while text changes?
- **Product question:** should "changed on both sides" be the primary grouping in the merge outline?

### More languages

Add declaration-engine providers for further languages the host IDE parses, after [Scala](#scala) (for example JavaScript/TypeScript in Ultimate/WebStorm, Python in PyCharm), each with the fixtures the product spec requires.

### Tree-sitter support

A Tree-sitter provider or engine for languages the host IDE cannot parse, after native packaging and grammar licence review ([research](RESEARCH.md#difftastic-and-alternatives)).

### Fine-grained differencing

A second comparison engine that highlights changed expressions inside declarations, using the result model's `details`. Compare a project-owned matcher with Difftastic and GumTree as baselines.

### Rename candidates

Pair a removed and an added declaration with similar bodies as an uncertain rename candidate; includes renamed classes. The first release keeps renames literal; revisit after validation shows how noisy that is (technical spec Q2).

### Third-party engines

Consider making the `comparisonEngine` extension point public once a second production engine has used the result model without breaking changes (technical spec Q5).
