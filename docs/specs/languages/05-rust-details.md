# Rust: details

Status: proposed, 2026-11-13

**Feature:** [Rust](05-rust.md)

## 1. Packaging and IDE versions

- **Plugin:** the Rust PSI comes from the JetBrains Rust plugin, `com.jetbrains.rust` (Marketplace plugin 22407). RustRover bundles it, and it is free there for noncommercial use. IntelliJ IDEA Ultimate, CLion and PyCharm Professional install it from the Marketplace with a paid licence; IntelliJ IDEA Community, the current build target, cannot use it. Spike S8 confirms the plugin id and the IDEs on 2025.1 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-rust.xml`, loaded through `<depends optional="true" config-file="smartdiff-rust.xml">com.jetbrains.rust</depends>` (R1).
- **Closed source:** unlike the open-source intellij-rust plugin it replaced in 2023, the JetBrains Rust plugin's sources are not published. The PSI class names below are those of the open-source plugin; S8 checks that the current plugin keeps them, and the verifier report shows which of them it treats as internal API.
- **Language id:** `Rust`, file type for `.rs` (R2).
- **IDE versions:** no change to P6. Rust support needs a Rust plugin release for that IDE, which in RustRover is always the bundled one.

## 2. Build target

The build compiles and tests against IntelliJ IDEA Community 2025.1.3, where the Rust plugin cannot be installed, so Rust needs a different target for compiling and testing. Spike S8 decides between:

| Option | How | For | Against |
| --- | --- | --- | --- |
| A. Rust submodule | A Gradle subproject `rust/` that targets RustRover 2025.1 and is packaged into the plugin through `pluginModule(implementation(project(":rust")))` | The main build stays on IntelliJ IDEA Community; RustRover bundles the plugin, so nothing to pin | The golden-fixture, merge and benchmark harnesses move into shared test fixtures used by both projects |
| B. Ultimate build target | Build and test the whole plugin against IntelliJ IDEA Ultimate 2025.1.3 with a pinned Marketplace `com.jetbrains.rust` | One project; the harnesses stay as they are | The Rust plugin is paid there, so tests may need a licence; Ultimate-only API could leak into the code, caught only by the verifier on Community |

- S8 is done when one option runs a Rust provider test and a golden fixture with the shared harness, locally and in CI, with no licence prompt and no licence secret. The choice goes in an ADR, since it changes the build of every feature.
- Option A is preferred if both work, because it keeps the build target that every other language and the verifier baseline use.

## 3. Plugin Verifier

- Add RustRover 2025.1 and the latest RustRover release (`IntelliJPlatformType.RustRover`) as verifier targets, in their own job of the verifier matrix ([Python details §2](04-python-details.md#2-plugin-verifier)). The verifier resolves the bundled `com.jetbrains.rust` there.
- On IntelliJ IDEA Community the optional dependency stays unresolved. The verifier reports it as a missing optional dependency, which is not a failure; the run is reviewed to confirm that.
- Rust PSI use is expected in the internal- or experimental-API report, as Scala's is. Every reported use is reviewed, and the provider keeps all Rust plugin calls in one class, `RustInterop`, so a change in the plugin breaks one place. A `NoClassDefFoundError` or `NoSuchMethodError` from the Rust plugin becomes a `FAILED` result with the native diff shown, never an exception in the diff window ([product scope](../README.md#product-scope) P5).

## 4. Declarations

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `RsFunction` (not local) | FUNCTION | `name` | `self` and parameter types (§5) |
| `RsStructItem` (`struct`) | STRUCT | `name` | — |
| `RsStructItem` (`union`) | UNION | `name` | — |
| `RsEnumItem` | ENUM | `name` | — |
| `RsEnumVariant` | VARIANT | `name` | — |
| `RsNamedFieldDecl` | FIELD | `name` | — |
| `RsTraitItem` | TRAIT | `name` | — |
| `RsImplItem` | IMPL | the type, or `Trait for Type` (§6) | — |
| `RsModItem`, `RsModDeclItem` | MODULE | `name` | — |
| `RsTypeAlias` (also an associated type) | TYPE_ALIAS | `name` | — |
| `RsConstant` with `const` | CONSTANT | `name` | — |
| `RsConstant` with `static` | STATIC | `name` | — |
| `RsMacro` (`macro_rules!`) and `RsMacro2` (`macro`) | MACRO | `name` | — |

- **New kinds:** `STRUCT`, `UNION`, `VARIANT`, `IMPL`, `MODULE`, `CONSTANT`, `STATIC` and `MACRO`, added to `DeclarationKind`. Labels show them as `struct`, `impl`, `module` and so on; Rust calls enum members variants, so the Java and Scala `ENUM_CONSTANT` would read wrongly. The matcher's list of type kinds for rule M4b gains `STRUCT`, `UNION`, `ENUM` already being there, so a `struct` turned into an `enum` of the same name is a kind change (R15).
- **Containers:** inline modules, structs (named fields), enums (variants), traits and `impl` blocks. A `mod name;` declaration has no members; its file is another diff.
- **Extern blocks (R8):** the functions and statics of `extern "C" { … }` are declarations of the module or file that holds the block; the block's own tokens (`extern "C" {`, `}`) belong to that container or the file region.
- **Not declarations (R9):** items inside function bodies and blocks, closures, `use`, `extern crate`, macro invocations at item level (`lazy_static! { … }`, `thread_local! { … }`), `const _: () = …`, and the tuple fields of structs and variants. Their source belongs to the declaration that contains them, or to the file region. Declarations that a macro would generate are not seen, since nothing is expanded.
- **Ranges (R10):** outer attributes (`#[derive(…)]`, `#[cfg(test)]`) and outer doc comments (`///`, `/** */`) are in the declaration's range and own tokens, so adding a derive changes the struct. Inner attributes and inner doc comments (`#![…]`, `//!`) belong to their module or the file region.
- **Repeated keys (R12):** several inherent `impl Point` blocks, or two functions of one name under different `#[cfg]` attributes, share a key and follow the matcher's rules for repeated keys.

## 5. Signatures

- The signature is the `self` parameter, if any, then each parameter's type, joined with `,`, normalized as in technical specification §6.6: no whitespace, comments or attributes, and no parameter names or patterns (R11). Generic parameters, `where` clauses and the return type are left out, as for the other languages.
- The `self` parameter is written as its form: `self`, `&self`, `&mut self`, `&'a self`, or its explicit type for `self: Box<Self>`. A `mut` binding (`mut self`) is left out, since it does not change the caller's contract. `fn area(&self, scale: f64)` → `&self,f64`; turning a method into an associated function changes its signature.
- The key form drops all whitespace (`&mut T` → `&mutT`); this cannot collide, since `mut`, `dyn` and `impl` are keywords. The display signature keeps one space between two words and none next to `<>()[],:.&`: `&mut self, &dyn Fn(i32) -> i32`.
- Functions have a signature even with no parameters (the empty one); other kinds have none.

## 6. Naming `impl` blocks

- An inherent impl is named by its type, a trait impl by the trait, ` for ` and the type, each normalized like a display signature, without the impl's generic parameters and `where` clause: `impl<T: Clone> fmt::Display for Wrapper<T> where …` → `fmt::Display for Wrapper<T>`; `impl Point` → `Point`; `impl !Send for Handle` → `!Send for Handle`.
- The row reads `impl fmt::Display for Wrapper<T>`. Changing the implemented trait or the type is a rename of the block; the rename candidate rule (M5) pairs it when its items are similar.
- The generic parameters and the `where` clause are the impl's own tokens, so changing them changes the block.

## 7. Whitespace policy

- Rust trees use `INSIGNIFICANT`, like Java: semicolons end statements, so line breaks and indentation carry no meaning (R13).
- **Macro invocations (R14):** a macro sees its arguments as tokens, and `stringify!`, procedural macros through spans, and some formatting macros can observe the spacing between them. The provider therefore marks every whitespace token inside the argument token tree of an `RsMacroCall`, such as `format!(…)` or `vec![…]`, as significant: the base provider takes a list of ranges from the language provider (`significantWhitespace(file)`) and classifies the whitespace tokens in them as `OTHER`, so they are compared exactly. A re-formatted `println!` call is then changed, which is strict but never hides a change.
- `macro_rules!` bodies are not invocations; whitespace there is formatting, since a macro definition matches tokens, not spacing.
- `OutlineController.whitespacePolicy` maps `Rust` to `INSIGNIFICANT`.

## 8. Parsing and parse errors

- `PsiFileFactory.createFileFromText(name, RsLanguage, text, false, false)`, inside the engine's read action. A file from text has no Cargo package or edition, so S8 checks that the parser accepts edition 2021 and 2024 syntax (`async` closures, `let … else`, `gen` as a keyword) on text-built files, and which edition it assumes.
- Offsets are checked as for S2: the PSI element's text equals the request text sliced by its range, on VCS-revision content with CRLF line ends.
- Parse errors follow technical specification §6.7. Errors inside macro invocation arguments are not expected, since they are parsed as token trees.

## 9. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates and kind changes (R15):** they work on the declaration model and need no Rust code.
- **Rename grouping:** out of scope. The Rust plugin resolves names through its Cargo project model, which a light copy of one file does not have. If S8 shows that in-file names (local functions, items of the same module) resolve on a light copy, a `RustReferenceResolver` follows as its own change to [relationships/04](../relationships/04-rename-grouping.md); until then Rust renames show no usage count, as for a language without a resolver.
- **Merges and Changes tree (R16):** both use the engine's result and need no Rust code.

## 10. Phases

They run in parallel with the [Python phases](04-python-details.md#9-phases) (decided 2026-11-13). The two share three changes: the verifier matrix, `DeclarationKind` and `OutlineController.whitespacePolicy`. Whichever of P0 and R0 merges first adds the verifier matrix, and the other adds its job to it; each language's P1 or R1 adds only its own kinds and mapping lines, so the two branches touch different lines and rebase cleanly.

| Phase | Content | Done when |
| --- | --- | --- |
| R0 | Spike S8, the build target decision and its ADR, `smartdiff-rust.xml` with no provider yet, and the RustRover verifier job | S8 answers §1, §2, §8 and §9; `buildPlugin check` runs a Rust platform test; the verifier passes on all targets |
| R1 | `RustDeclarationProvider`, `RustInterop`, the new kinds, the macro whitespace rule, provider tests and golden fixtures | R1 to R14 pass in tests named after them |
| R2 | Merge fixtures, relationship benchmark fixtures and the corpus validation | R15 to R17 pass; the benchmark keeps its `no-new-wrong-pairs` gate; a corpus run is in [VALIDATION.md](../../VALIDATION.md) |

After R2 the feature is implemented, the plugin description, README and CHANGELOG name Rust, and it ships in the next release.

## 11. Tests

- **`RustDeclarationProviderTest`:** one test per declaration rule, R3 to R12; offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/rust/`: the standard set of technical specification §12 (overloads become repeated keys through `#[cfg]`, since Rust has no overloading), plus Rust cases: a derive added (R10), a trait impl's trait changed (§6), two inherent impls of one type (R12), an item added to an inline module (R4), a variant added (R7), a field added to a struct (R7), a method turned into an associated function (§5), a `macro_rules!` changed, `println!` arguments re-spaced (R14), a whole file reformatted outside macros (R13), items in an `extern` block (R8), and a `#[cfg(test)] mod tests` with a changed test.
- **Merge fixtures** in `src/test/testData/rust/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus an `impl` block changed on both sides (R16).
- **Relationship benchmark** cases in `src/test/testData/rust/relationships/` with `corpus: rust`: extraction, inlining, rename candidate and kind change, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md); usage labels are left empty, since Rust has no usage groups.
- **Corpus validation:** `CorpusValidationTest` and the benchmark's real-commit lines accept `.rs` files. The first run uses recent commits from two active open-source Rust projects, for example a command-line tool and a serialization library, and records the results in [VALIDATION.md](../../VALIDATION.md).
- **Requirement names:** every test names its requirement, such as `// languages/05-rust R14`.

## 12. Open questions

- **Build target:** option A or B of §2, decided by S8.
- **Macro whitespace:** whether R14 should cover only macros known to observe spacing (`stringify!`, `concat!`, procedural macros), so a re-formatted `vec![…]` can be formatting-only. Knowing which macro a name refers to needs resolution, so the proposal treats every invocation alike.
