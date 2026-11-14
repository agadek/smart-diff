---
status: accepted
---

# The Rust provider builds and tests against IntelliJ IDEA Ultimate with the pinned Rust plugin

[Rust](../specs/languages/05-rust.md) needs the JetBrains Rust plugin PSI, `com.jetbrains.rust` (Marketplace plugin 22407), to compile and test its provider. The main build targets IntelliJ IDEA Community 2025.1.3, where the plugin cannot be installed, so phase R0 / spike S8 ([technical specification §13](../TECHNICAL-SPECIFICATION.md#13-prototype-spikes), [Rust details §2](../specs/languages/05-rust-details.md#2-build-target)) had to choose a target that runs a Rust platform test with the shared harness, locally and in CI, without a licence prompt or secret. The spike ran on 2026-11-13; the test is `RustS8SpikeTest` in the `rust/` subproject.

Marketplace facts confirmed on 2026-11-13 through the Marketplace API:

- `GET /api/plugins/22407`: xmlId `com.jetbrains.rust`, `pricingModel` `FREE`; the description says it adds RustRover to IntelliJ IDEA, CLion and PyCharm, and RustRover bundles it. From version 2026.2 it requires Native Debugging Support (`com.intellij.nativeDebug`).
- The scanned `GET /api/plugins/22407/updates` pages returned `251.29188.74` (update 1118668, since `251.29188`, until `251.*`) for the 251 line, compatible with RUST 2025.1.6, CLION 2025.1.7—2025.1.7.2, PYCHARM_PRO 2025.1.6—2025.1.6.2, IDEA_PRO 2025.1.7—2025.1.7.2 and JBCLIENT `251.29188`—`251.*`. The latest updates observed were `262.12573.30` and `263.7307.218` (platform-specific builds).

## Decision

The `rust/` Gradle subproject targets **IntelliJ IDEA Ultimate 2025.1.3** (build `IU-251.26927.53`) with the pinned Marketplace plugin **`com.jetbrains.rust:251.26927.79`**. It applies `org.jetbrains.intellij.platform.module` (IntelliJ Platform Gradle Plugin 2.15.0), the Kotlin JVM plugin 2.1.20, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4, and the root packages it with `pluginModule(implementation(project(":rust")))`. The main build stays on IntelliJ IDEA Community 2025.1.3.

For the module test environment the subproject also adds the two bundled plugins the Rust plugin requires and the test platform does not add by itself:

- `org.toml.lang` (the plugin's `<dependencies>` names it; IU bundles it)
- `com.intellij.modules.json` (also named in the plugin's `<dependencies>`)

They are declared as `bundledPlugin` and `testBundledPlugin`, so `prepareTestSandbox` puts them in `plugins-test/`. The IU 2025.1.3 distribution provides the modules `com.intellij.modules.ultimate` and `com.intellij.modules.rust-capable`, so the plugin's other dependencies are satisfied.

The plugin's descriptor names `org.rust.cargo.project.model.impl.TestCargoProjectsServiceImpl` as its project service implementation in unit-test mode, but the distributed plugin does not ship that test class. Unit-test mode therefore fails while the workspace model initializes. The subproject adds a test-only stand-in with that class name which reports no Cargo project; parsing a file from text needs none. It is test source, not packaged, and should be deleted when a plugin release ships the test class or a public test artifact.

The root's Plugin Verifier gains RustRover 2025.1.3 and the latest RustRover release when `rustrover` is in `smartdiff.verifier.ides` (the default set). Option B is the first time a verifier target and the test target differ in product: the module compiles and tests on IU, while the verifier checks the packaged plugin in RustRover.

## Findings

`RustS8SpikeTest` answers the questions of [Rust details §1, §8 and §9](../specs/languages/05-rust-details.md):

- **Plugin and language (R1).** `PluginManagerCore` finds `com.jetbrains.rust` (251.26927.79) and `Language.findLanguageByID("Rust")` returns `org.rust.lang.RsLanguage`. The plugin loads no licence prompt in headless tests.
- **PSI classes (R3 to R10).** The classes exist under `org.rust.lang.core.psi`: `RsFunction`, `RsStructItem`, `RsEnumItem`, `RsEnumVariant`, `RsNamedFieldDecl`, `RsTraitItem`, `RsImplItem`, `RsModItem`, `RsModDeclItem`, `RsTypeAlias`, `RsConstant`, `RsMacro`, `RsMacro2` and `RsMacroCall`.
- **Names and offsets.** A text-built file parses to `org.rust.lang.core.psi.RsFile`; for every checked element, `element.text` equals the request text sliced by its range, including CRLF content converted with `StringUtil.convertLineSeparators`.
- **Edition syntax.** `let … else`, `async` closures and `gen { }` blocks all parse on a text-built file with no `PsiErrorElement`. The file has no Cargo edition, so the parser accepts the newest syntax it knows.
- **In-file references.** A path expression calling a function in the same file resolves without a Cargo project: the `RsPath` for `callee` resolves to the `RsFunctionImpl` named `callee`. Rename grouping is therefore possible as its own change; it stays out of R1 scope.

## Considered Options

- **Option A, a RustRover subproject (rejected).** `rustRover("2025.1.3")`, `rustRover("2025.1")` and `create(IntelliJPlatformType.RustRover, "2025.1.3")` downloaded and extracted RustRover 2025.1, but IPGP 2.15.0 rejected the distribution while resolving the test runtime: `IDE by path '…/RustRover-2025.1.3-aarch64' is invalid: The 'Core' plugin (ID: 'com.intellij') is expected in …/lib`. The extracted RustRover 2025.1.3 keeps its core plugin descriptor outside the `lib/*.jar` files that the 2.15.0 validator scans, so the validator cannot find it. `create(IntelliJPlatformType.RustRover, "251.23774.463")` found no artifact at the queried repositories. Upgrading IPGP is not part of R0.
- **Option B, an IntelliJ IDEA Ultimate subproject with the pinned Marketplace Rust plugin (chosen).** It runs the Rust platform test headless, needs no licence secret, and keeps the main build on Community. It costs two bundled-plugin test dependencies and one test-only service stand-in, all recorded above.
- **Building the whole plugin on IntelliJ IDEA Ultimate (rejected).** It would move every feature and the verifier baseline to Ultimate for one language, and Ultimate-only API could leak into the main code unnoticed.

## Consequences

- `./gradlew buildPlugin check` runs `RustS8SpikeTest` in `:rust:test` and copies its `S8 …` lines into the build log; `RustS8SpikeTest` is the R0 answer and stays until the provider tests of R1 cover the same ground.
- R1 adds `RustDeclarationProvider`, `RustInterop`, the new kinds, the macro whitespace rule, provider tests and golden fixtures. The provider test and the golden fixture run with the shared harness through this subproject; the shared test fixtures move or are exposed to both projects then.
- The test-only `TestCargoProjectsServiceImpl` stand-in uses the plugin's internal `CargoProjectsService` interface. It must be revisited when the plugin ships its test class, and it shows that testing against this plugin needs a small compatibility layer.
- The verifier targets for RustRover are added to the root; whether IPGP 2.15.0 resolves the RustRover verifier IDE is checked by `verifyPlugin`, not by `check`, and a failure there is a build-configuration problem, not a provider result.
- The Rust plugin needs `org.toml.lang` and `com.intellij.modules.json`. Any other project that tests against it needs the same two bundled plugins, or the plugin stays unloaded and its extensions are missing.
- In-file references resolving on a light copy means [relationships/04](../specs/relationships/04-rename-grouping.md) can add a `RustReferenceResolver` as its own change; Rust renames still show no usage count until it does.
