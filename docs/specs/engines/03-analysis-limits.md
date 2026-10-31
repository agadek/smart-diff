# Analysis limits

Status: implemented, 2026-10-21

**Description:** [Analysis limits](../../GLOSSARY.md#analysis-limits) stop a comparison that would take too long, and say why the file was not analyzed.

**Use case:** A developer opens the diff of a large generated file. The IDE stays responsive, the [native diff](../../GLOSSARY.md#native-diff) shows as usual, and the outline says the file is too large.

## Requirements

- **R1** A side larger than 1 MB is not analyzed.
- **R2** A side with more than 5 000 [declarations](../../GLOSSARY.md#declaration) is not analyzed.
- **R3** An analysis that runs longer than 5 s is cancelled.
- **R4** The user can change all three limits in the settings.
- **R5** A file over a size or declaration limit shows "Too large to analyze" in place of the outline.
- **R6** An analysis cancelled by the time limit shows "Analysis failed" with the reason that it timed out.
- **R7** A failed analysis shows "Analysis failed" with the reason.
- **R8** A file with some [unanalyzed](../../GLOSSARY.md#unanalyzed) source shows a banner that some source was not analyzed.
- **R9** The native diff stays available in every one of these cases.

## References

- [Technical specification §9.3 and §10](../../TECHNICAL-SPECIFICATION.md#93-limits)
