# Structure-view fallback: details

Status: implemented, 2026-11-13

**Feature:** [Structure-view fallback](06-structure-view-fallback.md)

## Design

### Selection

`StructureViewDeclarationProvider` is registered on the main `io.github.agadek.smartdiff.declarationProvider` extension point with `order="last"`, so every dedicated provider registered in an optional descriptor comes first. `DeclarationEngine.provider(languageId)` takes the first provider whose `supports` returns true, which keeps a dedicated provider winning for its language (R2).

`supports(languageId)` returns true only when: the language exists, the setting *Use the IDE's structure view for other languages* is on, no other declaration provider supports the language, and the language has a `TreeBasedStructureViewBuilder`. The last check makes dedicated precedence independent of extension order, so language-provider tests and `DeclarationEngine` agree on exactly one provider per language.

The setting is exposed through `io.github.agadek.smartdiff.engine.api.StructureViewFallbackSetting`, a small engine API interface whose default is on. `SmartDiffStructureViewFallbackSetting` (UI) implements it from `SmartDiffSettings.useStructureViewFallback` and is registered as an application service in `plugin.xml` under the interface. The engine reads the service without importing `ui`, so `engine.declarations.psi` has no reflective or compile-time dependency on the UI (layering choice). `ComparisonRequest.useStructureViewFallback` remains as the per-request value the UI, Changes tree and merge outline already carry; `DeclarationEngine.supports(languageId)` and `compare` agree because both ask the provider, which checks the service, and `compare` additionally rejects the fallback when the request flag is off.

`DeclarationProvider.analysisNote` marks the fallback provider (`AnalysisNote.STRUCTURE_VIEW`), so the engine selects and tags results by that property instead of a class-name string. `ProviderTestCase` uses the same property to find a language's dedicated provider.

The request's language id reaches the provider explicitly: `DeclarationProvider` has default `dialect`/`parse` overloads that take `languageId`, and the engine always calls those. The fallback overrides them; dedicated providers keep their three-/four-argument implementations. No provider keeps per-request state between calls.

### Parsing

`parse` creates a PSI file with `PsiFileFactory.getInstance(project).createFileFromText(fileName, language, text, false, false)` (not physical, not event-system). When the request has a file name, it is used so the language's structure view can pick the right builder; otherwise a name derived from the language's default extension is used.

### Walking the model

`build` obtains a `TreeBasedStructureViewBuilder`, creates a `StructureViewModel` with a null editor (`createStructureViewModel(null)`), and walks `model.root.children` recursively. Sorters, filters and groupers are ignored. The model is disposed after the walk.

For each element the provider keeps items whose `value` is a `PsiElement` whose containing file is the parsed file and whose text range is non-empty and within the file. Same file means identity of the file, its `originalFile`, or its view provider: text-built files have no virtual file, so comparing virtual files would let in elements of any other non-physical file. Items whose element comes from another file (inherited or grouped) are dropped (R3). Two kept items with the same range become one declaration (R4), keeping the first.

### Names, kinds and signatures

The name is the item's presentable text (`StructureViewTreeElement.presentation.presentableText`), trimmed (R4). Declarations have no signature (R5).

Kinds map from the PSI element's class simple name (R6): contains `Interface` → `INTERFACE`, `Class` → `CLASS`, `Function` → `FUNCTION`, `Method` → `METHOD`, `Field` → `FIELD`, `Property` → `PROPERTY`; everything else → `ITEM`. The check is in that order so an interface class name matches `INTERFACE`. `DeclarationKind.ITEM` is rendered with no kind word in the outline and Changes tree.

### Tokens and whitespace

Own tokens come from the language's lexer through `LexedTokens`, as for other PSI providers: `LanguageParserDefinitions.INSTANCE.forLanguage(file.language).createLexer(file.project)` over the whole text, sliced by each declaration's range minus its children's extents. Leading-whitespace assignment and parse-error marking come from the shared `PsiTreeParts` used by `PsiDeclarationProvider`, so both provider kinds attribute them the same way.

The layout run before an item includes whitespace tokens and tokens whose text is all whitespace (YAML's `Eol`), up to the previous sibling. Only the indentation after the run's last line break is compared; the line break itself is layout. The run after an item (its separator and line break) is attached as `Declaration.layout`: it is covered by the change details but not compared. The analysis lists `layout` for every node and, under `SIGNIFICANT`, also lists a matched item's leading run, so a pure swap of two equal keys still satisfies nothing-hidden even though the LCS reports only one of them as moved.

The whitespace policy is `SIGNIFICANT`, so the fallback never reports formatting-only (R7). A pure reorder of unchanged keys shows the moved key, not `changed`; a real reformat (`a:    1`, or compact JSON against pretty JSON) is `CHANGED`, never formatting-only. `OutlineController.whitespacePolicy` already returns `SIGNIFICANT` for unknown language ids.

### Failure

A structure view that throws, returns no builder, needs an editor, or cannot be walked makes `build`/`compare` fail with a `FAILED` result and the native diff (R9, P5). The walk does not catch exceptions: cancellation always propagates, and any other exception reaches `DeclarationEngine`, which reports `FAILED` instead of a shorter outline. A file over the declaration limit is `TOO_LARGE`, as for every engine.

Parse errors are not failures: `PsiTreeParts.markParseErrors` gives each `PsiErrorElement` to the innermost declaration that contains it, or to the file region when no declaration does. The engine then reports `UNANALYZED` content and `PARTIAL` as usual (§6.7).

### Summary note

Results produced by the structure-view fallback carry `AnalysisNote.STRUCTURE_VIEW`. The outline's summary line ends with `· structure view` (R8), and the outline tooltip/empty-state text says the outline comes from the IDE's structure view. `ITEM` has no kind word in outline titles, matcher paths or the golden harness.

## Tests

Each test names its requirement, for example `// languages/06-structure-view-fallback R3`.

- `StructureViewSpikeTest` (platform test, S9): on text-built PSI files (no editor) asserts the exact kinds, names, nesting, ranges and file region for JSON, YAML, Markdown, properties and Groovy, and fails if a language or structure-view builder is missing. `StructureViewCases` holds the recorded values.
- `StructureViewDeclarationProviderTest`: the same exact trees through the provider; a dedicated provider (Java) wins (R2); the setting is on by default and off makes such files unsupported (R1, R10); a language without a structure view (plain text) stays unsupported (R12); a pure reorder is moved/changed-free and a reformat is changed (R7); the note is present only for fallback results (R8); a throwing structure view is `FAILED` and a file over the declaration limit is `TOO_LARGE` (R9).
- `OutlineModelTest` checks the `ITEM` title and the summary/empty-state note (R6, R8); `ChangesSummaryTest` and `StructureViewMergeTest` check a fallback file's Changes-tree summary and both merge sides (R11).
- Golden fixtures under `src/test/testData/structure-view/<lang>/<case>/` with `GoldenFixturesTest`: key/section added, removed, changed, reordered object keys/lines (each moves a declaration), a reformat that is not formatting-only, parse error on one side, identical files. `json/reordered` and `yaml/reordered` are pure key swaps, not array-element changes.
- Existing tests that relied on files being unsupported keep their intent by using plain text or another language with no structure view, or by turning the setting off, and their assertions are not weakened.

## S9 findings

- Text-built PSI files with no editor give exact names, nesting and ranges from `StructureViewModel` (`createStructureViewModel(null)`). The bundled plugin ids used by the tests are `com.intellij.modules.json`, `org.jetbrains.plugins.yaml`, `org.intellij.plugins.markdown`, `com.intellij.properties` and `org.intellij.groovy`.
- What each structure view actually gives (recorded in `StructureViewCases`):
  - JSON `{"a": 1, "b": {"c": 2}}`: `PROPERTY a [1, 7)`, `PROPERTY b [9, 22)` with `PROPERTY c [15, 21)`; key names have no quotes; file region `[0, 1)`, `[22, 23)`.
  - YAML `root:\n  child: value\n  other: 1\n`: `ITEM YAML document [0, 31)` containing `ITEM root [0, 31)` containing `ITEM child [8, 20)` and `ITEM other [23, 31)`; file region `[31, 32)`. The document item spans the whole file, and YAML's line breaks are `Eol` tokens, not whitespace.
  - Markdown `# Title\n\nText\n\n## Section\n\nMore\n`: `ITEM Title [0, 7)` containing `ITEM Section [15, 25)`; a heading's range is the heading line only, and the text belongs to the file region `[7, 32)`.
  - Properties `foo.bar=1\nbaz=2\n`: `PROPERTY foo.bar [0, 9)`, `PROPERTY baz [10, 15)`; file region `[15, 16)`.
  - Groovy `class A {\n  void foo() {}\n  int bar\n}`: `CLASS A [0, 37)` with `FIELD bar: int [32, 35)` and `METHOD foo(): void [12, 25)`; file region `[37, 38)`. The field name includes its type, and the structure view lists fields before methods.
- A structure view may touch project indexes: Groovy's failed with `FileBasedIndex should not receive default project` when the comparison ran against the default project. Passing the real fixture project (as the host does) fixed it, so `DeclarationEngine` keeps using its project for `parse`/`build`.
- Parse errors in the fixture inputs did not make the structure views throw; they were marked on the innermost declaration (JSON, Groovy) or the file region, and the engine reported `PARTIAL`. When a structure view does throw (a builder that needs an editor), the request is `FAILED`. R9's `FAILED` applies to throws/editor needs/limits, not to every syntactically broken input.
- Manual checks for TypeScript, CSS and Go in WebStorm and GoLand were not run in this environment; the platform tests and golden fixtures cover the current target.

## Open questions

- **F2 (decided):** the setting is on by default, with the summary-line note, since the alternative is the plain native diff and the provider never claims formatting-only.
