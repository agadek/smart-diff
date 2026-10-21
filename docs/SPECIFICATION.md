# Product Specification: SMART Diff for IntelliJ-based IDEs

Status: initial proposal, 2026-10-20; revised 2026-10-21 (declaration-level first release, PSI as first parser provider, native-viewer panel as a candidate integration).

## Summary

A structure-aware addition to the IntelliJ diff that presents source changes in terms of declarations, so developers can see which classes, functions, and members changed without losing access to the normal line diff. SMART Diff is multi-language by design. The first release should be conservative: parse both sides, match declarations by identity, classify the result, and show details with the native diff, labeling uncertain matches rather than claiming program equivalence. Fine-grained syntax-node differencing is a later stage.

The product name is **SMART Diff**, and the repository is [github.com/agadek/smart-diff](https://github.com/agadek/smart-diff). Research found a direct feature-space overlap with [SemanticDiff](https://semanticdiff.com/), an existing product for VS Code and GitHub. SMART Diff is the chosen distinct name; confirm Marketplace ID and name availability before public release. The expansion of “SMART” is intentionally undecided.

## Problem

Line diffs can make mechanical formatting changes dominate a review, split one logical edit across unrelated hunks, and obscure which declaration or expression changed. Developers need a higher-level map of a change while retaining the exact source and familiar IDE diff actions.

## Target user and primary use case

Developers reviewing local, VCS, or arbitrary two-file changes in an IntelliJ-based IDE. They open the IDE's ordinary diff, see a structural outline of changed declarations and change kinds, then inspect any item in the native text diff.

## Goals

1. Show changed source grouped by containing declarations.
2. Distinguish added, removed, and changed declarations, and identify formatting-only changes when the lexer/parser can establish that safely.
3. Keep the original source readable and navigable, with links to the corresponding locations on both sides.
4. Preserve IntelliJ's native diff as the detail view and fallback.
5. Support unsupported, malformed, or partially parseable files without blocking the user's review.

## Non-goals for the first release

- Proving behavioral equivalence or reporting that a change is semantically safe.
- AI-generated explanations, cloud processing, or telemetry.
- Automatic code rewriting, merge conflict resolution, or patch application.
- Commit-level cross-file refactoring detection.
- Fine-grained syntax-node (expression-level) differencing; within a declaration, the native line/word diff shows the detail.
- Supporting every language in the first release, or languages the host IDE cannot parse.
- Hiding changed code by default based on speculative equivalence rules.

## Proposed first-release scope

### Viewer and workflow

- Integrate with the IntelliJ diff for text source comparisons where a supported parser is available. Two candidate integrations are evaluated in the prototype (see open decision 4):
  - a structure panel (declaration tree) attached to the native side-by-side viewer through `DiffExtension`, so the native diff stays the main view; or
  - an alternate viewer (`FrameDiffTool`) in the viewer switcher, which must not replace the user's default viewer initially.
- Present a file-level summary and a navigable tree of changed declarations.
- Selecting an item focuses the matching old/new source region. Where there is no reliable counterpart, show it as added or removed.
- Use IDE diff colors and theme-aware presentation; retain keyboard navigation and accessibility labels.

### Change analysis

The first-release analysis is declaration-level:

1. Parse each side and extract declarations: kind, name, signature where available, parent, and source range.
2. Match declarations by kind, qualified name, and signature. Overloads and duplicate names are matched only when unambiguous; otherwise the pair is uncertain.
3. For each matched pair, compare token sequences to decide changed versus formatting-only; show the detailed edit with the native diff.
4. Source outside any declaration (imports, top-level statements, file headers) is grouped as a file-level region and compared the same way.

### Change classification

- Initial classes: added, removed, changed, moved, formatting-only, and uncertain.
- “Moved” means a declaration matched by identity whose position or parent changed. It is not presented as a refactoring.
- Renames are not detected in the first release. A rename appears as one removed and one added declaration; a later stage may pair them as a “rename candidate” using body similarity, labeled uncertain.
- Mark unmatched or ambiguous structure as uncertain. Do not silently suppress it.
- Formatting-only means the two token sequences are equal after ignoring whitespace tokens, except where the language makes whitespace significant (for example indentation in Python or YAML, and inside literals). Comments and string contents count as changes. It does not mean runtime behavior is unchanged.
- Preserve comments, string contents, whitespace-sensitive syntax, and unknown tokens unless language-specific parsing proves they are irrelevant.

### Language support

- Design the analysis for multiple languages from the start: the shared model is a parser-neutral declaration/region model, not any one language's PSI tree.
- Keep parser integration behind a provider interface that supplies declarations, tokens, and source ranges.
- The first provider is IntelliJ PSI: it parses in-memory text, needs no bundled native code or grammars, and covers the languages the host IDE supports. Language coverage therefore varies by IDE (for example, IntelliJ IDEA Community lacks JavaScript/TypeScript and Python support without additional plugins).
- A Tree-sitter provider is a later option for languages the host IDE cannot parse, subject to native packaging and grammar license review.
- Choose the prototype languages from what PSI supports in the target IDEs; the exact first-release list is open.
- Show a clear unsupported-language state and offer the native text diff.
- Treat incomplete/error parses as partial analysis: keep all source visible and identify affected regions as unanalyzed.
- Add each language only with fixtures for syntax, formatting sensitivity, literals, comments, and parse errors.

### Settings

- Whether the structural view opens by default (initial default: off, native diff only).
- Show or de-emphasize formatting-only changes (initial behavior: show them in a collapsible category; never discard source evidence).
- No global “ignore comments/whitespace” rule in the first release.

## User stories

- As a reviewer, I can see which declarations changed before reading every changed line.
- As a reviewer, I can jump from a structural change to its exact source in both versions.
- As a reviewer, I can use the native text diff whenever structure matching is confusing.
- As a reviewer, I can distinguish an actual detected change from a formatting-only or uncertain match.
- As a reviewer, malformed or unsupported input still opens in the normal diff viewer.

## Functional requirements

1. Accept the same old/new text contents and metadata supplied to the IDE diff viewer.
2. Analyze each side independently with the parser provider for its language when available.
3. Build a deterministic mapping from declarations on the old side to declarations on the new side.
4. Classify mapped and unmapped declarations and file-level regions; retain source ranges for navigation.
5. Render a usable result for empty files, identical files, add/delete, syntax errors, and large inputs.
6. Provide native diff fallback for unsupported input and analysis failures.
7. Recompute safely when the diff request changes; do not perform parsing or matching on the UI thread for expensive inputs.
8. Respect cancellation and avoid retaining project/file content after the viewer is disposed.

## Quality requirements

- No source content leaves the IDE process.
- Analysis is deterministic for fixed inputs and platform/parser versions.
- Analysis failure never loses or hides source text.
- Large-file limits must be configurable or produce a clear fallback; avoid blocking the UI.
- Accessibility: keyboard-accessible change navigation, meaningful labels, theme-aware contrast.
- Compatibility baseline follows the plugin skeleton: IntelliJ Platform 2025.1+, subject to prototype/API verification.

## Success criteria

- A reviewer can identify the changed declaration(s) in a representative multi-hunk edit faster than by scanning the raw diff in a small usability check.
- Exact source for every displayed change is reachable in both sides or explicitly marked as absent.
- Formatting-only changes are not presented as behavioral claims.
- Unsupported and malformed inputs consistently remain reviewable in the native diff.
- Viewer remains responsive on ordinary source files; oversized inputs degrade to a clear fallback.

## Risks and open decisions

1. **Name:** SMART Diff distinguishes the product from SemanticDiff. Confirm the Marketplace ID and name availability before publishing; do not imply affiliation.
2. **Meaning of semantic:** This proposal chooses structural review and narrow syntax-aware noise reduction, not syntactic or behavioral equivalence.
3. **Language coverage:** PSI-first means coverage depends on the host IDE and installed plugins. Decide whether that is acceptable for the first release or whether a Tree-sitter provider is needed earlier for specific languages.
4. **Diff integration:** Compare a `DiffExtension` structure panel on the native viewer with a separate `FrameDiffTool` viewer, for the IDE/VCS request types in scope. Prefer the panel if it can attach reliably, because it keeps native editing, navigation, and VCS actions.
5. **Fine-grained differencing (later stage):** Expression-level differencing inside a declaration is out of the first release. When it is added, compare a project-owned matcher with Difftastic and suitable GumTree generators as baselines. See [Research notes](RESEARCH.md#difftastic-and-alternatives).
6. **Parsing transient diff inputs:** IDE diff inputs may not correspond to physical project files. Confirm that PSI parses in-memory contents with reliable source ranges, including for VCS revisions without a project context.
7. **Multi-file reviews:** VCS changes usually span many files, but this proposal only defines a per-file view. Decide whether the first release needs a change-list-level overview of changed declarations.
8. **Ambiguous matches:** Define the rules and UX for overloads, duplicate names, and future rename candidates before presenting any pairing as certain.

## Initial implementation sequence

1. Spike both diff integrations (`DiffExtension` panel and `FrameDiffTool` viewer) and choose one.
2. Define the parser-neutral declaration/region model and the provider interface.
3. Implement the PSI provider for one or two languages the target IDEs support (Java and Kotlin are natural first candidates in IntelliJ IDEA), with fixtures.
4. Implement declaration matching and classification; render the declaration tree with source navigation; keep the native diff as the detail view.
5. Add token-level formatting-only detection with explicit examples and regression fixtures, one language rule at a time.
6. Validate on a representative corpus of real changes; then decide on further languages, a Tree-sitter provider, and fine-grained differencing.

## Acceptance checks for the prototype

- Opens from the IDE diff for comparisons in each selected prototype language.
- Shows changed declarations and locates their source ranges on both sides in each selected prototype language.
- Handles added and removed methods, identical files, empty files, parse errors, and unsupported files.
- Native text diff remains available.
- Ambiguous mappings remain visible and are not called equivalent.
