# Tree-sitter engine: details

Status: proposed, 2026-11-17

**Feature:** [Tree-sitter engine](05-tree-sitter.md)

## Design

### 1. Phases

| Phase | Work | Done when |
| --- | --- | --- |
| T0 | Spike S12 (§2) and ADR 0016 for the parser runtime | Done 2026-11-17: S12's questions 1 and 2 are answered, question 3 is a recorded manual check, and [ADR 0016](../../adr/0016-tree-sitter-runs-in-a-native-helper-process.md) is accepted |
| T1a | The helper build for six platforms (§9), the helper client and manager (§8), notices and size checks | Done 2026-11-18: `TreeSitterHelperTest` and the checks pass, and CI builds and starts the helper on every platform it has a runner for |
| T1b | The language resolver of [ADR 0015](../../adr/0015-tree-sitter-languages-are-chosen-by-file-name.md), the setting, the engine, the tree builder and Swift (§3 to §7, §10) | Done 2026-11-18 for Swift: `SwiftTreeTest`, the Swift golden fixtures, `TreeSitterEngineContractTest`, `EngineLanguageTest` and `TreeSitterOutlineTest` pass with the helper of this platform |
| T2 | C#, C and C++ | Done 2026-11-24: `CSharpTreeTest`, `CTreeTest`, `CppTreeTest`, their golden fixtures and the engine contract suite on the three languages pass, and the archive size check holds (R28) |
| T3 | Merge and benchmark fixtures, and a corpus validation | Done 2026-11-24: `TreeSitterMergeFixturesTest` and `TreeSitterRelationshipBenchmarkTest` pass (R30), the benchmark's `no-new-wrong-pairs` gate passes, and the corpus run is in [VALIDATION.md](../../VALIDATION.md#2026-11-24-tree-sitter-merge-fixtures-benchmark-cases-and-corpus-validation) |

T1b builds on T1a. T2's three languages are independent of each other.

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

**Result (2026-11-17).** [ADR 0016](../../adr/0016-tree-sitter-runs-in-a-native-helper-process.md) records the measurements and chooses option D: one native helper executable per platform with all four grammars, run as a child process. The JNI binding has no Windows arm64 build and goes over the time limit on 1 MB of Swift. The WebAssembly grammars run their lexer in Chicory's interpreter and are more than 10 times too slow. The Java binding needs Java 23. In the test IDE, every extension of R3 has a file type without a language (`C#`, `C++` or unknown), with or without the TextMate plugin, so the resolver of §3 starts from a missing language id; `TreeSitterS12SpikeTest` checks it. Question 3 was not run and stays a manual check.

### 3. Choosing the language

[ADR 0015](../../adr/0015-tree-sitter-languages-are-chosen-by-file-name.md) records the decision. A new object, `EngineLanguage` in `engine.registry`, has one function:

```kotlin
fun of(languageId: String?, fileName: String?): String?
```

It returns `languageId` unchanged unless that id is null, `PlainText.id` or the TextMate language's id and the file name's extension, compared ignoring case, is in the table of R3. Then it returns the Tree-sitter language id: `tree-sitter.swift`, `tree-sitter.c-sharp`, `tree-sitter.c` or `tree-sitter.cpp`. These ids are constants in `engine.api.LanguageIds` and are never an IntelliJ language id, so no PSI provider or other engine claims them (R4).

Every place that selects an engine calls `EngineLanguage.of` first and puts its result in `ComparisonRequest.languageId`: `DocumentContent.languageId()` and its three callers in `OutlineDiffExtension`, `MergeWindowSupport` and `ChangesSummary.request` (R6). The file name is the content's file, or for a revision whose file is not on disk the last path segment of the diff request's title. Engines still never read `fileName` (engine contract), because the resolved id carries the language.

The resolver does not read the setting. The setting turns the engine off instead: `TreeSitterEngine.supports` is false while "Compare languages the IDE cannot parse with Tree-sitter" is off, so no engine supports the resolved id and the outline uses the line comparison as for plain text ([engines/04](04-moved-lines.md) R2), or attaches no panel when that setting is off too (R1). With the setting on, the resolved id has a supporting engine, so the line comparison is not used for it ([engines/04](04-moved-lines.md) R4), which gives R5 without a change to `LineEngine`. Every open outline reads the setting on its next refresh, which the settings page triggers when applied (R31).

### 4. The engine

`TreeSitterEngine` in a new package `io.github.agadek.smartdiff.engine.treesitter`, with id `smartdiff.treesitter` and display name `Declarations (Tree-sitter)`, is registered on the `comparisonEngine` extension point after the declaration engine. It supports exactly the four Tree-sitter language ids. No other engine supports them, so the [engine dropdown](../../GLOSSARY.md#engine-dropdown) stays hidden, and the settings page's default engine table lists the four languages with this one engine.

`compare(request, cancellation)`:

1. Find the language's `TreeSitterLanguage` (§5); a missing one is `UNSUPPORTED`.
2. Parse both sides in the helper of [ADR 0016](../../adr/0016-tree-sitter-runs-in-a-native-helper-process.md), calling `cancellation.checkCanceled()` once before the names and once before each side, so every comparison checks it equally often (engine contract R2, R4). The wait for the helper's answer is not interrupted, since a 1 MB side takes well under a second; a cancellation at a check stops the helper, so the next comparison starts a new one. Comparisons share the one helper and wait for it in turn; if waiting makes the Changes tree too slow, T1 measures a pool of at most 2 helpers.
3. Build a `DeclarationTree` for each side with `TreeSitterTreeBuilder` (§6).
4. Compare them with the declaration engine's `DeclarationAnalysis.compare`, with the request's limits and confidence policy, and set `analysisNote = AnalysisNote.TREE_SITTER` (`"tree-sitter"`, a new constant), which gives R7.

Reusing `DeclarationAnalysis` is what gives R21: matching, classification, rename candidates, kind changes, moves, extraction and inlining all work on the `DeclarationTree`, not on PSI. The rename grouping's reference pass needs PSI, so Tree-sitter declarations get no usage evidence. The architecture rule that keeps engines independent allows `engine.treesitter` to use `engine.declarations.model` and `engine.declarations.analysis`, which depend on no IntelliJ API, and nothing else of the declaration engine.

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

A C# signature is the parameter types with their `ref`, `out`, `in`, `this` and `params` modifiers, which tell overloads apart, as `(ref int, params int[])`. A destructor is named `~Type`, an operator `operator +`, a conversion `implicit operator int` and an indexer `this`, with the indexer's parameter types as its signature. Accessor, block and expression bodies are opaque (R13).

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
| `friend_declaration` | not a declaration; part of the class's own tokens |

In a class body, a function without a return type whose name is the plain identifier is a `CONSTRUCTOR`, one named `~Type` a `DESTRUCTOR` and the others `METHOD`, whether written as a `function_definition`, a prototype `declaration` or a `field_declaration`. A qualified definition outside the class is in the namespace that holds it, so it is a `FUNCTION`, or a `CONSTRUCTOR` or `DESTRUCTOR` when its last part names its scope's type, as `Cart::Cart` and `Cart::~Cart` (R12). An operator is named without spaces after `operator`, as `operator=`, and a conversion as `operator bool`. A `struct`, `union`, `enum` or `class` ends after the `;` that follows it, and one without a name but with a body is named `(anonymous)`. A `typedef` with a struct, union or enum body holds that body's members. A function pointer variable such as `int (*f)(int)` is a `VARIABLE`, not a function.

A C or C++ signature is the parameter types with names and default values removed, written without spaces around punctuation, as `(const std::vector<int>&, int)`, and normalized as Java's are ([technical specification §6.6](../../TECHNICAL-SPECIFICATION.md#66-signatures)); `(void)` is `()`. `.h` files use the C++ table (R3): the C++ grammar accepts nearly all C headers, and a C-only header that fails to parse becomes unanalyzed source (R20) rather than a wrong declaration.

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

In C and C++, the line break at the end of a `preproc_*` directive, and a backslash line continuation inside one, are `OTHER` tokens instead of whitespace (R18), because they end or extend the directive. Other whitespace inside a directive stays `WHITESPACE`, which `INSIGNIFICANT` ignores except between two tokens that it separates. The grammar gives a macro's body as one `preproc_arg` leaf, so the tokens split it into runs of whitespace and runs of other characters: respacing a macro's body is formatting-only, and continuing it on a new line is not. Directive lines inside a declaration's range are its own tokens; those outside are the file region or their transparent conditional's container.

Two declarations with the same name and signature in the two branches of an `#if … #else` are both kept, as two declarations with a repeated key; the matcher pairs them by order, as it does other repeated keys ([declarations/01](../declarations/01-declaration-matching.md)).

### 8. Loading, failure and isolation

- The helper is copied to the IDE's system directory and started once per IDE session, on the first comparison, in a background thread. A start failure, such as an unsupported OS or architecture or a missing executable, is recorded through the [API guard](../platform/01-platform-api-stability.md) as a new `GuardedFeature.TREE_SITTER`: the engine then supports no language, `EngineLanguage.of` returns the IntelliJ id, and the log has one warning (R23).
- A helper that exits, closes its output or sends a malformed answer during a comparison makes that comparison `FAILED` through `compareSafely`, and the outline shows `Analysis failed` (R24); the next comparison starts a new helper. A helper that fails 3 times in a row in one session is treated as a start failure.
- The host's size check and time limit apply as for every engine (R25).
- The helper reads only its standard input and writes only its standard output: it opens no file and no network connection. The source goes to it over a pipe and never leaves the computer ([engine contract](02-engine-contract.md) R13).

### 9. Packaging and licences

The runtime and grammar sources are pinned by version and checksum in the build, fetched at build time, and compiled into `smartdiff-treesitter` with a C compiler, with the symbol and field name table generated next to it. The CI workflow builds the helper on native runners for the six platforms of R22 and the release job packages all six under `treesitter/<os>-<arch>/` in the plugin archive; a local build packages only the host's helper. `THIRD-PARTY-NOTICES.md` at the repository root, copied into the plugin archive, lists the runtime and each grammar with its version, its upstream URL and its licence text (R27). A Gradle check fails the build when a grammar or runtime has no notice entry, and when the plugin archive is more than 25 MB larger than the size recorded in `gradle.properties` before T1 (R28).

### 10. The setting

`SmartDiffSettings.treeSitterLanguages: Boolean = true`, shown on the settings page as "Compare languages the IDE cannot parse with Tree-sitter" ([settings/01](../settings/01-settings-page.md) R25). Applying a change refreshes open outlines through the existing settings listener (R31), which resolves the language again, so an open Swift diff switches between the Tree-sitter engine and the line comparison.

## Tests

Each test names its requirement, for example `// engines/05-tree-sitter R14`.

- `EngineLanguageTest` (unit): each extension of R3 resolves to its id from plain text, the TextMate language and no language, ignoring case (R2, R3); `JAVA` and any other IntelliJ id stays unchanged (R4); with the setting off nothing changes (R1).
- `SwiftTreeTest`, and one such class per language of T2: each declaration kind of R8 to R11 with its name, range and signature; a qualified C++ method (R12); locals and lambdas in a body are not declarations (R13); declarations under `#ifdef` and `#else` (R14); attributes and doc comments in the range (R15); Swift labels in signatures (R16); an `ERROR` node marks the smallest declaration (R20); every character of the file is in a declaration or the file region.
- Golden fixtures under `src/test/testData/treesitter/<language>/`: per language, changed, added, removed, moved, formatting-only, renamed and extracted declarations with their expected outline text, including a Swift line-break case (R19), a C directive line-break case (R17, R18) and the `· tree-sitter` summary note (R7, R21).
- `TreeSitterEngineContractTest`: the shared engine contract suite with the four languages, including nothing hidden, cancellation and identical texts (R6, R25).
- `TreeSitterOutlineTest` (platform test): a `.swift` file opened as plain text gets the outline from this engine and not the line comparison (R2, R5); the Changes tree shows a summary for a changed `.cpp` file, and a three-sided diff and the merge window show the merge outline (R6); a file over the size limit shows `Too large to analyze` (R25); changing the setting and applying it switches an open diff to the line comparison and back (R31).
- `TreeSitterHelperTest` (unit test, with the helper the build made): each grammar's names and fields, a Swift parse with offsets in the text's `char` units, C#, C and C++ samples without errors, a parse error marked in the tree (R20), a killed helper failing one comparison and starting again (R24), a cancellation that stops the helper without counting as a failure (R25), and a missing helper or one that keeps answering garbage turning Tree-sitter off after 3 failures in a row (R23, R24). T1b adds the engine-level fallback: a disabled helper makes Tree-sitter languages behave as with the setting off and logs one warning.
- Gradle checks in `check`: `checkTreeSitterNotices`, every pinned source has its `THIRD-PARTY-NOTICES.md` entry with the same version (R27); `checkTreeSitterArchiveSize`, the archive is at most 25 MB larger than `treeSitterArchiveBaselineBytes` (R28). The engine version test that names each grammar version (R29) comes with the engine in T1b.
- The `treesitter-helper.yml` workflow, called by Build and Release, builds the helper on Linux, macOS and Windows runners on x64 and arm64 and runs `smokeTestTreeSitterHelper` there (the Intel Mac helper is cross-built on the Apple silicon runner and not started); Build and Release package all six (R22, R26). The engine contract suite and golden fixtures run on the Linux build job with that platform's helper.
- `TreeSitterMergeFixturesTest` (platform test): the nine merge cases of merge/05 R11 for each language under `src/test/testData/treesitter/<language>/merge/`, with nothing hidden on either side (R6, R30).
- `TreeSitterRelationshipBenchmarkTest` (platform test): the cases in `relationships-corpus-treesitter.txt`, a method rename, a class rename, a rename with a body change, a kind change, a move, an extraction and an inlining per language, run through the benchmark with this engine; every labelled relationship is found and the `no-new-wrong-pairs` gate passes (R21, R30).
- `TreeSitterCorpusValidationTest`: real commits from `-Psmartdiff.validation.treesitter.corpus=<list>`, skipped without it, flagging fallbacks, hidden or empty results, same-name pairs, unanalyzed ranges, noisy and slow files (R30).

## Open questions

- **Default for `.h`.** C++ (the default above), or C when the same change has `.c` files and no C++ files. The second needs the Changes tree's other files and stays out of scope until a corpus run shows C headers failing.
- **The setting's default (decided 2026-11-24).** On. ADR 0016 runs the parser in a child process, and the T3 corpus validation found no hidden change and no failure outside one comparison.
