# Comparison engines

Status: implemented, 2026-10-21

**Description:** SMART Diff compares files through interchangeable [comparison engines](../../GLOSSARY.md#comparison-engine). Each engine says which languages it supports, and the user chooses one per language.

**Use case:** A developer has two engines that support Kotlin. They pick the one that gives the clearer outline for Kotlin files, and every later Kotlin diff uses it.

## Requirements

- **R1** An engine is registered through the plugin's `comparisonEngine` extension point.
- **R2** Each engine has a stable id, a display name and a version.
- **R3** Each engine says which languages it supports.
- **R4** The candidates for a file are the registered engines that support its language, in registration order.
- **R5** A file with no candidate is not analyzed, and the [native diff](../../GLOSSARY.md#native-diff) works as usual.
- **R6** The selected engine is the user's default for the language, when it is a candidate.
- **R7** Otherwise the selected engine is the first candidate.
- **R8** A stored default whose engine is not installed is kept, but ignored until the engine is back.
- **R9** The [engine dropdown](../../GLOSSARY.md#engine-dropdown) is shown only when a language has two or more candidates.
- **R10** Choosing an engine in the dropdown compares the file again with it.
- **R11** Choosing an engine in the dropdown makes it the default for that language only.
- **R12** When two engines have the same id, the later one is skipped with a warning.
- **R13** Engines can be installed or removed without restarting the IDE.
- **R14** The extension point is internal: only SMART Diff and its own add-ons register engines.

## Out of scope

- Engines from other plugins.

## References

- [ADR 0004](../../adr/0004-pluggable-comparison-engines.md)
- [Technical specification §3 and §4](../../TECHNICAL-SPECIFICATION.md#3-engine-interface)
