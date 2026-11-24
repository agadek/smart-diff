# Go: details

Status: implemented, 2026-11-24

**Feature:** [Go](17-go.md)

## 1. Packaging and IDE versions

- **Plugin:** the Go PSI comes from the JetBrains Go plugin, `org.jetbrains.plugins.go` (Marketplace plugin 9568). GoLand bundles it; IntelliJ IDEA Ultimate installs it from the Marketplace. IntelliJ IDEA Community, the main build target, cannot use it, since the plugin requires the `com.intellij.modules.ultimate` module.
- **Marketplace facts (spike S13, 2026-11-24):** plugin 9568 is `FREE` (`pricingModel`). The newest update for IntelliJ IDEA Ultimate 2025.1.3 (`IU-251.26927.53`) is `251.26927.53` (update 780515, since 251.26927, until 251.*); the next one, `251.27812.12`, needs a newer build. GoLand 2025.1.3 is `GO-251.26927.50` and bundles its own copy.
- **Plugin dependencies:** the plugin's `META-INF/plugin.xml` requires the modules `com.intellij.modules.lang`, `com.intellij.modules.ultimate`, `com.intellij.modules.go-capable` and `com.intellij.modules.json` and the platform module `intellij.platform.vcs.impl`; it has no `<depends>` entries. Its 16 content modules (terminal, shell, Docker and others) only add integrations with other plugins, and the test sandbox loads the plugin without them.
- **Descriptor:** the provider registers in `META-INF/smartdiff-go.xml`, loaded through `<depends optional="true" config-file="smartdiff-go.xml">org.jetbrains.plugins.go</depends>` (R1).
- **Closed source:** the Go plugin's sources are not published. S13 confirmed the PSI class names of §4 on `251.26927.53`; the verifier report shows which of them it treats as internal or experimental API, and the provider keeps every Go plugin call in one class, `GoInterop`, as `RustInterop` does.
- **Language id:** `go` in lower case (S13: `Language.findLanguageByID("go")` is `com.goide.GoLanguage`; `"Go"` finds nothing), with `GoFileType` for `.go` (R2).
- **IDE versions:** no change to P6. Go support needs a Go plugin release for that IDE, which in GoLand is always the bundled one.

## 2. Build target

Go follows the paid-IDE build pattern of [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md) (its amendment for phase B0 names one IntelliJ IDEA Ultimate subproject per language): a Gradle subproject `go/` (`include("go")`) targets **IntelliJ IDEA Ultimate 2025.1.3** with the pinned Marketplace plugin **`org.jetbrains.plugins.go:251.26927.53`**, and the main build stays on Community.

- The subproject applies `org.jetbrains.intellij.platform.module`, the Kotlin JVM plugin, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4, like `rust/`.
- It adds `com.intellij.modules.json` as `bundledPlugin` and `testBundledPlugin`, since the Go plugin requires it and the test platform does not add it by itself. S13 needed no other plugin and no test-only stand-in class, unlike Rust.
- As for Rust, the root packages it with `pluginModule(runtimeOnly(project(":go")))`, and `:go` adds `compileOnly(project(":"))` for its main source set and `testImplementation(project(":"))` and `testImplementation(testFixtures(project(":")))` for tests, so there is no task cycle.
- `./gradlew buildPlugin check` runs `:go:test`; the test task copies `S13 …`, `GOLDEN …` and `GO VALIDATION …` lines into the build log, and forwards `-Psmartdiff.updateGolden` and `-Psmartdiff.validation.go.corpus`.

## 3. Plugin Verifier

- `verifyPlugin` gains the `goland` family: GoLand 2025.1.3 (`IntelliJPlatformType.GoLand`) and the latest GoLand release. It is in the default set and is one more job of the `verify` matrix in `.github/workflows/build.yml`.
- S13 (2026-11-24) ran `verifyPlugin -Psmartdiff.verifier.ides=goland`: GoLand 2025.1.3 and GoLand 2026.3 resolved, and both were `Compatible`, with 49 experimental-API usages and only optional dependencies unresolved, before the provider existed. The run with the provider (2026-11-24) resolved GoLand `GO-251.26927.50` and `GO-263.7307.211`, both `Compatible` with the same 49 experimental-API usages, none of them in the Go provider: the verifier reports no internal or experimental `com.goide` use ([VALIDATION.md](../../VALIDATION.md)).
- On IntelliJ IDEA Community the optional dependency stays unresolved, which the verifier reports as a missing optional dependency, not a failure.
- Every Go PSI use the verifier reports as internal or experimental is listed in [PLATFORM-API.md](../../PLATFORM-API.md) and reviewed there. A `NoClassDefFoundError` or `NoSuchMethodError` from the Go plugin turns the provider off through `ApiGuard` (`GuardedFeature.GO_PROVIDER`) and shows the native diff, never an exception in the diff window ([product scope](../README.md#product-scope) P5).

## 4. Declarations

S13 confirmed these classes, all in `com.goide.psi` (interfaces) with `Impl` classes in `com.goide.psi.impl`, and that every element's `text` equals the request text sliced by its range. A text-built file is a `com.goide.psi.GoFile` and parsed the sample, generics included, with no `PsiErrorElement`.

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `GoFunctionDeclaration` | FUNCTION | `name` | parameter types (§5) |
| `GoMethodDeclaration` | METHOD | `name`, with the receiver type as `receiver` (§6) | receiver type, `.`, parameter types (§5) |
| `GoTypeSpec` with a `GoStructType` | STRUCT | `name` | — |
| `GoTypeSpec` with a `GoInterfaceType` | INTERFACE | `name` | — |
| `GoTypeSpec` written with `=` | TYPE_ALIAS | `name` | — |
| any other `GoTypeSpec` | TYPE | `name` | — |
| `GoFieldDeclaration` in a struct | FIELD | its `GoFieldDefinition` names joined with `, `; for an embedded field, its `GoAnonymousFieldDefinition` name | — |
| `GoMethodSpec` with an identifier, in an interface | METHOD | `name` | parameter types |
| `GoConstSpec` at top level | CONSTANT | its `GoConstDefinition` names joined with `, ` | — |
| `GoVarSpec` at top level | VARIABLE | its `GoVarDefinition` names joined with `, ` | — |

- **New kind:** `TYPE`, shown as `type`, for a defined type such as `type ID int64`. It is not an alias (`type A = B`), which keeps `TYPE_ALIAS`, and no other kind reads right. It is added on its own line at the end of `DeclarationKind`. The matcher's type kinds for rule M4b gain `TYPE`, so a defined type turned into a struct of the same name is a kind change (R15).
- **Containers:** structs (fields) and interfaces (methods). The struct or interface is reached through `GoTypeSpec.getSpecType().getType()`. A type whose underlying type is a pointer, slice or map of a struct has no members.
- **Groups (R7):** `type (…)`, `const (…)` and `var (…)` are one `GoTypeDeclaration`, `GoConstDeclaration` or `GoVarDeclaration` holding several specs. Each spec is a top-level declaration; the keyword, the parentheses and anything between the specs belong to the file region. A single `type Point struct {…}` is one declaration with one spec; its range is the whole `GoTypeDeclaration`, so the `type` keyword is the declaration's own token. The same holds for single `const` and `var` declarations.
- **Several names (R8):** `X, Y int`, `const a, b = 1, 2` and `var x, y = f()` are one declaration named `X, Y`, as Python names a tuple target. Renaming one of the names renames the declaration: a constant or variable with a value becomes a rename candidate, while a field, which has no value, shows as removed and added, as any field without a value does ([relationships/03](../relationships/03-relationship-matching.md) R21). A struct tag is part of its field.
- **Embedded fields (R6):** `fmt.Stringer` or `*Base` in a struct is a field named by its Go field name, `Stringer` or `Base`. In an interface, an embedded interface or a type set term (`fmt.Stringer`, `~int | ~string`) is a `GoMethodSpec` with no identifier, or a constraint element; it is not a declaration and belongs to the interface's own tokens, so adding one changes the interface.
- **Not declarations (R9):** the provider never looks inside a function or method body, so local `type`, `const` and `var` declarations, short variable declarations and `GoFunctionLit` are content of their function. The package clause and the import list belong to the file region. A spec whose names are all `_` (`var _ io.Reader = (*T)(nil)`) and a function named `_` are content of the file region; a spec with another name next to `_` (`a, _ = f()`) is a declaration named `a, _`.
- **Repeated keys (R12):** several `func init()` in one file share a key and follow the matcher's rules for repeated keys, as Rust's `#[cfg]` twins do.

## 5. Signatures

- A function's signature is each parameter's type, in order, normalized as in technical specification §6.6: no whitespace or comments and no parameter names (R11). `dx, dy int` is one `GoParameterDeclaration` with two `GoParamDefinition`s, so its type is written once per name: `func Move(dx, dy int)` → `int,int`. A parameter with a type and no name counts once.
- A variadic parameter keeps its `...`: `func Sum(xs ...T)` → `...T`.
- Type parameters (`[T int | float64]`) and results, named or not, are left out, as the other languages leave out generics and return types.
- A method's signature key is its receiver type without `*` and type parameters, `.`, then its parameter types: `func (p *Point) Move(dx, dy int)` → `Point.int,int`, `func (l List[T]) Len()` → `List.`. Two methods of one name on different types therefore never share a key, as Kotlin extensions do not. The display signature is the parameter types only, `int, int`, and the receiver goes in `receiver`, so the row reads `method Point.Move(int, int)`.
- Whether a receiver is a pointer is the method's own token, not its signature: turning `(p Point)` into `(p *Point)` changes the method and keeps it paired.
- Functions and methods have a signature even with no parameters (the empty one); interface methods have the same parameter signature as functions; other kinds have none.
- The display signature keeps one space between two words and none next to `[](),.*`, as for Rust: `func(int) error, ...string`.

## 6. Receivers

- `GoMethodDeclaration.getReceiverType()` is a `GoPointerType` for `*Point` or a `GoType` for `Point` and `List[T]`. The provider takes the type name under the pointer and drops the type arguments, so the receiver of `(l *List[K, V])` is `List`.
- S13 found that a generic receiver's `List[T]` holds a `GoTypeParamDefinition` for `T`; it is part of the receiver, never a declaration.
- A method whose receiver has no type name (a parse error) is named by its own name, with an empty receiver.

## 7. Ranges and comments

- S13 showed that a doc comment is a sibling before the declaration under `GoFile`, not inside its range: `// Doc for Point.` ends before the `GoTypeDeclaration` starts.
- The provider therefore extends a declaration's range back over the comments directly above it (R10): the run of `//` and `/* */` comments that ends on the line before the declaration, with no blank line between them and the declaration and none inside the run. A comment after a blank line, a comment on the same line as the previous declaration, and a build constraint (`//go:build`) at the top of the file stay in the file region.
- The same rule applies inside a group (`const (…)`) and inside struct and interface bodies, to the comments above a spec, field or method. A trailing comment on the same line after a field (`X int // pixels`) is the field's own token.
- The range is returned through `DeclarationInfo.range`, so the outline frames the comment with its declaration and adding a doc comment changes the declaration.

## 8. Whitespace policy

- Go inserts a semicolon at a line break after an identifier, a literal, `)`, `]`, `}`, `++`, `--`, `return`, `break`, `continue` and `fallthrough`, so a line break can change how the source parses, while spaces and indentation cannot. Go trees use `LINE_BREAKS_SIGNIFICANT`, the policy Kotlin started with: whether there is a line break between two tokens matters, its amount and the spaces do not (R13, R14).
- This keeps the most common Go formatting change formatting-only: `gofmt` re-aligning the columns of a struct's fields or a `const` group when a longer name is added. Moving a brace onto its own line, or joining two lines, is a change, which is strict but never hides one.
- Raw strings (`` `…` ``) and struct tags are single tokens, so whitespace inside them is compared as written.
- `OutlineController.whitespacePolicy` maps `go` to `LINE_BREAKS_SIGNIFICANT`.

## 9. Parsing and parse errors

- `PsiFileFactory.createFileFromText("file.go", GoLanguage, text, false, false)`, inside the engine's read action. A text-built file has no module or SDK; S13 found that the parser accepts generics, type sets and `~` terms on it with no `PsiErrorElement`.
- Offsets are checked on LF and on CRLF content, as for the other languages.
- Parse errors follow technical specification §6.7. An empty side, as of a new or deleted file, has no package clause; that missing clause is no parse error, so the file is `ANALYZED` with every declaration added or removed. Any error in a file with code is one.

## 10. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates, kind changes and moves inside declarations (R15):** they work on the declaration model. For [moves inside declarations](../relationships/08-moves-inside-declarations.md) the provider marks `GoBlock` as a statement block (`isStatementBlock`), so the statements of a function body, and of the blocks nested in it, can be moved.
- **Rename grouping:** out of scope. S13 showed that in a text-built file, without a Go SDK or module, the reference `Pi` in `fmt.Println(Pi)` resolves to its `GoConstDefinition` and `fmt` to its `GoImportSpec`, so a `GoReferenceResolver` can follow as its own change; until it does, Go renames show no usage count.
- **Merges and Changes tree (R16):** both use the engine's result and need no Go code.
- **Benchmark:** the relationship benchmark gains the `go` corpus, with cases in `src/test/testData/go/relationships/` listed in `src/test/testData/relationships-corpus-go.txt`, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md), and run by `GoRelationshipBenchmarkTest` from `:go:test`, under the `no-new-wrong-pairs` gate.

## 11. Tests

- **`GoS13SpikeTest`** (`go/src/test/kotlin/.../psi/go/`): the spike that answered §1, §4, §6, §7 and §10, printing `S13 …` lines. It stays until the provider tests below cover the same ground.
- **`GoDeclarationProviderTest`** (`go/src/test/kotlin/.../psi/go/`): tests named after R1 to R14, each naming its requirement with `// languages/17-go Rn`, with offset checks that every declaration's range and tokens match the request text, on LF and CRLF content. R1 also checks that the optional descriptor registers the provider when the Go plugin is present.
- **Golden fixtures** in `src/test/testData/go/`, run by `GoGoldenFixturesTest` through the shared golden harness: the standard set of technical specification §12 (overloads become repeated `init` functions, since Go has no overloading), plus Go cases: a field added to a struct and the others re-aligned by `gofmt` (R6, R13), a method's receiver turned into a pointer (§5), a method added to an interface (R6), a constant added to a grouped `const` block (R7), a field renamed inside `X, Y int` (R8), a doc comment added (R10), a local type changed inside a function (R9), a brace moved onto its own line (R14), a defined type turned into a struct (R15), two `init` functions (R12), an embedded field added (R6) and a generic function's type parameters changed (§5).
- **Merge fixtures:** `GoMergeFixturesTest` (`go/src/test/kotlin/.../merge/`) runs the cases in `src/test/testData/go/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11 (R16, R17).
- **Relationship benchmark:** `GoRelationshipBenchmarkTest` (`go/src/test/kotlin/.../relationships/`), as §10 says (R15, R17).
- **Corpus validation:** `GoCorpusValidationTest` (`go/src/test/kotlin/.../psi/go/`) runs with `-Psmartdiff.validation.go.corpus=<list>`; `CorpusValidationTest` and the benchmark's real-commit lines accept `.go` files. The first run uses recent commits from two active open-source Go projects and records the results in [VALIDATION.md](../../VALIDATION.md).
- **Architecture:** `GoArchitectureTest` checks that only `io.github.agadek.smartdiff.engine.declarations.psi.go..` uses `com.goide..`, and the root inventory test lists that row as checked by the `go` subproject.

## 12. Open questions

- **Methods under their type:** whether a method whose receiver type is declared in the same file should also be listed under that type. The proposal keeps methods top-level (out of scope), since Go code is written that way and the type is often in another file.
- **Line breaks:** whether line breaks that Go's semicolon rule ignores, such as one after a `,` or an operator inside brackets, should become formatting-only, as [declarations/07](../declarations/07-kotlin-scala-formatting.md) did for Kotlin. `gofmt` rarely changes them, so the proposal waits for the corpus validation to show they matter.
