# Change classification

Status: implemented, 2026-10-21

**Description:** For each matched [declaration](../../GLOSSARY.md#declaration), the declaration engine says whether its own source is unchanged, [formatting-only](../../GLOSSARY.md#formatting-only), [changed](../../GLOSSARY.md#changed) or [unanalyzed](../../GLOSSARY.md#unanalyzed).

**Use case:** A reviewer opens a diff where a class was reformatted and one method got a real fix. The outline keeps the reformatted methods apart, so the reviewer reads the fix first.

## Requirements

- **R1** A declaration's own source excludes the declarations nested in it.
- **R2** A declaration with the same own source on both sides is unchanged and not reported.
- **R3** A declaration whose own source differs only in whitespace the language ignores is formatting-only.
- **R4** Any other difference in a declaration's own source makes it changed.
- **R5** A declaration with its [signature changed](../../GLOSSARY.md#signature-changed) is changed.
- **R6** A comment change is a change.
- **R7** A change inside a string is a change.
- **R8** Adding, removing or reordering members leaves their [container](../../GLOSSARY.md#container) unchanged.
- **R9** A changed declaration with a [parse error](../../GLOSSARY.md#parse-error) on either side is unanalyzed.
- **R10** A declaration with a parse error is unchanged when its source is the same on both sides.
- **R11** The [file region](../../GLOSSARY.md#file-region) is classified in the same way.
- **R12** A file with parse errors and no declarations, while it is not blank, fails to analyze.
- **R13** Formatting-only never claims that two versions behave the same.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Technical specification §6.7 and §8](../../TECHNICAL-SPECIFICATION.md#8-declaration-engine-classification)
