# Analysis fallback states

Status: implemented, 2026-11-01

**Description:** When a comparison cannot be analyzed, or its language has no [comparison engine](../../GLOSSARY.md#comparison-engine), the [structural outline](../../GLOSSARY.md#structural-outline) says why and the [native diff](../../GLOSSARY.md#native-diff) stays available.

**Use case:** A reviewer opens a diff of a YAML file, or of a Java file over the size limit, and wants to know why there are no outline rows. They still read and edit the change in the native diff, which is never taken away.

## Requirements

- **R1** A two-sided text diff whose two sides are in the same language and that no engine supports shows the outline with the message `No comparison engine supports <language> files.`
- **R2** The same message says that the native diff shows every change.
- **R3** A file over a size or declaration limit shows `Too large to analyze` with the reason, and no outline rows.
- **R4** A comparison that cannot be analyzed shows `Analysis failed` with the reason, and no outline rows.
- **R5** A comparison that exceeds the time limit is reported as `Analysis failed` with the reason `timed out`.
- **R6** A comparison with some [unanalyzed](../../GLOSSARY.md#unanalyzed) source shows the analyzed rows and a banner saying that some source was not analyzed.
- **R7** In the [Changes tree](../../GLOSSARY.md#changes-tree), a modified or moved file that is not analyzed shows `not analyzed: too large`, `not analyzed: timed out`, `not analyzed: parse errors` or `not analyzed: analysis failed` in place of its [summary line](../../GLOSSARY.md#summary-line).
- **R8** A file in a language without an engine, an added file and a deleted file get no Changes tree summary.
- **R9** Every fallback state keeps the file open in the native diff, and the plugin never edits it.
- **R10** A fallback state never says that two versions behave the same.
- **R11** The outline toggle and the setting that opens the outline automatically keep their usual behavior in each state.
- **R12** A comparison the engine reports as unsupported shows `Not supported` with the reason, and no outline rows.

## Out of scope

- A retry button in the outline. The user can refresh the comparison or change the analysis limits in the settings.
- A separate overview of files that were not analyzed.
- Changing the native diff when a file cannot be analyzed.

## References

- [Details: design and tests](05-analysis-fallback-details.md)
- [Product scope](../README.md#product-scope)
- [Changes tree summary](../changes-tree/01-changes-tree-summary.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary) and [§10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
- [Validation](../../VALIDATION.md)
