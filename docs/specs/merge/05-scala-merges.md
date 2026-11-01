# Scala merges and merge fixtures

Status: accepted, 2026-11-01

**Description:** Makes the [merge outline](02-merge-outline.md) correct for Scala 2 and Scala 3 merges, and checks every language's merge outline against golden merge fixtures.

**Use case:** A developer rebases a Scala 3 branch that moved a class from braces to indentation, while `main` changed one of its methods. The outline reads the [base](../../GLOSSARY.md#base) and both [sides](../../GLOSSARY.md#side) as Scala 3, shows the reindented class as changed on one side, and the method as [changed on both sides](../../GLOSSARY.md#changed-on-both-sides).

## Requirements

- **R1** The base and both sides of a Scala merge are read as the same Scala version.
- **R2** That version is chosen from all three texts, even when the base and one side alone would choose the other.
- **R3** Code without Scala 3 syntax in all three texts is read as Scala 2.
- **R4** A move from braces to indentation on one side is a change on that side, never [formatting-only](../../GLOSSARY.md#formatting-only).
- **R5** In Scala 2, re-indenting on one side is formatting-only on that side.
- **R6** A class and its companion object are separate rows, and a change to one never pairs with the other.
- **R7** A given, an extension block and its methods each get their own row, as in a two-sided Scala diff.
- **R8** A [kind change](../../GLOSSARY.md#kind-change) on one side, such as a class turned into a trait, is a change on that side, and a member changed on the other side stays in place, changed on that side only.
- **R9** Golden merge fixtures exist for Java, Kotlin, Scala 2 and Scala 3, in `src/test/testData/<language>/merge/<case>/` with `base`, `left` and `right` files.
- **R10** Each fixture has an expected rendering: one line per row with its group, each side's labels and the lines it spans in the base, left and right.
- **R11** Every language covers: changed on both sides, changed on one side only, rename versus change, remove versus change, add on both sides, move versus change, formatting-only versus change, the [same change on both sides](../../GLOSSARY.md#same-change-on-both-sides), and imports changed on both sides.
- **R12** Scala 3 also covers a braces-to-indentation change on one side and a given changed on both sides.
- **R13** Scala 2 and Scala 3 each cover a case where the base and one side alone would choose a different Scala version than all three.
- **R14** Scala covers a companion object change, an extension method change and a kind change on one side.
- **R15** Every fixture also passes the nothing-hidden check for both sides.
- **R16** Running the build with `-Psmartdiff.updateGolden=true` rewrites the expected merge renderings, as for two-sided fixtures.

## Out of scope

- Other languages.
- Real merge commits, which [merge validation](06-merge-validation.md) covers.

## References

- [Three-way merge plan §7 and §9, phase 4](../../THREE-WAY-MERGE.md#7-fixtures-and-tests)
- [Scala](../languages/03-scala.md)
- [Merge join R3](01-merge-join.md)
