# Merge validation: details

Status: implemented, 2026-11-02

Feature: [Merge validation](06-merge-validation.md)

## Design

### Where it plugs in

The merge mode of `CorpusValidationTest` replays merge commits as described in the [three-way merge plan §8](../../THREE-WAY-MERGE.md#8-validation) and writes `build/reports/smartdiff-validation/merge/report.md` with one row per file, its flags, and the outline and conflicts of each flagged file (R1 to R24). This file adds how a run is judged and recorded before a release (R25 to R32). The review itself is manual; only R29, R31 and R32 change code.

### Rerunning on the version that ships

The first run, on 2026-11-01, used an engine that still reported a declaration as changed when a declaration was added above it at the start of a Kotlin or Scala file (issue #50). Any change to matching or classification can add or remove flags, so the readiness is judged on a run of the engine version that will ship (R25).

- Rerun after every merged change to matching, classification or the merge join, and after the last such change before the release.
- Use the corpus list of the previous run. Its lines select commits from `HEAD`, so record each clone's `HEAD` commit in the dated section and replace `HEAD` with that commit in later runs. Without it, a clone that was pulled since gives other merge commits, and the two runs cannot be compared.
- When the previous run did not record the commits, record them in the rerun, and say in the dated section that the commit lists of the two runs cannot be compared.
- Keep the reports of both runs, so each file's flags can be compared.

### Verdicts

Every `both-sides-clean` and `conflict-one-side` file in the run that will ship gets one verdict (R26):

| Verdict | When | Release |
| --- | --- | --- |
| `expected` | The outline is right and the flag is the signal it is meant to be: both sides changed different parts of the declaration and Git merged them, or a conflict lies inside a declaration that only one side changed because the other side changed the lines next to it | Not blocked |
| `fixed` | The outline was wrong, and a merged fix makes it right; the rerun shows the file as `expected` or no longer flags it | Not blocked |
| `known limit` | The outline is wrong and is not fixed for this release; an issue describes it (R28) | Not blocked; see the open question |
| `wrong flag` | The outline is right, but the flag does not fit its own definition in R8 or R9, for example conflicts computed differently from the merge window (R10), or a formatting-only side counted as changed (R8) | Blocked until the validation is fixed and rerun (R29) |

A file with several flagged declarations gets the verdict of its worst declaration, in the order `wrong flag`, `known limit`, `fixed`, `expected`, and the reason names the declarations.

### Reviewing a flagged file

For each flagged file in `report.md`:

1. Read the file's outline and conflicts in the report.
2. Compare each side with the base for the flagged declaration: `git diff <base> <parent> -- <path>` for both parents, in the clone.
3. For `both-sides-clean`, check that both sides changed the declaration beyond formatting: its own content, or a move, rename, signature or kind change. If one side only added or removed a sibling next to it, the outline is wrong. If one side is only [formatting-only](../../GLOSSARY.md#formatting-only), with no move, rename, signature or kind change, the outline is right and the flag is wrong (R8).
4. For `conflict-one-side`, check where Git's conflict hunk lies. A hunk that starts or ends inside the declaration because the other side changed a neighbouring line is `expected`; a hunk inside a declaration whose content the other side did change means the outline missed a change.
5. Pick the verdict, write a one-line reason, and open an issue for each wrong outline that is not fixed in the same pull request.

A `conflict-one-side` flag is judged on the part of the conflict that lies in the declaration's own source, outside its children. A container is flagged when the hunk covers its own lines, even if the hunk also covers a child; when the hunk lies only in a child, the child decides (R9). Adding, removing or reordering a member leaves its container unchanged (declarations/02 R8), so a container whose own source is unchanged does not flag a file.

Wrong outlines with the same cause share one issue and one fix. The #50 pattern, a declaration reported as changed because a declaration was added above it at the start of the file, is fixed by PR #58; files of that pattern that are still flagged after the rerun are a new bug.

### Recording the results

The rerun adds a new dated section to VALIDATION.md, after the 2026-11-01 one, and the earlier section links to it:

- **Corpus:** the list, with each clone's commit (see above).
- **Result:** files, merge commits and skips, and a table of each flag's count in this run next to the previous run's (R30). Each changed count names the fix behind it, such as `both-sides-clean 50 → 31 (#58)`.
- **Time:** as in the first run (R13, R17).
- **Flagged files:** one table per verdict, in the order `known limit`, `wrong flag`, `fixed`, `expected`, with the columns file, merge commit, flag, verdict and reason (R27). The `known limit` table also links each issue.
- **Fallbacks:** each `status` file with its side and fallback reason (R31).
- **Ready to ship:** each of R15, R16, R17 and R32, with yes or no.

Once the section says ready, the roadmap's 0.2.0 row for the merge validation is marked done and issue #54 is closed with a link to the section.

### Fallbacks

`status` marks a side that fell back (R12). A fallback for a size limit, a timeout or parse errors is the plugin working as specified (P5). A fallback because the comparison threw is a bug, so the report shows each `status` file's reason (R31), and a thrown comparison blocks the release until it is fixed or turned into one of the specified fallbacks (R32). The report's summary then says `not ready: comparison threw in <file>`.

### Edge cases

- A file flagged in the previous run that is missing from the rerun, because the commit lists differ, is listed under the previous run's verdicts only if it was reviewed there; otherwise it is left out, and the counts say so.
- A file flagged `both-sides-clean` and `conflict-one-side` for different declarations gets one row per flag.
- A verdict reached by inspection only, with no fix and no issue, is `expected` or `wrong flag`, never `known limit` (R28).

## Tests

| Requirement | Check |
| --- | --- |
| R25, R30 | Manual: the dated section gives the clone commits and the counts of both runs |
| R26–R28 | Manual: every flagged file in the report has a row in VALIDATION.md, and every `known limit` row links an issue |
| R29 | Each `wrong flag` fix adds a case to `MergeValidationTest` that names the flag and fails on the old validation |
| R31 | A `MergeValidationTest` case with a side that fell back checks that the report row gives the reason |
| R32 | A `MergeValidationTest` case where the comparison throws checks that the report's readiness says no and names the file |

## Open questions

- Should a `known limit` need the maintainer's approval in the pull request review, or is a linked issue enough? Approval in review is proposed, because a known limit ships a wrong outline.
