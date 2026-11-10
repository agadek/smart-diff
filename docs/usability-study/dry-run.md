# Usability study dry run

Date: 2026-11-10

This is a facilitator dry run of T1–T5. It contains no participant data and no timed runs.

## Environment

- **Worktree:** `/Users/gadek/workspace/semantic-diff-usability-study`, branch `docs/usability-study`
- **Commit:** `b4bb28a` (`b4bb28aebb85f4941b9dbde13e548f08b88d3ae8`)
- **Plugin:** SMART Diff `0.2.0`, built from this worktree
- **IDE:** IntelliJ IDEA Community `IC-251.26927.53`
- **Sandbox:** `.intellijPlatform/sandbox/smart-diff/IC-2025.1.3`
- **Task repositories:** `/tmp/usability-T1` … `/tmp/usability-T5`, prepared with `docs/usability-study/prepare-task.sh` from `/Users/gadek/workspace/smartdiff-corpus`

## Method

For each task I started `./gradlew runIde --args="/tmp/usability-<T>"`, opened the Commit tool
window, selected the one changed file, and opened its diff with Cmd+D. I read the structural
outline through the macOS accessibility tree, then clicked the outline **Hide** button and read
the native side-by-side editors. The native check only reports text present in the old and new
editors; it does not claim painted hunk colours.

## Results

| Task | Expected row | Observed structural outline | Native diff evidence | Result |
| --- | --- | --- | --- | --- |
| T1 | `method isDuplicatePetNameViolation(DataIntegrityViolationException)  added` | `method isDuplicatePetNameViolation(DataIntegrityViolationException)  added`; nearby `processCreationForm`, `processUpdateForm` and `updatePetDetails` rows changed | Old editor has no method and no call; new editor has the method and two `isDuplicatePetNameViolation(ex)` call sites | **Pass** |
| T2 | `function attemptAnotherConnectionSpec(IOException)  rename candidate from function retryTlsHandshake(IOException)  changed` | `function retryTlsHandshake → attemptAnotherConnectionSpec(IOException)  rename candidate, changed`; separate `function attemptAnotherConnection(IOException)  added` | Old editor has `fun retryTlsHandshake`; new editor has `fun attemptAnotherConnection` and `fun attemptAnotherConnectionSpec` | **Pass for the answer; expected-row wording needs an update** |
| T3 | `property → function break  changed  signature changed` | `property → function break  changed, signature changed` | Old editor has `private val break`; new editor has `private def break` | **Pass** |
| T4 | `function checkNonParamTuple(Tree)  removed` | `function checkNonParamTuple(Tree)  removed`; nearby placeholder/binding rows changed | Old editor has the function and its two call sites; new editor has none | **Pass** |
| T5 | `function literal(T)  added` | `function literal(T)  added`; existing `function ExpressionWithColumnType<S>.asLiteral(T)  changed` | Old editor has no `fun literal`; new editor has `fun <T : Any> literal(value: T)` and a `literal(value)` call | **Pass** |

## Notes and replacement decision

- **No task needs replacing for its question or answer.** T1, T3, T4 and T5 show the expected
  declaration and change with the expected labels.
- **T2 needs its expected-answer wording updated, not a replacement.** The current plugin renders
  the rename as `retryTlsHandshake → attemptAnotherConnectionSpec(IOException)  rename candidate,
  changed`, not the `… rename candidate from … changed` wording in `tasks.md`. The old and new
  names and the rename label are all visible, so the T2 question remains answerable.
- **T3** uses `changed, signature changed` rather than the double-space form in `tasks.md`; this is
  punctuation only and does not affect the answer.
- The structural-outline condition showed the expected row in all five tasks. The native condition
  showed the same old/new text change with the outline hidden in all five tasks.

No product code was changed, and nothing was committed or pushed.
