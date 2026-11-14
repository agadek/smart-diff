# Rust: details

Status: proposed, 2026-11-13

**Feature:** [Rust](05-rust.md)

## 1. Packaging and IDE versions

- **Plugin:** the Rust PSI comes from the JetBrains Rust plugin, `com.jetbrains.rust` (Marketplace plugin 22407). RustRover bundles it, and it is free there for noncommercial use. IntelliJ IDEA Ultimate, CLion and PyCharm Professional install it from the Marketplace with a paid licence; IntelliJ IDEA Community, the current build target, cannot use it. Spike S8 (2026-11-13, [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md)) confirmed the plugin id `com.jetbrains.rust` (present as 251.26927.79 in the option B test environment) and, from the Marketplace API on 2026-11-13, plugin 22407 is `FREE` (`pricingModel`) and installs in IntelliJ IDEA, CLion and PyCharm (custom IDE list; bundled in RustRover). The 251.* update observed was 251.29188.74 (id 1118668, since 251.29188, until 251.*, compatible with RUST 2025.1.6, CLION 2025.1.7—2025.1.7.2, PYCHARM_PRO 2025.1.6—2025.1.6.2, IDEA_PRO 2025.1.7—2025.1.7.2, JBCLIENT build 251.29188—251.*); latest updates observed were 262.12573.30 and 263.7307.218 (platform-specific). Full compatibility across all listed 251.* releases was not re-enumerated beyond the scanned API pages.
- **Descriptor:** the provider registers in `META-INF/smartdiff-rust.xml`, loaded through `<depends optional="true" config-file="smartdiff-rust.xml">com.jetbrains.rust</depends>` (R1). The descriptor is present as a spike scaffold with no provider yet.
- **Closed source:** unlike the open-source intellij-rust plugin it replaced in 2023, the JetBrains Rust plugin's sources are not published. The PSI class names below are those of the open-source plugin; S8 confirmed for `com.jetbrains.rust` 251.26927.79 that they all exist and parse with the expected names and text offsets (§4), and the verifier report shows which of them it treats as internal API.
- **Language id:** `Rust`, file type for `.rs` (R2). S8 confirmed `Language.findLanguageByID("Rust")` resolves to `org.rust.lang.RsLanguage` when the plugin is present.
- **Plugin dependencies:** the Rust plugin's descriptor requires the plugins `org.toml.lang` and `com.intellij.modules.json`, and the modules `com.intellij.modules.ultimate` and `com.intellij.modules.rust-capable`. IntelliJ IDEA Ultimate 2025.1.3 provides both modules and bundles both plugins; a test environment must add the two bundled plugins explicitly (§2).
- **IDE versions:** no change to P6. Rust support needs a Rust plugin release for that IDE, which in RustRover is always the bundled one.

## 2. Build target

The main build compiles and tests against IntelliJ IDEA Community 2025.1.3, where the Rust plugin cannot be installed. Rust therefore compiles and tests in its own Gradle subproject `rust/`, which targets **IntelliJ IDEA Ultimate 2025.1.3** (build `IU-251.26927.53`) with the pinned Marketplace plugin **`com.jetbrains.rust:251.26927.79`** (option B, [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md)). The root packages the subproject with `pluginModule(implementation(project(":rust")))`, so the main plugin stays on Community.

| Option | How | S8 result (2026-11-13) |
| --- | --- | --- |
| A. RustRover submodule | A Gradle subproject `rust/` that targets RustRover 2025.1 with the bundled `com.jetbrains.rust` | Rejected. With IntelliJ Platform Gradle Plugin 2.15.0, `rustRover("2025.1.3")`, `rustRover("2025.1")` and `create(IntelliJPlatformType.RustRover, "2025.1.3")` downloaded and extracted the IDE but failed dependency resolution: `IDE by path '…/RustRover-2025.1.3-aarch64' is invalid: The 'Core' plugin (ID: 'com.intellij') is expected in …/lib`. `create(IntelliJPlatformType.RustRover, "251.23774.463")` was not found at the queried repositories. Upgrading IPGP is not part of R0. |
| B. Ultimate subproject | The `rust/` subproject targets IntelliJ IDEA Ultimate 2025.1.3 with the pinned Marketplace `com.jetbrains.rust`; the main build stays on Community | **Chosen.** `:rust:test` runs `RustS8SpikeTest` headless with no licence prompt; the plugin, language and PSI classes are present, parsing and offsets are correct, and in-file references resolve (§4, §8, §9). The provider test and golden fixture with the shared harness follow in R1. |

The subproject's test setup:

- Applies `org.jetbrains.intellij.platform.module` (IPGP 2.15.0), Kotlin JVM 2.1.20, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4.
- Declares `intellijIdeaUltimate("2025.1.3")`, `plugin("com.jetbrains.rust:251.26927.79")`, and the bundled plugins `org.toml.lang` and `com.intellij.modules.json` as both `bundledPlugin` and `testBundledPlugin`. Without the last two the Rust plugin stays unloaded in the test sandbox and its extensions are missing.
- Adds a test-only `org.rust.cargo.project.model.impl.TestCargoProjectsServiceImpl` stand-in. The plugin's descriptor names that class as its project service in unit-test mode, but the distributed plugin does not ship it; the stand-in reports no Cargo project. It is not packaged and is removed when the plugin ships the test class.
- `./gradlew buildPlugin check` runs `:rust:test`; the test task copies its `S8 …` lines into the build log.

- S8 answers §1, §2, §8 and §9. The provider test and golden fixture of the "done when" criterion move to R1, which adds them to this subproject.
- Option A would have kept the verifier baseline identical; option B adds RustRover to the verifier targets instead (§3).

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

S8 (2026-11-13, `com.jetbrains.rust` 251.26927.79 on IU 2025.1.3) confirmed these real fully qualified names under `org.rust.lang.core.psi`: `RsFunction`, `RsStructItem`, `RsEnumItem`, `RsEnumVariant`, `RsNamedFieldDecl`, `RsTraitItem`, `RsImplItem`, `RsModItem`, `RsModDeclItem`, `RsTypeAlias`, `RsConstant`, `RsMacro`, `RsMacro2`, `RsMacroCall` (the last used for macro-invocation whitespace ranges in §7). A text-built file parses to `org.rust.lang.core.psi.RsFile`; a sample with one of each of these declarations gave the expected names (`foo`, `S`, `E`, `V`, `f`, `T`, `m`, `decl`, `A`, `C`, `mm`, `m2`), and every element's `text` equals the request text sliced by its range.

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

- `PsiFileFactory.createFileFromText(name, RsLanguage, text, false, false)`, inside the engine's read action. A file from text has no Cargo package or edition, so S8 checked which syntax the parser accepts (`async` closures, `let … else`, `gen` as a keyword) and which edition it assumes.
- S8 (2026-11-13) found that on IU 2025.1.3 with `com.jetbrains.rust` 251.26927.79 the parser accepts all three on a text-built file with no `PsiErrorElement`: `let Some(x) = y else { return };`, `async || {}` and `gen { }`. The parser uses the newest syntax it knows; no edition setting is needed or available for a file without a Cargo project.
- Offsets are checked as for S2: the PSI element's text equals the request text sliced by its range, on VCS-revision content with CRLF line ends. S8 confirmed this, including CRLF content converted with `StringUtil.convertLineSeparators`, where the parsed file keeps the CRLF line ends and the offsets still match.
- Parse errors follow technical specification §6.7. Errors inside macro invocation arguments are not expected, since they are parsed as token trees.

## 9. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates and kind changes (R15):** they work on the declaration model and need no Rust code.
- **Rename grouping:** out of R1 scope, but the blocker S8 looked for is gone. S8 (2026-11-13) showed that a path expression calling a function in the same file resolves on a light copy without a Cargo project: the `RsPath` for `callee` resolves to the `RsFunctionImpl` named `callee`. A `RustReferenceResolver` can therefore follow as its own change to [relationships/04](../relationships/04-rename-grouping.md); until it does, Rust renames show no usage count, as for a language without a resolver.
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

- **`RustS8SpikeTest`** (R0, `rust/src/test/kotlin/.../psi/rust/`): the spike test that answered S8; it checks the plugin id, the language, the real PSI class names, parsed names and offsets, the accepted edition syntax, CRLF offsets and in-file reference resolution. Its `S8 …` lines are copied into the build log. It stays until the provider tests below cover the same ground.
- **Test service stand-in:** `rust/src/test/kotlin/org/rust/cargo/project/model/impl/TestCargoProjectsServiceImpl.kt` satisfies the Rust plugin's unit-test-mode project service, which the distributed plugin does not ship. It reports no Cargo project and is not packaged.
- **`RustDeclarationProviderTest`:** one test per declaration rule, R3 to R12; offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/rust/`: the standard set of technical specification §12 (overloads become repeated keys through `#[cfg]`, since Rust has no overloading), plus Rust cases: a derive added (R10), a trait impl's trait changed (§6), two inherent impls of one type (R12), an item added to an inline module (R4), a variant added (R7), a field added to a struct (R7), a method turned into an associated function (§5), a `macro_rules!` changed, `println!` arguments re-spaced (R14), a whole file reformatted outside macros (R13), items in an `extern` block (R8), and a `#[cfg(test)] mod tests` with a changed test.
- **Merge fixtures** in `src/test/testData/rust/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus an `impl` block changed on both sides (R16).
- **Relationship benchmark** cases in `src/test/testData/rust/relationships/` with `corpus: rust`: extraction, inlining, rename candidate and kind change, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md); usage labels are left empty, since Rust has no usage groups.
- **Corpus validation:** `CorpusValidationTest` and the benchmark's real-commit lines accept `.rs` files. The first run uses recent commits from two active open-source Rust projects, for example a command-line tool and a serialization library, and records the results in [VALIDATION.md](../../VALIDATION.md).
- **Requirement names:** every test names its requirement, such as `// languages/05-rust R14`.

## 12. Open questions

- **Macro whitespace:** whether R14 should cover only macros known to observe spacing (`stringify!`, `concat!`, procedural macros), so a re-formatted `vec![…]` can be formatting-only. Knowing which macro a name refers to needs resolution, so the proposal treats every invocation alike.
- **Test service stand-in:** remove the test-only `TestCargoProjectsServiceImpl` when a Rust plugin release ships its test classes or a public test artifact, and check then whether the `org.toml.lang` and `com.intellij.modules.json` test dependencies can be reduced. The build-target question of §2 is closed.
