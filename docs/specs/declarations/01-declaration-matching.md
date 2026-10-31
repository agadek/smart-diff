# Declaration matching

Status: implemented, 2026-10-21

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) pairs each [declaration](../../GLOSSARY.md#declaration) on the [old side](../../GLOSSARY.md#old-side) with its [counterpart](../../GLOSSARY.md#counterpart) on the [new side](../../GLOSSARY.md#new-side), by kind, container, name and signature.

**Use case:** A reviewer opens a diff where methods were added, removed, reordered and moved to another class. The outline lists each declaration once, says what happened to it, and never guesses a pairing it is not sure of.

## Requirements

- **R1** Matching covers all declarations in the file, across containers.
- **R2** A declaration with the same [kind](../../GLOSSARY.md#kind), [container](../../GLOSSARY.md#container), name and [signature](../../GLOSSARY.md#signature) on both sides is a [match](../../GLOSSARY.md#match).
- **R3** A declaration found with the same kind, name and signature in another container is a match that is [moved](../../GLOSSARY.md#moved).
- **R4** A declaration found with the same kind, container and name but another signature is a match with its [signature changed](../../GLOSSARY.md#signature-changed).
- **R5** Overloads that all changed signature are paired by how similar their parameters and content are.
- **R6** When a key occurs more than once on a side and cannot be paired, each declaration is [uncertain](../../GLOSSARY.md#uncertain).
- **R7** An uncertain declaration lists every same-key declaration of the other side as a [candidate](../../GLOSSARY.md#candidate).
- **R8** A declaration left without a counterpart on the new side is [removed](../../GLOSSARY.md#removed).
- **R9** A declaration left without a counterpart on the old side is [added](../../GLOSSARY.md#added).
- **R10** A matched declaration whose order among its siblings changed is moved.
- **R11** Only the declarations that left the longest common order are moved.
- **R12** Every declaration of both sides appears in the result exactly once.
- **R13** Changes are arranged by the new side's containers.
- **R14** A removed declaration is shown where it was, before what was added in its place.
- **R15** The same two files always give the same matches.

## Out of scope

- Moves of code inside a declaration.
- Matching across files.

## References

- [ADR 0001](../../adr/0001-declaration-level-first-release.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Technical specification §7](../../TECHNICAL-SPECIFICATION.md#7-declaration-engine-matching)
