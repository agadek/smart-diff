# SMART Diff: Research Notes

Research snapshot: 2026-10-20; recommendation revised 2026-10-21.

## Product landscape

### SemanticDiff

[SemanticDiff](https://semanticdiff.com/docs/what-is-semanticdiff/) is an existing language-aware diff product for Visual Studio Code and GitHub. It parses supported source formats, suppresses selected whitespace and token changes, applies language-specific equivalence rules, and can identify moves and renames. It also has a fallback diff for unsupported text. Its [invariances list](https://semanticdiff.com/docs/invariances/) shows that equivalence rules vary considerably by language.

This is a direct feature-space overlap with the original working name “Semantic Diff.” The project has since adopted **SMART Diff** to distinguish its product name. Avoid implying affiliation with SemanticDiff; confirm the final Marketplace ID and name availability before publishing.

The product's [description of its diff model](https://semanticdiff.com/docs/understand-diff/) is useful: it compares corresponding blocks rather than insisting that every old line map to one new line. It also exposes the tradeoff: structural views can be more informative but no longer preserve line-for-line alignment expected from a conventional diff.

#### What is publicly known about its implementation

SemanticDiff publishes a high-level description, but its diff-engine source is not in its [public GitHub repository](https://github.com/Sysmagine/SemanticDiff), which is used for bug reports and community discussions. Its [SemanticDiff vs. Difftastic article](https://semanticdiff.com/blog/semanticdiff-vs-difftastic/) says the engine parses both versions into syntax structures, enriches the structures with language-specific information and invariance rules, aligns the old and new structures, then uses node/source positions to render changes. It also says the comparison still uses textual similarity for identifiers and strings, and that SemanticDiff detects moves and groups repeated edits such as renames. These are product-author descriptions, not a published implementation or algorithm specification.

The article does not identify the parser technology used for every supported language or disclose the exact tree-matching algorithm. One concrete implementation detail is public: the [0.10.0 release notes](https://semanticdiff.com/blog/semanticdiff-0.10.0/) state that SemanticDiff switched its C# parser to Roslyn, and that old/new parsing was parallelized in that release. Do not infer from this that it uses Tree-sitter for the other languages. Its [VS Code Marketplace listing](https://marketplace.visualstudio.com/items?itemName=semanticdiff.semanticdiff) says processing happens locally; that describes deployment, not its internal architecture.

### AST differencing research

AST differencing can express insertions, deletions, updates, and moves at syntax-node level. GumTree is a prominent implementation and its [ASE paper](https://www.labri.fr/perso/xblanc/data/papers/ASE14.pdf) describes fine-grained source-code differencing. However, mapping is uncertain: the [differential evaluation of AST mapping algorithms](https://arxiv.org/abs/2103.00141) reports inaccurate mappings for a material share of revisions across several algorithms. A recent [semantic-aware AST differencing study](https://arxiv.org/abs/2403.05939) identifies unresolved issues including incompatible node matches, language clues, refactoring awareness, and commit-level support.

This argues for conservative matching, visible fallback behavior, and a conventional diff that remains available. A syntax tree describes structure; it does not prove that two programs behave equivalently.

## Difftastic and alternatives

### Difftastic

[Difftastic](https://github.com/Wilfred/difftastic) is a mature, widely used structural diff built around Tree-sitter. It converts parser trees to a simplified syntax representation and computes a structural comparison. Its README reports support for 30+ programming languages, syntax-aware whitespace handling, Git/VCS integration, and line-diff fallback for unrecognized languages or parse errors. It explicitly does not produce applicable patches, and lists performance and memory use on heavily changed files as known issues. It preserves child order rather than treating reorderings as invariant. The project is MIT-licensed; vendored grammars include MIT- and Apache-licensed components, each with its own notice.

For this plugin, the major limitation is integration shape. The published `difftastic` crate remains an executable rather than a consumable library, while the CLI now has a machine-readable JSON display mode. That makes a subprocess integration feasible, but does not provide a stable plugin protocol: the CLI docs warn that display output can change, and the JSON still needs validation for complete source ranges and mapping data SMART Diff's viewer needs. Its terminal presentation is not a ready-made IntelliJ viewer. A supported embedded API would require maintaining a Rust fork/library boundary or adding a wrapper around Difftastic internals.

Two further limits matter for SMART Diff's first release, and the spike should confirm both against the current release. First, the JSON display mode is documented as unstable and must be enabled explicitly (`DFT_UNSTABLE=yes`). Second, it reports changed lines and highlighted spans per side; it does not expose an old-to-new node mapping or the declaration that contains each change. Grouping changes by declaration would therefore still need a parser on the plugin side, so Difftastic adds native packaging cost without removing the parser work.

### Cost and architecture for using the Rust engine

The estimate below assumes SMART Diff keeps the JetBrains plugin and user interface in Kotlin/JVM, reuses Difftastic's parser and matcher, and supports three first-release operating-system targets: macOS Apple Silicon, macOS Intel, and Windows/Linux x64 (exact packaging targets remain a product decision). It includes a multi-language baseline, native diff fallback, and a release-quality local integration; it excludes implementing new diff semantics, porting Difftastic to Java, marketplace/release operations, and broad language-specific tuning beyond what Difftastic already provides. One engineer experienced with Kotlin/JVM and comfortable making small Rust changes is assumed; estimates are elapsed full-time engineering effort and are planning ranges.

| Workstream | Estimate | Deliverable |
| --- | --- | --- |
| Feasibility spike | 1–2 engineer-weeks | Invoke CLI from a prototype viewer; inspect JSON output for representative edits in three languages, error handling, source locations, startup time, and cancellation behavior |
| Base integration architecture | 2–4 engineer-weeks | Kotlin engine interface, request/result model, async job and cancellation lifecycle, temp-file or streaming-input strategy, JSON adapter, language detection/overrides, native-diff fallback, and versioned protocol boundary |
| Rust adapter and result contract | 2–4 engineer-weeks | If stock JSON is insufficient, maintain a small Rust wrapper/fork that accepts structured requests and returns stable changed ranges/mappings, with compatibility tests against a pinned Difftastic version |
| Packaging and release hardening | 2–4 engineer-weeks | Build and bundle per-target executables or libraries, extraction/permissions, startup and crash handling, size/performance checks, notices/SBOM, upgrade policy, and CI packaging validation |
| Viewer and end-to-end hardening | 2–4 engineer-weeks | Map structural results to IDE source editors, validate navigation/highlighting, large inputs and parse failures, and complete a cross-platform regression corpus |

**Planning total:** about **7–14 engineer-weeks** (roughly 2–3.5 months for one engineer) for the feasibility spike, base integration, packaging, and viewer hardening; this assumes the stock CLI output is adequate. A fast prototype may take **1–2 weeks**, but a 5–9 week production path assumes JSON already provides the needed locations and the supported platform matrix stays small. Add roughly **2–4 weeks** if a custom Rust adapter/API is needed; additional targets or substantial output adaptation can bring the total to **10–18 weeks**. The feasibility spike should resolve that largest uncertainty before committing to the integration path.

Recommended base architecture:

1. Keep the IntelliJ plugin, diff viewer, and IDE source navigation in Kotlin/JVM.
2. Define an internal `StructuralDiffEngine` request/result interface independent of Difftastic. Requests carry old/new text, language hint, and limits; results carry status, changed source ranges on each side, and fallback reason.
3. Start with a pinned Difftastic executable per supported OS/architecture, invoked off the UI thread. Use isolated temporary inputs with cleanup, bounded execution time/output, cancellation, and no shell interpolation. Parse JSON into the internal model and retain the native IDE diff when parsing, execution, or mapping fails.
4. Hide all CLI/version-specific behavior behind the adapter. If JSON lacks accurate range mapping or proves unstable, replace the adapter implementation with a small Rust sidecar/library protocol without changing the viewer contract.
5. Bundle and inventory Difftastic plus each included parser license. Pin the upstream version and make upgrades explicit, with corpus and output-compatibility review.

If SMART Diff adopts a Difftastic backend for fine-grained differencing, this is likely the lowest-risk architecture for it, because it reuses the existing multilingual parser/matcher while keeping IDE behavior native. It is not needed for the declaration-level first release (see the recommendation below). The main ongoing cost is owning the Rust/JVM boundary and target-specific binaries, not writing the diff algorithm. A stable long-lived sidecar could reduce per-diff process startup overhead but adds protocol, lifecycle, and crash-recovery complexity; only pursue it if the spike shows process launch is a user-visible bottleneck. Difftastic's own documentation reports poor scaling on heavily changed files, so retain limits/fallbacks and measure realistic inputs.

### Effort to port Difftastic's diff algorithm to Java

The central algorithm is understandable: Difftastic maps positions in two simplified syntax trees to vertices in an implicit directed acyclic graph, assigns costs to matching versus inserting/deleting syntax, and uses Dijkstra's algorithm to find a low-cost route. It creates graph neighbors as it searches rather than materializing the whole graph. The difficult work is making the syntax representation and costs produce consistently good diffs, preserving balanced delimiters and source positions, handling parser errors/fallbacks, and keeping pathological inputs responsive. The [diffing internals](https://github.com/Wilfred/difftastic/blob/master/manual/src/diffing.md) and [parsing internals](https://github.com/Wilfred/difftastic/blob/master/manual/src/parsing.md) explain the model, while the evolving implementation and regression corpus show there is ongoing edge-case and performance work.

The parser choice changes the scope. SMART Diff should be multi-language by design, so no single language's PSI tree should be the shared model. A parser-neutral model can accept multiple front ends: IntelliJ PSI for languages the host IDE supports, and Tree-sitter for broader coverage. PSI coverage varies by IDE and installed plugins, so it cannot alone define the product's long-term language coverage. Any Tree-sitter route needs a compatible JVM/native integration, grammar packaging and per-grammar license review; an external Difftastic process or Rust bridge would trade parser and matcher reimplementation for native executable/library integration. These options need a small multi-language prototype before selection.

The following are planning estimates, not published project data. They assume one experienced JVM engineer working full time, no exact reproduction of Difftastic's terminal UI, and effort for implementation plus a small useful regression corpus:

| Port scope | Rough effort | What is included |
| --- | --- | --- |
| Multi-language feasibility prototype (three parsers) | Roughly 3–6 engineer-weeks | Parser-provider boundary, shared syntax model, matcher/source ranges, viewer integration, and representative fixtures across three languages; parser/native packaging choice is a major variable |
| Multi-language plugin-quality engine | Roughly 3–6+ engineer-months | Multiple parser integrations, per-language tree normalization and delimiter/atom configuration, language detection, compatibility, regression/performance work; estimate grows with language count and desired fidelity |

These ranges are uncertain and could shift after a small benchmark. Reusing the Difftastic CLI avoids rewriting its matcher but retains native executable packaging and output-adaptation costs. Rewriting only the matcher in Java avoids subprocesses but is not a literal port if another parser family replaces Tree-sitter: node shapes and parser error recovery differ, so algorithm weights and matching behavior need retuning. A useful first spike should exercise the same shared model and matching behavior over several languages, rather than optimize for Java alone.

### License compliance for an agent-assisted port

Difftastic's core repository is under the [MIT License](https://github.com/Wilfred/difftastic/blob/master/LICENSE), with copyright attributed to Wilfred Hughes. MIT permits modification, distribution, and commercial use, provided the copyright and permission notice accompany copies or substantial portions. If an agent translates or ports Difftastic implementation code, treat it as a derivative/ported implementation and preserve the upstream notice and license in the distribution. A practical setup is to include a `THIRD_PARTY_NOTICES.md`, identify the Difftastic-derived component and upstream URL, include the full MIT license, and retain a source-file attribution where code was ported. The exact legal characterization of any particular port is fact-specific; this is a conservative compliance approach, not legal advice.

Keep provenance explicit in agent tasks: name the upstream files consulted, ask the agent to list copied or translated code, and review diffs for embedded Rust comments, code, or license headers. If implementing only from the published algorithm description and independently written code, do not copy source text or substantial expressive details; record the design references anyway. Avoid describing an independent implementation as Difftastic itself. Using an AI agent does not remove the need to comply with the source license.

Parser licenses are separate from Difftastic's license. Difftastic says its vendored Tree-sitter parsers include MIT- and Apache-licensed components and keeps license files under `vendored_parsers/*/LICENSE`; check every grammar actually bundled rather than assuming all are MIT. For SMART Diff, licenses must be reviewed for the complete selected language set and parser runtime. IntelliJ PSI may avoid bundling grammars for a given provider, but does not address other parser providers or dependencies. GumTree's LGPL-3.0 terms are a separate compliance question and are one reason not to add it without review.

The current SMART Diff skeleton has no project `LICENSE` or third-party notices file yet. Before distributing a plugin, choose SMART Diff's own license, inventory all runtime and bundled components (including parser grammars and native binaries), include required notices in the plugin distribution, and verify that the chosen project license is compatible with each dependency. Maintain a simple dependency/license inventory in CI or release preparation. A qualified open-source licensing review is appropriate before Marketplace or commercial distribution if a close port is planned.

### Using a coding agent for the Java port

A coding agent can produce much of the first implementation, especially when the port is divided along stable interfaces. It can translate Rust data structures and graph-search logic, implement syntax-tree adapters, scaffold JVM/IDE integration, generate candidate edge-case fixtures, and compare Java output with Difftastic on a supplied corpus. Those tasks reduce hand-transcription and boilerplate work; they do not remove the need to decide whether a match is correct or useful.

The most effective setup is one owner for the algorithm and acceptance criteria, with agent tasks separated by artifacts. For a multi-language effort, keep the shared model and matcher centrally owned while parser adapters can be developed and reviewed independently:

1. Have the agent document the Rust algorithm and relevant Difftastic cases before translating it; record the behavior each case is intended to preserve.
2. Keep parser/tree adaptation, graph matching, and IntelliJ viewer integration behind small interfaces. Delegate or sequence those pieces independently, then integrate against the agreed model.
3. Use a golden comparison corpus covering formatting, nested delimiters, reordered siblings, repeated identifiers, insertions/deletions, parser errors, and large/pathological inputs. Compare mappings and source ranges, not just rendered screenshots.
4. Have a human review algorithm changes and ambiguous mappings. Agents can implement proposed costs and heuristics, but should not silently choose product semantics such as whether reorderings, renames, or comments count as equivalent.
5. Require reproducible benchmark results and platform builds before calling the port complete. Generated code that compiles can still produce poor mappings or block the IDE.

Agent assistance may shorten initial implementation time, especially for translation and scaffolding, but it does not justify a dependable percentage reduction to the estimates. A time-boxed spike should target several parsers and a narrow set of shared cases to expose differences in node shape, error recovery, and source ranges. Parallel agents are most useful on isolated research, fixture authoring, and parser adapters; the matching core should have one owner to avoid incompatible algorithm assumptions.

### IntelliJ PSI

The IntelliJ Platform's [PSI](https://plugins.jetbrains.com/docs/intellij/psi.html) gives plugins a language-specific syntax tree through the language support installed in the IDE. The platform documents creating in-memory PSI from text with `PsiFileFactory`, which is relevant because diff sides are often transient contents rather than files in the project. This offers the most native route to parse the old/new sides and navigate between PSI nodes and source offsets.

For a declaration-level diff, PSI provides most of what is needed: named declarations with kinds, parents, and source ranges, plus a lexer for token-level comparison. It requires no bundled native code or grammar licenses, and its ranges map directly to IDE editors and navigation.

PSI is not a diff algorithm: SMART Diff would still need matching/classification and a UI model. Language support depends on the target IDE and installed plugins. For example, IntelliJ IDEA Community includes Java, Kotlin, Groovy, XML, JSON, YAML, and properties support, while JavaScript/TypeScript requires Ultimate or WebStorm and Python requires PyCharm or the Python plugin. Parsing APIs are coupled to the IntelliJ Platform and need read actions. The plugin must handle absent language parsers and malformed PSI without hiding source. UAST/PSI abstractions should not be assumed to provide identical tree detail across languages and should not constrain the shared model.

On balance, PSI is the most practical first provider for SMART Diff: reviewers mostly read code in languages their IDE already parses, and the coverage gap can be filled later by a Tree-sitter provider behind the same interface.

### GumTree

[GumTree](https://github.com/GumTreeDiff/gumtree) is a syntax-aware differencing engine whose output is edit actions aligned to syntax; it also targets moved and renamed elements. Its repository contains multiple generators (including JDT, JavaParser, Tree-sitter, XML, JSON, and others), so supported languages and tree detail depend on generator choice. GumTree is closer to a reusable edit-script engine than Difftastic, but SMART Diff would still need to adapt its edit actions into an IntelliJ viewer and connect actions back to offsets in each source. The repository license is LGPL-3.0 with additional permissions; check the exact terms and notices before bundling it.

### RefactoringMiner

[RefactoringMiner](https://github.com/tsantalis/RefactoringMiner) is a Java library aimed at detecting refactorings in repository history. Since version 3.0 it also produces AST diffs, including cross-file moves and cross-language migration cases. It is a strong candidate if the product expands toward Java refactoring explanations or commit-level change maps, but its core emphasis is Java/repository history and recognized refactorings rather than a general, language-agnostic PSI viewer. The repository is MIT-licensed.

### Other relevant options

- **Tree-sitter with a project-owned matcher:** offers broad parser coverage and resilient syntax trees, while leaving the matcher and review UI under SMART Diff's control. It is the leading candidate for extending coverage beyond the languages the host IDE parses. The cost is integrating/packaging native grammars, tracking grammar licenses and versions, and reproducing language-specific configuration that Difftastic has already built.
- **Native IntelliJ line/word diff:** has excellent workflow integration and remains the essential fallback, but it does not provide declaration mapping or structural change classification on its own.
- **SemanticDiff:** the closest product comparison, not a reusable engine. Its language-specific normalization and move/rename features are useful UX references, but its implementation is not available as an engine for this project.
- **Mergiraf:** relevant to structural merge, not a direct alternative for a read-only review diff; merge behavior is out of this project's initial scope.

Additional candidates worth separating by what they diff:

- **GumTree-Spoon AST Diff:** a Java library that parses or accepts Spoon ASTs, runs GumTree, and returns edit actions connected to original Spoon nodes and source positions. It also accepts two source snippets directly. It is a convenient Java-specific benchmark for comparing mapping quality on Java fixtures, but it cannot serve as the multi-language engine. It is Apache-2.0, but review the licenses of its transitive dependencies (including GumTree) separately.
- **sem (Ataraxy Labs):** entity-level change review over functions/classes/methods, with move/rename detection, JSON output, and an MCP server for coding agents. It advertises 28 Tree-sitter languages and offers `sem-core` as a Rust library. Its entity-level model is the closest existing match to SMART Diff's declaration-level first release and is a useful design reference for entity extraction and matching; as a dependency it would bring Rust/native packaging, and it is not a Java library or a fine-grained token/syntax-node viewer. The project is dual MIT/Apache-2.0.
- **diffsitter:** a Tree-sitter AST diff CLI with Java support and MIT license. Its own README calls it work-in-progress and not production-ready. Difftastic's structural-diff survey describes its approach as LCS over syntax-tree leaves. It is a useful experimental baseline, but does not remove the native executable or UI-adaptation work.
- **ChangeDistiller:** an older Java AST differencer that classifies fine-grained source changes and has a stand-alone Apache-2.0 release. Its change taxonomy and Java focus may be useful for comparison, but it is not a cross-language replacement for Difftastic.
- **RefDiff:** a Java library with Java, JavaScript, and C support for identifying refactorings across revisions (rename, move, extract, etc.). It is useful for a refactoring-detection layer, not a full detailed diff viewer by itself; its repository is MIT-licensed.

### Assessment for SMART Diff

| Option | Main strength | Main cost/limitation | Fit now |
| --- | --- | --- | --- |
| Parser-neutral declaration model + own matcher | Shared declaration-level diff across parser providers and languages; simple identity-based matching | Requires provider interface and per-language declaration extraction | Best fit for the first release |
| IntelliJ PSI provider | Native parsing, declarations, lexer, and source navigation; no native packaging | Platform coupling; availability varies by IDE and installed plugins | First provider for the first release |
| Difftastic CLI / Rust wrapper | Broad Tree-sitter language support and strong structural presentation; CLI has JSON display | Unstable JSON without node or declaration mapping; Rust/native packaging per target | Baseline for later fine-grained differencing, not the first-release engine |
| GumTree | Fine-grained syntax edit actions and move matching; Java integration possible | Generator-dependent behavior, LGPL-3.0 terms, custom viewer/source mapping still needed | Include in the matcher comparison benchmark; not a default dependency |
| GumTree-Spoon | Java API; accepts snippets and ties edit actions to Spoon nodes/source positions | Java-only; adds Spoon/GumTree dependencies and their license inventory | Java-specific comparison candidate, not a multi-language foundation |
| RefactoringMiner | Java refactorings, AST diff, and cross-file/cross-language cases | Repository-history and Java emphasis; likely broader than first-release need | Consider for later Java refactoring layer |
| RefDiff / ChangeDistiller | Existing Java-oriented refactoring or change-classification algorithms | Older/research-oriented; narrower change model than a general viewer | Compare only if those classifications match the product needs |
| sem | Entity-level diff, JSON and MCP for agents; broad Tree-sitter language set | Rust engine; native packaging; not a fine-grained IntelliJ source viewer | Design reference for declaration extraction and matching |
| diffsitter | Tree-sitter AST diff with Java support | Rust CLI; self-described as not production-ready | Experimental comparison only |
| Tree-sitter provider | Language breadth beyond what the host IDE parses | Native JVM integration, grammar packaging, licensing, and maintenance work | Later provider for languages PSI cannot cover |
| Native text/word diff | Reliable, familiar, editable IDE workflow | Line-oriented mapping | Always retain as fallback |

**Recommendation (revised 2026-10-21):** the specification's first release is declaration-level, not a fine-grained syntax-node diff, and the engine choice should follow from that. Treat multi-language support as an architectural requirement through a parser-neutral declaration model and provider interface. Start with IntelliJ PSI as the first provider: parse both sides, extract declarations, match them by kind, qualified name, and signature, detect formatting-only changes from token sequences, and show detail with the native diff. This needs no tree-matching algorithm, native binaries, or grammar licenses. Use `sem` as a design reference for entity extraction and matching. Add a Tree-sitter provider later for languages the host IDE cannot parse. Defer fine-grained differencing; when it is added, compare a project-owned matcher with Difftastic and suitable GumTree generators, keeping GumTree-Spoon as a Java-only benchmark. The first-release language list remains open, constrained by PSI coverage in the target IDEs.

## JetBrains platform fit

The IntelliJ Platform exposes diff viewers through extension points including [`com.intellij.diff.DiffTool`](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html). JetBrains' [`DiffManager`](https://github.com/JetBrains/intellij-community/blob/master/platform/diff-api/src/com/intellij/diff/DiffManager.java) documentation distinguishes adding a custom viewer (`FrameDiffTool` for supported requests) from customizing existing viewers (`DiffExtension`). A separate project, `markdown-review` (not part of this repository), demonstrates registering a `DiffTool` in `plugin.xml` and targeting IntelliJ Platform 2025.1.

The normal IDE diff viewer already supports navigation, editing, and comparisons from VCS workflows ([JetBrains Diff Viewer docs](https://www.jetbrains.com/help/idea/differences-viewer.html)). A plugin should preserve that workflow and give users an easy route back to the native text diff.

`DiffExtension` is worth evaluating alongside a separate `FrameDiffTool`: it lets a plugin attach UI, such as a declaration tree, to the existing side-by-side viewer. That would keep native editing, navigation, and VCS actions available without switching viewers. Whether it attaches reliably for all request types in scope, and how much layout control it gives, needs a prototype.

## Product implications

- “Semantic” should mean language-aware structure and explicitly documented equivalence rules, not a claim of full behavioral equivalence.
- Syntax tree availability is language- and IDE-plugin-dependent. Unsupported languages and parse failures need a reliable text fallback.
- Formatting-only suppression must be parser-aware; global whitespace stripping is incorrect for indentation-sensitive languages, literals, and embedded languages.
- Moves, renames, and cross-file changes are valuable but harder than highlighting syntax-node insertions and removals. They should follow a trusted within-file structural diff.
- An alternate diff view is a lower-risk first integration than replacing the native viewer by default.

## References

- [SemanticDiff: What is SemanticDiff?](https://semanticdiff.com/docs/what-is-semanticdiff/)
- [SemanticDiff: Invariances](https://semanticdiff.com/docs/invariances/)
- [SemanticDiff: Understanding the Diff](https://semanticdiff.com/docs/understand-diff/)
- [SemanticDiff: Moved Code](https://semanticdiff.com/docs/understand-diff/moved-code/)
- [SemanticDiff vs. Difftastic: How do they differ?](https://semanticdiff.com/blog/semanticdiff-vs-difftastic/)
- [SemanticDiff 0.10.0 release notes](https://semanticdiff.com/blog/semanticdiff-0.10.0/)
- [SemanticDiff public support repository](https://github.com/Sysmagine/SemanticDiff)
- [SemanticDiff VS Code Marketplace listing](https://marketplace.visualstudio.com/items?itemName=semanticdiff.semanticdiff)
- [Difftastic repository and README](https://github.com/Wilfred/difftastic)
- [Difftastic internals: parsing](https://github.com/Wilfred/difftastic/blob/master/manual/src/parsing.md)
- [Difftastic internals: diffing](https://github.com/Wilfred/difftastic/blob/master/manual/src/diffing.md)
- [Difftastic crate documentation](https://docs.rs/crate/difftastic/latest) (identifies the crate as not a library)
- [Difftastic CLI manual](https://github.com/Wilfred/difftastic/blob/master/difft.1.md) (documents JSON display mode and its output options)
- [Difftastic MIT license](https://github.com/Wilfred/difftastic/blob/master/LICENSE)
- [Difftastic parser license note](https://github.com/Wilfred/difftastic#license)
- [Tree-sitter Java bindings and requirements](https://tree-sitter.github.io/java-tree-sitter/)
- [JNI-based Java Tree-sitter bindings](https://github.com/seart-group/java-tree-sitter)
- [Tree-sitter Java grammar and license](https://github.com/tree-sitter/tree-sitter-java)
- [IntelliJ Platform PSI documentation](https://plugins.jetbrains.com/docs/intellij/psi.html)
- [IntelliJ Platform PSI Files documentation](https://plugins.jetbrains.com/docs/intellij/psi-files.html)
- [GumTree repository](https://github.com/GumTreeDiff/gumtree)
- [GumTree license](https://github.com/GumTreeDiff/gumtree/blob/main/LICENSE)
- [GumTree-Spoon AST Diff](https://github.com/SpoonLabs/gumtree-spoon-ast-diff)
- [sem: entity-level diff and MCP](https://github.com/Ataraxy-Labs/sem)
- [diffsitter](https://github.com/afnanenayet/diffsitter)
- [ChangeDistiller project page](https://www.ifi.uzh.ch/en/seal/research/tools/changeDistiller.html)
- [RefDiff repository](https://github.com/aserg-ufmg/RefDiff)
- [RefactoringMiner repository](https://github.com/tsantalis/RefactoringMiner)
- [Falleri et al., Fine-grained and Accurate Source Code Differencing (ASE 2014)](https://www.labri.fr/perso/xblanc/data/papers/ASE14.pdf)
- [Wang et al., A Differential Testing Approach for Evaluating AST Mapping Algorithms](https://arxiv.org/abs/2103.00141)
- [Alikhanifard and Tsantalis, A Novel Refactoring and Semantic Aware AST Differencing Tool (2024)](https://arxiv.org/abs/2403.05939)
- [IntelliJ Platform Extension Point List](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html)
- [JetBrains IntelliJ Community DiffManager API](https://github.com/JetBrains/intellij-community/blob/master/platform/diff-api/src/com/intellij/diff/DiffManager.java)
- [JetBrains Diff Viewer documentation](https://www.jetbrains.com/help/idea/differences-viewer.html)

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Mark this as a historical, non-normative research snapshot.
- Remove or update “The current SMART Diff skeleton has no project LICENSE”; LICENSE exists.
- Update “confirm the final Marketplace ID”; the plugin ID is decided.
- Add a short “what changed since this snapshot” note: PSI provider, Scala, extraction detection and the Changes tree.
