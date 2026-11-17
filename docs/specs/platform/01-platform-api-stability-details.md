# Platform API stability: details

Status: proposed, 2026-11-17

**Feature:** [Platform API stability](01-platform-api-stability.md)

## Design

### Early-access verifier family (R1 to R4)

- `build.gradle.kts` gains the verifier family `eap`, accepted by `-Psmartdiff.verifier.ides` next to `idea`, `pycharm` and `rustrover` but not in the default set, so `./gradlew verifyPlugin` with no property and the pull request matrix are unchanged (R4). It selects the newest IntelliJ IDEA build on the EAP channel (`ProductRelease.Channel.EAP`, type `IntellijIdea`). When the newest EAP build is not newer than the newest release (R2), it selects the newest release instead and writes `No early-access build newer than <build>; verified the latest release.` to the file named by `GITHUB_STEP_SUMMARY` when that variable is set.
- A new workflow `.github/workflows/eap-verifier.yml` runs on `schedule` (Mondays, 04:00 UTC) and `workflow_dispatch`, with the same disk clean-up and Java and Gradle setup as the verify job in `build.yml`, then `./gradlew verifyPlugin -Psmartdiff.verifier.ides=eap` and the inventory check below. It uploads the verifier reports as `plugin-verifier-reports-eap`.
- A summary step (R3) reads the verifier's report directory for the verified build and appends to `GITHUB_STEP_SUMMARY` the IDE build, the verdict and the number of experimental, internal, deprecated and scheduled-for-removal API uses. The step runs on success and failure.
- The verifier's failure levels stay as in `build.yml` (compatibility problems fail; API status uses are reported), so the run fails on the same problems the pull request check fails on (R3).

### Inventory (R6, R8, R9)

`docs/PLATFORM-API.md` has one table row per API, with the columns API, status (experimental, internal, deprecated, scheduled for removal, or non-public plugin PSI), adapter, feature, fallback and public replacement. It is written from the verifier reports of the current targets and from the imports of `org.jetbrains.plugins.scala`, `com.jetbrains.python`, the Rust plugin's packages and `org.jetbrains.kotlin.analysis` in the main and `rust` source sets.

A Gradle task `checkPlatformApiInventory` (R8) reads the verifier reports under `build/reports/pluginVerifier`, collects every reported experimental, internal, deprecated or scheduled-for-removal API (class, or class and member), and fails, naming each one, when it is not in the inventory's API column. The inventory lists APIs by fully qualified class name, with the member when the report names one. The task runs in the early-access workflow and is a step of [RELEASING.md](../../RELEASING.md) "Every release"; `check` does not depend on it, because it needs the verifier's downloaded IDEs.

When the inventory records a public replacement in 2025.1 (R9), the implementation switches to it in the same change and drops the row. Today's known uses (ChangesViewNodeAction and the other experimental Changes view API, the Scala plugin PSI, the Python plugin PSI, the Rust plugin PSI and the Kotlin Analysis API) are each checked for a replacement once, and the result is recorded in the row.

### Adapters and the architecture test (R7)

`ArchitectureRules` gets one rule per inventory row group, each `noClasses().that().resideOutsideOfPackages(<adapter>).should().dependOnClassesThat(<api>)`:

| API | Allowed only in |
| --- | --- |
| `org.jetbrains.plugins.scala..` | `engine.declarations.psi.scala..`, `references.ScalaReferenceResolver` |
| `com.jetbrains.python..` | `engine.declarations.psi.python..`, `references.PythonReferenceResolver` |
| `org.jetbrains.kotlin.analysis..` | `references.KotlinReferenceResolver` |
| The experimental Changes view classes the inventory lists | `ui.changes.ChangesTreeSetup`, `ui.changes.ChangesSummary` |

The Rust provider lives in the `rust` subproject; the same rule runs there against the Rust plugin's packages, allowed only in its provider package. The allowed lists are the inventory's adapter column, and the test fails when the two differ, so a new use is either moved into an adapter or listed.

### Turning a feature off (R10 to R15)

A new `ApiGuard` object in `engine.api` (it depends on nothing in the plugin) holds the set of turned-off features for the IDE session:

- `ApiGuard.run(feature: GuardedFeature, fallback: () -> T, block: () -> T): T` returns `fallback()` when the feature is off; otherwise it runs `block`, and on `LinkageError` (`NoClassDefFoundError`, `NoSuchMethodError`, `NoSuchFieldError`, `AbstractMethodError`, `IncompatibleClassChangeError`) it turns the feature off, logs one warning `SMART Diff turned off <feature>: <API> is not available in this IDE (<error>).` with `Logger.warn` (never `error`, so no IDE error report, R14), and returns `fallback()`. `ProcessCanceledException` and `CancellationException` are rethrown as before.
- `GuardedFeature` has one entry per inventory row's feature: each optional-plugin provider (`SCALA_PROVIDER`, `PYTHON_PROVIDER`, `RUST_PROVIDER`), each resolver (`KOTLIN_REFERENCES`, `SCALA_REFERENCES`, `PYTHON_REFERENCES`), and `CHANGES_TREE_HOOK`.
- Providers (R11, R15): the provider registry calls `supports` and the provider's analysis through `ApiGuard`. A provider that is off answers `supports` with false, so the registry picks the next provider or, with none, the engine is unsupported for that language and [outline/05](../outline/05-analysis-fallback.md) R1 and R8 apply. A provider that fails during an analysis turns off and that comparison is retried once without it, so the current diff gets the fallback message rather than `Analysis failed`.
- Resolvers (R12): `ReferenceResolution` runs a resolver through `ApiGuard` with the fallback "no reference resolved", the same result as an unresolved usage.
- Changes tree (R13): the `changesViewModifier` and node action entry points run through `ApiGuard` with the fallback "no summary", so the tree shows files as the platform does.
- Today's `catch (e: Exception)` blocks in `EngineRegistry`, `DeclarationEngine`, `ReferencePass` and `ReferenceResolution` stay; `LinkageError` is not an `Exception`, which is why it needs this guard.

## Tests

| Test | Checks |
| --- | --- |
| `VerifierFamiliesTest` (Gradle TestKit or a configuration-time check run by `check`): `eap` is accepted, is not in the default set, and an unknown family still fails | R1, R4 |
| `PlatformApiInventoryTest`: the inventory file parses, every row has every column, and `checkPlatformApiInventory` fails on a report fixture with an unlisted use and passes on one with only listed uses | R6, R8 |
| `ArchitectureTest` platform/01 R7: one test per API rule, and the allowed lists equal the inventory's adapter column | R7 |
| `ApiGuardTest`: each `LinkageError` subtype turns the feature off and returns the fallback, a second call skips the block, one warning is logged, cancellation is rethrown, other features stay on | R10, R14 |
| `GuardedProviderTest`: a test provider throwing `NoSuchMethodError` from `supports` and from analysis leaves the file to the next provider or to the unsupported message, and the diff opens | R11, R14, R15 |
| `GuardedResolverTest`: a resolver throwing `NoClassDefFoundError` leaves the rename without usages and other renames unaffected | R12 |
| `GuardedChangesTreeTest`: the modifier throwing `NoSuchMethodError` leaves the tree without summaries and does not fail | R13 |
| Manual: the early-access workflow run on demand, green, with the summary lines | R1, R2, R3 |

`ApiGuard` resets between tests through an internal `resetForTests()`.
