# Platform API stability

Status: proposed, 2026-11-17

**Description:** A scheduled Plugin Verifier run checks the plugin against the next IntelliJ IDEA build before it is released, every experimental, internal or non-public API the plugin uses is listed with the one adapter that uses it, and a missing or changed API turns off only the feature that needs it, so the diff keeps working.

**Use case:** The IDE or the Scala plugin updates and removes a method the Scala provider calls. Before this, opening a Scala diff could fail with an error report. With this, the maintainer already saw the problem in the weekly early-access run, and a user who updates first gets the native diff without a structural outline for Scala files while every other language keeps working.

## Requirements

### Early-access verification

- **R1** A scheduled workflow runs Plugin Verifier once a week and on demand against the newest IntelliJ IDEA early-access build, with the optional Scala, Python and Rust dependencies resolved as for the other targets.
- **R2** When no early-access build is newer than the latest release, the run verifies the latest release and its summary says so.
- **R3** The run fails on any compatibility problem, and its summary names the IDE build and counts the experimental, internal, deprecated and scheduled-for-removal API uses.
- **R4** Pull request and `main` builds are unchanged: they keep verifying the lowest supported build and the latest release of each IDE family.
- **R5** The release checklist requires the latest early-access run to be green, or its failure to be reviewed and recorded.

### Inventory and adapters

- **R6** `docs/PLATFORM-API.md` lists every API the plugin uses that Plugin Verifier reports as experimental, internal, deprecated or scheduled for removal, and the PSI of every optional plugin dependency and the Kotlin Analysis API, each with: the API, its status, the adapter class or package that uses it, the feature that needs it, the fallback, and whether a public replacement exists in the lowest supported build.
- **R7** Each listed API is used only by its listed adapter. An architecture test fails when any other class uses it.
- **R8** A use that Plugin Verifier reports and the inventory does not list fails the early-access run and the release check.
- **R9** When the inventory records a public replacement available in the lowest supported build, the adapter uses the replacement, and the listed API is removed from the inventory.

### Degrading

- **R10** When an adapter meets a missing class, method or field, or an incompatible change to one, the feature that needs it is turned off for the rest of the IDE session, and the error is logged once as a warning naming the API and the feature.
- **R11** A declaration provider that is turned off leaves its files to the next provider that supports them, or to the [analysis fallback](../outline/05-analysis-fallback.md), so the [native diff](../../GLOSSARY.md#native-diff) opens as usual.
- **R12** A reference resolver that is turned off leaves renames without grouped usages, as for an unresolved usage in [rename grouping](../relationships/04-rename-grouping.md).
- **R13** A Changes tree hook that is turned off leaves the tree without summaries or declaration rows, as before the plugin was installed.
- **R14** Such an error never opens an IDE error report, never stops another feature, and never makes a diff fail to open.
- **R15** A file whose provider is turned off gets the same outline message and Changes tree state as a file in a language no engine supports ([outline/05](../outline/05-analysis-fallback.md) R1, R8), never an analyzed result.

## Out of scope

- An `untilBuild`: the plugin keeps supporting every later release, and the early-access run is what catches a break.
- Automatic issue creation from a failed run; the failed workflow run is the report.
- Early-access builds of PyCharm and RustRover, which follow IntelliJ IDEA's platform.
- Replacing an API that has no public replacement yet.

## References

- [Details: design and tests](01-platform-api-stability-details.md)
- [Analysis fallback](../outline/05-analysis-fallback.md)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md), [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md)
- [RELEASING.md](../../RELEASING.md)
- [Roadmap: Platform API stability](../../ROADMAP.md#platform-api-stability)
