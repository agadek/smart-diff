# Extraction detection

Status: implemented, 2026-10-23

**Description:** Links an [added](../../GLOSSARY.md#added) method or function to the code it was extracted from, when that code was removed from a [changed](../../GLOSSARY.md#changed) declaration in the same file.

**Use case:** A reviewer reads a change where a long method was split into a helper. They want to see that the helper's code was moved unchanged, without searching for it on the [old side](../../GLOSSARY.md#old-side).

## Requirements

- **R1** An added method or function whose body is mostly code removed from a changed declaration is an [extraction](../../GLOSSARY.md#extraction).
- **R2** That changed declaration is the extraction's [source](../../GLOSSARY.md#extraction-source).
- **R3** The code counts as removed only when the source's new version no longer has it.
- **R4** Code that is reformatted or slightly changed on the way still counts.
- **R5** A body with fewer than 10 tokens is never an extraction.
- **R6** An extraction stays added, with the note `extracted from <source>`.
- **R7** The source stays changed, with the note `code extracted to <declaration>`.
- **R8** A note names the other end by its outline label, with its container when the containers differ.
- **R9** A note lists every link, up to 3 names, then `+N more`.
- **R10** Selecting an extraction frames the extracted code on the old side.
- **R11** Selecting a source highlights the code that was extracted from it.
- **R12** The [summary line](../../GLOSSARY.md#summary-line) counts extractions as `extracted`, not `added`.
- **R13** Hiding added changes with the [change type filter](../../GLOSSARY.md#change-type-filter) also hides extractions.
- **R14** Detection never changes how a declaration is matched or classified.
- **R15** Detection works the same way for Java, Kotlin and Scala.
- **R16** A file that would take too much work is not checked for extractions.

## Out of scope

- Code extracted to another file.
- Code extracted into a lambda, a local function or a variable.
- Inline Method, the reverse refactoring.

## References

- [Extraction detection design](../../EXTRACTION-DETECTION.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Validation, 2026-10-23](../../VALIDATION.md#2026-10-23-extraction-detection)
