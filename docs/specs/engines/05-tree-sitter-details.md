# Tree-sitter engine: details

Status: proposed, 2026-11-17

**Feature:** [Tree-sitter engine](05-tree-sitter.md)

## Design

### 1. Phases

| Phase | Work | Done when |
| --- | --- | --- |
| T0 | Spike S12 (§2), ADR 0016 for the parser runtime, the language resolver of [ADR 0015](../../adr/0015-tree-sitter-languages-are-chosen-by-file-name.md) and the setting | S12's questions are answered in a test or a recorded manual run, and ADR 0016 is accepted |
| T1 | The engine, the tree builder and Swift (§4 to §8) | The Swift golden fixtures and the engine contract suite pass on every platform of R22 |
| T2 | C#, C and C++ | Their golden fixtures pass, and the archive size check holds (R28) |
| T3 | Merge and benchmark fixtures, and a corpus validation | R30 holds, the benchmark's `no-new-wrong-pairs` gate passes, and the results are in [VALIDATION.md](../../VALIDATION.md) |

T1 can start only after ADR 0016 is accepted. T2's three languages are independent of each other.

### 2. Spike S12

S12 is added to the [technical specification §13](../../TECHNICAL-SPECIFICATION.md#13-prototype-spikes). It answers:

1. **Runtime.** Which way of running Tree-sitter meets R22 to R28 on the build target, IntelliJ IDEA Community 2025.1.3 on JetBrains Runtime 21:
   - **A, a JNI binding with prebuilt native libraries**, such as `tree-sitter-ng`: fast, but one native library per grammar and platform, and a crash in native code ends the IDE process, which breaks R24 unless the parser runs in a child process.
   - **B, grammars compiled to WebAssembly and run on a JVM WebAssembly runtime**, such as Chicory: one artifact per grammar for every platform and no native code in the IDE process, so a grammar fault is an exception (R22, R24); parse time must be measured.
   - **C, the official Java binding `java-tree-sitter`**, which uses the Foreign Function and Memory API; S12 checks whether it runs on JetBrains Runtime 21 without preview flags, which the plugin cannot set.
   - **D, a child process** that parses and returns the trees over standard input and output: isolates crashes for option A, at the cost of starting and managing a process.
   
   S12 measures, for each option it can run: the archive growth for the four grammars (R28), the time to parse a 1 MB C++ file and a 1 MB Swift file on both sides (within the 5 s [time limit](03-analysis-limits.md)), whether a deliberately failing grammar call ends the IDE (R24), and whether the plugin verifier accepts the result. It records the runtime and each grammar's licence (R27). The choice is ADR 0016.
2. **Language ids.** Which file type and language id IntelliJ IDEA 2025.1 gives `.swift`, `.cs`, `.c`, `.h` and `.cpp` files with the bundled TextMate bundles on and off, and the same on the latest release. ADR 0015 assumes plain text, the TextMate language or no language; S12 confirms it or the ADR is changed.
3. **Rider and CLion Nova.** Which language id and PSI Rider gives `.cs` files and CLion Nova gives `.cpp` files, and whether the [structure-view fallback](../languages/06-structure-view-fallback.md) already shows their declarations. The result is recorded only; using Tree-sitter there is out of scope.

### 3. Choosing the language

[ADR 0015](../../adr/0015-tree-sitter-languages-are-chosen-by-file-name.md) records the decision. A new object, `EngineLanguage` in `engine.registry`, has one function:

```kotlin
fun of(languageId: String?, fileName: String?, treeSitterEnabled: Boolean): String?
```

It returns `languageId` unchanged unless that id is null, `PlainText.id` or the TextMate language's id, the setting is on, and the file name's extension, compared ignoring case, is in the table of R3. Then it returns the Tree-sitter language id: `tree-sitter.swift`, `tree-sitter.c-sharp`, `tree-sitter.c` or `tree-sitter.cpp`. These ids are constants in `engine.api.LanguageIds` and are never an IntelliJ language id, so no PSI provider or other engine claims them (R4).

Every place that selects an engine calls `EngineLanguage.of` first and puts its result in `ComparisonRequest.languageId`: `DocumentContent.languageId()` and its three callers in `OutlineDiffExtension`, the restore check in `OutlineController` and `MergeOutlineController`, `MergeWindowSupport` and `ChangesSummary.request` (R6). Engines still never read `fileName` (engine contract), because the resolved id carries the language.

Because the resolved id has a supporting engine, the line comparison is not used for it ([engines/04](04-moved-lines.md) R4), which gives R5 without a change to `LineEngine`. With the setting off, `of` returns the IntelliJ id, so plain-text files reach the line comparison as before (R1).

### 4. The engine

`TreeSitterEngine` in a new package `io.github.agadek.smartdiff.engine.treesitter`, with id `smartdiff.treesitter` and display name `Declarations (Tree-sitter)`, is registered on the `comparisonEngine` extension point after the declaration engine. It supports exactly the four Tree-sitter language ids. No other engine supports them, so the [engine dropdown](../../GLOSSARY.md#engine-dropdown) stays hidden, and the settings page's default engine table lists the four languages with this one engine.

`compare(request, cancellation)`:

1. Find the language's `TreeSitterLanguage` (§5); a missing one is `UNSUPPORTED`.
2. Parse both sides with the runtime of ADR 0016, calling `cancellation.checkCanceled()` between the sides and, through the runtime's progress callback where it has one, during parsing.
3. Build a `DeclarationTree` for each side with `TreeSitterTreeBuilder` (§6).
4. Compare them with the declaration engine's `DeclarationAnalysis.compare`, with the request's limits and confidence policy, and set `analysisNote = AnalysisNote.TREE_SITTER` (`"tree-sitter"`, a new constant), which gives R7.

Reusing `DeclarationAnalysis` is what gives R21: matching, classification, rename candidates, kind changes, moves, extraction and inlining all work on the `DeclarationTree`, not on PSI. The rename grouping's reference pass needs PSI, so Tree-sitter declarations get no usage evidence. `DeclarationAnalysis` and its model move from `engine.declarations.analysis` and `engine.declarations.model` only if the architecture test forbids `engine.treesitter` to depend on them; then they move to a shared package `engine.structure` in the same change, without other edits.

`version` is `1 (swift <v>; c-sharp <v>; c <v>; cpp <v>; <runtime> <v>)`, from a resource file that the build writes from the pinned grammar versions (R29). Raise the leading number when the builder or a language table changes.

### 5. Language tables

Each language is a `TreeSitterLanguage` with its grammar, its `WhitespacePolicy` and a table that maps grammar node types to a declaration kind, the child field that holds the name, how the signature is built, and which node is the body. Node types not in the table are walked through only when the table marks them *transparent*; any other node is part of its declaration's own tokens. The node type names below are those of the grammars' current releases; S12 pins the versions and the T1 and T2 tests check each name.

**Swift** (`tree-sitter-swift`), `KOTLIN_LINE_BREAKS` (R19):

| Node | Kind | Notes |
| --- | --- | --- |
| `class_declaration` | `CLASS`, `STRUCT`, `ENUM`, `ACTOR` or `EXTENSION` | By its keyword child; an extension is named by its extended type |
| `protocol_declaration` | `PROTOCOL` | |
| `enum_entry` | `ENUM_CONSTANT` | One declaration per case name in `case a, b` |
| `function_declaration`, `protocol_function_declaration` | `FUNCTION`, or `METHOD` inside a type | |
| `init_declaration`, `deinit_declaration` | `CONSTRUCTOR`, `DESTRUCTOR` | `init?` and `init!` keep the mark in the name |
| `subscript_declaration` | `SUBSCRIPT` | Named `subscript` |
| `property_declaration`, `protocol_property_declaration` | `PROPERTY` | One per name in `let a = 1, b = 2` |
| `typealias_declaration` | `TYPE_ALIAS` | |

A Swift signature is each parameter's external label and type, `(from: Int, to: Int)`, with `_` for no label (R16), because Swift overloads differ by labels. Computed property and observer bodies (`get`, `set`, `willSet`, `didSet`) are the property's body (R13).

**C#** (`tree-sitter-c-sharp`), `INSIGNIFICANT`:

| Node | Kind |
| --- | --- |
| `namespace_declaration`, `file_scoped_namespace_declaration` | `NAMESPACE`; a file-scoped namespace's members are the declarations after it |
| `class_declaration`, `struct_declaration`, `record_declaration`, `interface_declaration`, `enum_declaration` | `CLASS`, `STRUCT`, `RECORD`, `INTERFACE`, `ENUM` |
| `enum_member_declaration` | `ENUM_CONSTANT` |
| `delegate_declaration` | `DELEGATE` |
| `method_declaration`, `constructor_declaration`, `destructor_declaration`, `operator_declaration`, `conversion_operator_declaration` | `METHOD`, `CONSTRUCTOR`, `DESTRUCTOR`, `OPERATOR` |
| `property_declaration`, `indexer_declaration`, `event_declaration`, `event_field_declaration`, `field_declaration` | `PROPERTY`, `INDEXER`, `EVENT`, `FIELD`; one per variable in a multi-variable field |

**C** (`tree-sitter-c`), `INSIGNIFICANT` with the directive rule of §7:

| Node | Kind |
| --- | --- |
| `function_definition`, a `declaration` whose declarator is a function declarator | `FUNCTION` |
| `struct_specifier`, `union_specifier`, `enum_specifier` with a name or a body | `STRUCT`, `UNION`, `ENUM` |
| `enumerator` | `ENUM_CONSTANT` |
| `field_declaration` | `FIELD`, one per declarator |
| `type_definition` | `TYPE_ALIAS`, named by its declarator; a `typedef struct { … } Name` is one declaration |
| `preproc_def`, `preproc_function_def` | `MACRO` |
| other `declaration` at file level | `VARIABLE`, one per declarator |
| `preproc_if`, `preproc_ifdef`, `preproc_else`, `preproc_elif` | transparent (R14) |
| `linkage_specification` (C++ only) | transparent |

**C++** (`tree-sitter-cpp`) adds:

| Node | Kind |
| --- | --- |
| `namespace_definition` | `NAMESPACE`; an anonymous namespace is named `(anonymous)` |
| `class_specifier` | `CLASS` |
| `function_definition` in a class body | `METHOD`, `CONSTRUCTOR` or `DESTRUCTOR` by its declarator |
| `function_definition` with a qualified declarator, `Type::name` | named `Type::name` and placed where it is written (R12) |
| `alias_declaration` | `TYPE_ALIAS` |
| `template_declaration` | the kind of the declaration it wraps, whose range it takes; the template parameters are part of its own tokens |
| `access_specifier` | not a declaration; part of the class's own tokens |

A C or C++ signature is the parameter types with names removed, normalized as Java's are ([technical specification §6.6](../../TECHNICAL-SPECIFICATION.md#66-signatures)); `(void)` is `()`. `.h` files use the C++ table (R3): the C++ grammar accepts nearly all C headers, and a C-only header that fails to parse becomes unanalyzed source (R20) rather than a wrong declaration.

New `DeclarationKind` values: `PROTOCOL`, `ACTOR`, `DESTRUCTOR`, `SUBSCRIPT`, `NAMESPACE`, `DELEGATE`, `OPERATOR`, `INDEXER` and `EVENT`, with labels in lower case. T1 changes [kind changes](../declarations/04-kind-changes.md) so that class, struct, actor and record (Swift and C#) are one family, and protocol and interface another; that spec changes first, in T1.

### 6. The tree builder

`TreeSitterTreeBuilder.build(tree, text, language, cancellation)`:

1. Walk the syntax tree from the root, keeping the enclosing declaration. A table node becomes a `Declaration` child of the enclosing one; a transparent node is walked with the same parent; nodes inside a body are never declarations (R13).
2. A declaration's `range` starts at the first attribute (`attribute`, `attribute_list`, `modifiers` holding attributes) and at the doc comment directly above it with no blank line between (`///`, `/** */`, and `///` XML comments in C#) (R15), and ends at the node's end, including a trailing `;`.
3. Tokens are the tree's leaves in order. A `comment` leaf is `COMMENT`; the gaps between leaves are `WHITESPACE`; a string literal is one `OTHER` token, so whitespace inside it is content; every other leaf is `OTHER`. Each token keeps its start offset in `ownTokenStarts`, so extraction and inlining detection work (R21).
4. `ownTokens` are the tokens in `range` minus the children's ranges, as PSI providers build them; `leadingWhitespace` is the whitespace before `range` up to the previous line break, as in the Java provider.
5. `body` is the node in the language table's body field: a `compound_statement`, a `function_body` or a Swift `computed_property`.
6. Everything outside top-level declarations, such as `#include`, `using` directives and `import` declarations, is the file region.
7. `ERROR` and `MISSING` nodes set `hasParseError` on the smallest enclosing declaration, or on the file region (R20); the declaration engine then reports them as unanalyzed as for PSI parse errors ([technical specification §6.7](../../TECHNICAL-SPECIFICATION.md#67-parse-errors)).
8. Call `cancellation.checkCanceled()` every 1,000 nodes.

### 7. Preprocessor directives

In C and C++, the line break at the end of a `preproc_*` directive, and a backslash line continuation inside one, are `OTHER` tokens instead of whitespace (R18), because they end or extend the directive. Other whitespace inside a directive stays `WHITESPACE`, which `INSIGNIFICANT` ignores except between two tokens that it separates. Directive lines inside a declaration's range are its own tokens; those outside are the file region or their transparent conditional's container.

Two declarations with the same name and signature in the two branches of an `#if … #else` are both kept, as two declarations with a repeated key; the matcher pairs them by order, as it does other repeated keys ([declarations/01](../declarations/01-declaration-matching.md)).

### 8. Loading, failure and isolation

- The runtime and grammars load once per IDE session, on the first comparison, in a background thread. A load failure, such as an unsupported OS or architecture, is recorded through the [API guard](../platform/01-platform-api-stability.md) as a new `GuardedFeature.TREE_SITTER`: the engine then supports no language, `EngineLanguage.of` returns the IntelliJ id, and the log has one warning (R23).
- A runtime or grammar exception during a comparison becomes `FAILED` through `compareSafely`, and the outline shows `Analysis failed` (R24). If ADR 0016 picks a runtime that can crash the process, it must run in a child process (option D), so R24 holds for every option.
- The host's size check and time limit apply as for every engine (R25).
- Parsing never reads files, the network or the IDE's project; the grammars are resources in the plugin archive (R26), and no source leaves the IDE process (engine contract R10). A child process, if used, gets the text over a pipe and has no network access of its own.

### 9. Packaging and licences

The grammars are pinned in `gradle/libs.versions.toml`, built or fetched at build time, and packaged as resources. `THIRD-PARTY-NOTICES.md` at the repository root, copied into the plugin archive, lists the runtime and each grammar with its version, its upstream URL and its licence text (R27). A Gradle check fails the build when a grammar or runtime has no notice entry, and when the plugin archive is more than 25 MB larger than the size recorded in `gradle.properties` before T1 (R28).

### 10. The setting

`SmartDiffSettings.treeSitterLanguages: Boolean = true`, shown on the settings page as "Compare languages the IDE cannot parse with Tree-sitter" ([settings/01](../settings/01-settings-page.md) R25). Applying a change refreshes open outlines through the existing settings listener (R31), which resolves the language again, so an open Swift diff switches between the Tree-sitter engine and the line comparison.

## Tests

Each test names its requirement, for example `// engines/05-tree-sitter R14`.

- `EngineLanguageTest` (unit): each extension of R3 resolves to its id from plain text, the TextMate language and no language, ignoring case (R2, R3); `JAVA` and any other IntelliJ id stays unchanged (R4); with the setting off nothing changes (R1).
- `TreeSitterTreeBuilderTest`, one class per language: each declaration kind of R8 to R11 with its name, range and signature; a qualified C++ method (R12); locals and lambdas in a body are not declarations (R13); declarations under `#ifdef` and `#else` (R14); attributes and doc comments in the range (R15); Swift labels in signatures (R16); an `ERROR` node marks the smallest declaration (R20); every character of the file is in a declaration or the file region.
- Golden fixtures under `src/test/testData/treesitter/<language>/`: per language, changed, added, removed, moved, formatting-only, renamed and extracted declarations with their expected outline text, including a Swift line-break case (R19), a C directive line-break case (R17, R18) and the `· tree-sitter` summary note (R7, R21).
- `TreeSitterEngineContractTest`: the shared engine contract suite with the four languages, including nothing hidden, cancellation and identical texts (R6, R25).
- `TreeSitterOutlineTest` (platform test): a `.swift` file opened as plain text gets the outline from this engine and not the line comparison (R2, R5); the Changes tree shows a summary for a changed `.cpp` file, and a three-sided diff and the merge window show the merge outline (R6); a file over the size limit shows `Too large to analyze` (R25); changing the setting and applying it switches an open diff to the line comparison and back (R31).
- `TreeSitterFailureTest`: a runtime that fails to load leaves the engine supporting nothing, falls back as with the setting off, and logs one warning (R23); a grammar call that throws gives `Analysis failed` (R24).
- `TreeSitterPackagingCheck` (Gradle task in `check`): every grammar and the runtime have a notice entry (R27), the archive growth is at most 25 MB (R28), and the engine version names each grammar version (R29).
- The CI workflow runs `TreeSitterEngineContractTest` and the golden fixtures on Linux, macOS and Windows runners, on x64 and, where the runner exists, arm64 (R22). If ADR 0016 picks a platform-neutral runtime, Linux x64 and macOS arm64 are enough.
- Merge fixtures and relationship benchmark fixtures for each language, added to the existing suites (R30).

## Open questions

- **Default for `.h`.** C++ (the default above), or C when the same change has `.c` files and no C++ files. The second needs the Changes tree's other files and stays out of scope until a corpus run shows C headers failing.
- **The setting's default.** On, as here, if ADR 0016 picks a runtime that cannot crash the IDE; off by default until the corpus validation of T3 if it can.
