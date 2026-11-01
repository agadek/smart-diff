# Feature specifications

One file per feature, in one folder per context, plus a details file for each new feature, written as described in the [specification guide](../SPEC-GUIDE.md). Key words link to the [glossary](../GLOSSARY.md).

## Product scope

SMART Diff adds a [structural outline](../GLOSSARY.md#structural-outline) to the IntelliJ diff. It shows which [declarations](../GLOSSARY.md#declaration) changed and how, and the [native diff](../GLOSSARY.md#native-diff) stays the detail view and the fallback. These rules hold for every feature below:

- **P1** No source code leaves the IDE process, and the plugin sends no telemetry.
- **P2** The plugin never claims that two versions behave the same.
- **P3** The plugin never hides a change by default, and every change stays reachable in the native diff.
- **P4** The plugin never edits code, resolves merge conflicts or applies patches.
- **P5** Unsupported, malformed or oversized files still open in the native diff.
- **P6** The plugin works in IntelliJ Platform 2025.1 and newer, checked by Plugin Verifier on 2025.1 and the latest release.
- **P7** Comparisons never run on the UI thread, and the IDE stays responsive.
- **P8** The outline is usable from the keyboard, every row has an accessible name, and labels never rely on color alone.

Out of scope for now: AI explanations, cloud processing, expression-level differencing inside a declaration, and cross-file refactoring detection. Later work is in the [roadmap](../ROADMAP.md).

## Engines

| Feature | Status |
| --- | --- |
| [01 Comparison engines](engines/01-comparison-engines.md) | implemented, 2026-10-21 |
| [02 Engine contract](engines/02-engine-contract.md) | implemented, 2026-10-21 |
| [03 Analysis limits](engines/03-analysis-limits.md) | implemented, 2026-10-21 |

## Declaration engine

| Feature | Status |
| --- | --- |
| [01 Declaration matching](declarations/01-declaration-matching.md) | implemented, 2026-10-21 |
| [02 Change classification](declarations/02-change-classification.md) | implemented, 2026-10-21 |
| [03 Rename candidates](declarations/03-rename-candidates.md) | implemented, 2026-10-22 |
| [04 Kind changes](declarations/04-kind-changes.md) | implemented, 2026-10-23 |
| [05 Extraction detection](declarations/05-extraction-detection.md) | implemented, 2026-10-23 |

## Languages

| Feature | Status |
| --- | --- |
| [01 Java](languages/01-java.md) | implemented, 2026-10-21 |
| [02 Kotlin](languages/02-kotlin.md) | implemented, 2026-10-21 |
| [03 Scala](languages/03-scala.md) | implemented, 2026-10-22 |

## Outline

| Feature | Status |
| --- | --- |
| [01 Structural outline](outline/01-structural-outline.md) | implemented, 2026-10-22 |
| [02 Outline toggle](outline/02-outline-toggle.md) | implemented, 2026-10-21 |
| [03 Change type filter](outline/03-change-type-filter.md) | implemented, 2026-10-22 |
| [04 SMART Diff viewer](outline/04-smart-diff-viewer.md) | implemented, 2026-10-22 |

## Changes tree

| Feature | Status |
| --- | --- |
| [01 Changes tree summary](changes-tree/01-changes-tree-summary.md) | implemented, 2026-10-22 |
| [02 Declarations in the Changes tree](changes-tree/02-changes-tree-declarations.md) | implemented, 2026-10-23 |

## Settings

| Feature | Status |
| --- | --- |
| [01 Settings page](settings/01-settings-page.md) | implemented, 2026-10-22 |

## Merges

| Feature | Status |
| --- | --- |
| [01 Merge join](merge/01-merge-join.md) | implemented, 2026-10-31 |
| [02 Merge outline](merge/02-merge-outline.md) | implemented, 2026-10-31 |
| [03 Merge outline in three-sided diffs](merge/03-three-sided-diffs.md) | implemented, 2026-10-31 |
| [04 Merge outline in the merge window](merge/04-merge-window.md) | implemented, 2026-10-31 |
| [05 Scala merges and merge fixtures](merge/05-scala-merges.md) | implemented, 2026-11-01 |
| [06 Merge validation](merge/06-merge-validation.md) | implemented, 2026-11-01 |

## Relationships

| Feature | Status |
| --- | --- |
| [01 Relationship benchmark](relationships/01-relationship-benchmark.md) | accepted, 2026-11-01 |
| [02 Relationship model](relationships/02-relationship-model.md) | proposed, 2026-11-01 |
| [03 Relationship matching](relationships/03-relationship-matching.md) | proposed, 2026-11-01 |
| [04 Rename grouping](relationships/04-rename-grouping.md) | proposed, 2026-11-01 |
| [05 Inlining detection](relationships/05-inlining-detection.md) | proposed, 2026-11-01 |
| [06 Refactorings view](relationships/06-refactorings-view.md) | proposed, 2026-11-01 |
| [07 Relationship validation](relationships/07-relationship-validation.md) | proposed, 2026-11-01 |
