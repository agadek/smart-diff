# Inlining detection

Status: proposed, 2026-11-01

**Description:** Links a [removed](../../GLOSSARY.md#removed) method or function to the declaration its code was inlined into, the reverse of [extraction detection](../declarations/05-extraction-detection.md).

**Use case:** A reviewer reads a change where a small helper was removed and its code pasted into its only caller. They see that the code moved unchanged, without searching for it on the [new side](../../GLOSSARY.md#new-side).

## Requirements

- **R1** A removed method or function whose body is mostly code added to a [changed](../../GLOSSARY.md#changed) declaration is an [inlining](../../GLOSSARY.md#inlining).
- **R2** That changed declaration is the inlining's [target](../../GLOSSARY.md#inlining-target).
- **R3** The code counts as added only when the target's old version did not have it.
- **R4** Code that is reformatted or slightly changed on the way still counts.
- **R5** A body with fewer than 10 tokens is never an inlining.
- **R6** An inlining stays removed, with the note `inlined into <target>`.
- **R7** The target stays changed, with the note `code inlined from <declaration>`.
- **R8** A note lists every link, up to 3 names, then `+N more`.
- **R9** Selecting an inlining frames the inlined code on the new side.
- **R10** Selecting a target highlights the code inlined into it.
- **R11** One removed declaration can be inlined into several targets, and one target can take code from several.
- **R12** Inlinings and extractions are [relationships](../../GLOSSARY.md#relationship) in the comparison result.
- **R13** The [summary line](../../GLOSSARY.md#summary-line) counts inlinings as `inlined`, not `removed`.
- **R14** Hiding removed changes with the [change type filter](../../GLOSSARY.md#change-type-filter) also hides inlinings.
- **R15** Detection never changes how a declaration is matched or classified.
- **R16** Detection works the same way for Java, Kotlin and Scala.
- **R17** A file that would take too much work is not checked for inlinings.
- **R18** The benchmark's inlining cases pass its gate.

## Out of scope

- Code inlined into another file.
- Inlined variables and constants.

## References

- [Structural relationships plan §6.5](../../STRUCTURAL-RELATIONSHIPS.md#65-extraction-and-inlining)
- [Extraction detection design](../../EXTRACTION-DETECTION.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
