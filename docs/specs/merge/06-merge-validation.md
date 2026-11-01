# Merge validation

Status: proposed, 2026-10-31

**Description:** Runs the merge outline on real merge commits before it ships, and flags results to review by hand.

**Use case:** Before releasing the merge outline, the maintainer replays a few hundred merges from the usual corpus. The report lists the files where the outline and Git disagree, and the fixes or explanations go into VALIDATION.md.

## Requirements

- **R1** `CorpusValidationTest` has a merge mode, run with `-Psmartdiff.validation.merges=<list>`.
- **R2** Each line of the list is a local Git repository followed by `git rev-list` arguments that select merge commits, as in the two-sided list.
- **R3** Without the property, the merge mode is skipped, so CI does not need the corpus.
- **R4** For each merge commit with two parents, every Java, Kotlin and Scala file changed on both sides since their merge base is checked.
- **R5** A file is joined from the merge base and the two parents, as the [merge join](01-merge-join.md) does in the IDE.
- **R6** The report goes to `build/reports/smartdiff-validation/merge/`, with one row per file and its flags, and the outline of each flagged file.
- **R7** The flag `join-mismatch` marks a file where the two results did not agree on the [base](../../GLOSSARY.md#base).
- **R8** The flag `both-sides-clean` marks a declaration [changed on both sides](../../GLOSSARY.md#changed-on-both-sides) in a file that merges without a conflict.
- **R9** The flag `conflict-one-side` marks a conflict inside a declaration that only one side changed in content.
- **R10** Conflicts are computed from the three texts as the merge window computes them.
- **R11** The flag `slow` marks a file where both comparisons together took more than 1 s.
- **R12** The flags `status` and `hidden` mark a side that fell back or hid a difference, as in the two-sided mode.
- **R13** The report gives the median time per file next to the median of the two-sided comparisons of the same files.
- **R14** The corpus covers Java, Kotlin, Scala 2 and Scala 3 merges, from the repositories in VALIDATION.md.
- **R15** The merge outline is ready to ship when no file has `join-mismatch`.
- **R16** It is ready only when every `both-sides-clean` and `conflict-one-side` file has been reviewed and fixed, or explained in VALIDATION.md.
- **R17** It is ready only when the median time per file is at most twice the two-sided median.
- **R18** The results and the corpus are recorded in a dated section of VALIDATION.md.

## References

- [Three-way merge plan §8 and §9, phase 5](../../THREE-WAY-MERGE.md#8-validation)
- [VALIDATION.md](../../VALIDATION.md)
