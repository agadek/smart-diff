# Three-Way Merge Outline Plan

Status: proposal, 2026-10-23; nothing is implemented. It expands the roadmap's [structural outline for three-way merges](ROADMAP.md#structural-outline-for-three-way-merges) entry. Section numbers (§) refer to the [technical specification](TECHNICAL-SPECIFICATION.md), and terms follow the [glossary](GLOSSARY.md). Supported languages are those of the declaration engine today: Java, Kotlin, Scala 2 and Scala 3.

## 1. Problem

When a merge, rebase, cherry-pick or unstash stops on a conflict, IntelliJ opens its merge window: the left and right versions around an editable result that starts as the common base. It shows conflicts as text hunks. A reviewer resolving them has to work out from those hunks what each side did to the code, and the hunks hide the cases that matter most:

| Base | Left | Right | Merge window today |
| --- | --- | --- | --- |
| `total(Order)` | renamed to `sum(Order)` | changed its body | One conflict, or two hunks far apart, depending on lines |
| `shippingCost(Order)` | removed, code extracted to `ShippingRates.costFor` | changed its body | A conflict on the old method; the new class merges silently |
| `parse(String)` | moved to another class | changed its body | Unrelated hunks at both places |
| `validate()` | changed one line | changed another line | No conflict at all; merged without a question |
| imports | reordered | one import added | A conflict on the file region |

SMART Diff already knows how to say each of these for two versions. The merge window needs the same outline for both sides at once.

## 2. Goals and non-goals

Goals:

- In the merge window, show which declarations each side changed relative to the base, grouped so that declarations changed on both sides stand out.
- Let the reviewer see a declaration on all three panes at once: selecting it frames it in the left pane, the result pane and the right pane.
- Show the same outline in three-sided diffs that are not merges (a conflicted file's three-sided diff from the Changes view), from the same join.
- Stay conservative ([ADR 0003](adr/0003-conservative-classification.md)): every change either side made is reported, uncertain pairings stay uncertain, and the outline never claims a merge is safe.
- Work for every engine, without changing the engine interface (§3.1).

Non-goals:

- Resolving, applying or editing anything. SMART Diff never touches the result pane; merge conflict resolution stays a product non-goal ([product specification](SPECIFICATION.md#non-goals-for-the-first-release)).
- A semantic merge: deciding that two edits can be combined, or combining them.
- Files with conflict markers opened in an ordinary editor, and merges of directories or binary files.
- Cross-file effects, such as a method one side renamed and the other side calls from another file.

## 3. Risks first

The plan is phased so that the riskiest assumptions are tested before anything is built on them (§9). Each risk names the phase that settles it.

- **R1 Attaching to the merge window (Phase 0).** The outline attaches to two-sided diffs through `DiffExtension.onViewerCreated` ([ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md)). That callback is run by `DiffRequestProcessor`; the merge window is run by a different processor (`MergeRequestProcessor`, with `TextMergeTool` creating a `TextMergeViewer`), which may not call diff extensions at all. If it does not, the fallback is a `MergeTool` registered first that delegates to the platform's text merge tool and wraps its component (§6.2). Either way, the attachment depends on layout details that no API promises.
- **R2 Which side is which (Phase 0).** The left pane is not always "my" code: in a rebase the left side is the branch being rebased onto and the right side the commit being replayed, the reverse of a merge. Labelling sides "ours" and "theirs" would be wrong half the time. The plan uses the window's own content titles (`MergeRequest`/`ContentDiffRequest.getContentTitles()`), which the VCS integration sets correctly for each operation (open question T2).
- **R3 The base must be the same tree twice (Phase 1).** The join (§5) runs the engine base → left and base → right and matches results by base-side ranges. That only works if both runs see the same base declarations. Java and Kotlin parse the same text the same way (§3.1 rule 2), but the Scala provider chooses the dialect from both texts of a comparison (§6.2): base + left could choose Scala 2 while base + right chooses Scala 3, giving two base trees with different whitespace policies and possibly different declarations. §5.3 adds a hint so the dialect is chosen from all three texts, and the join checks the two base trees agree.
- **R4 The result pane moves (Phase 3).** The result pane is editable and the reviewer changes it while the outline is open; "Apply non-conflicting changes" and automatic import resolution can change it before the reviewer does anything. The outline's own input (base, left, right) never changes, so the outline itself is never stale, but its base-side ranges must be mapped into the current result to frame a declaration there (§6.3).
- **R5 Internal API (Phase 0).** The merge viewer classes live in the platform's `diff-impl` module, and some may be internal. Plugin Verifier reports internal-API use; the spike lists what the attachment needs, and anything internal must be reviewed each release, as for the Scala PSI.
- **R6 "Changed on both sides" is not "conflict" (Phase 5).** A declaration both sides changed may merge without a textual conflict (the `validate()` row in §1), and a textual conflict may lie in a declaration only one side changed in content (the other only reformatted it, or the conflict is in the blank lines between two declarations). The outline must not look like a second, disagreeing list of conflicts (open question T3).
- **R7 Space (Phase 3).** The merge window already has three editors. A fourth column beside them may be too narrow on a laptop screen; the outline may need to sit below the editors or start collapsed in this window.
- **R8 Cost (Phase 1).** Two comparisons parse the base twice, and an optional third (§5.2) parses both sides again. Spike S3's timings (§9.3) leave room for this, but the 5 s limit has to cover all runs together.

## 4. Behaviour

For a merge of a branch into `main` where both sides edited `OrderService.java`, the outline reads, with the window's titles as side names:

```text
OrderService.java  2 changed on both sides · 2 in main only · 1 in feature only
Changed on both sides
  method total(Order)       main: rename candidate → sum(Order)   feature: changed
  method validate()         main: changed                          feature: changed
Changed in main only
  method shippingCost(Order)  removed, code extracted to ShippingRates.costFor(Order)
  class ShippingRates         added
Changed in feature only
  method parse(String)      moved to Parser
File region                 main: formatting-only                  feature: changed
```

- **Groups,** in this order: *changed on both sides*, then each side alone, then *uncertain*. A declaration is in *changed on both sides* when each side reports it (§5, the reported rule), whatever the kinds of change; that includes removed on one side and changed on the other, a rename on one side and a change on the other, and added on both sides with the same key. Whether this grouping is primary is open question T1.
- **Same change on both sides:** when both sides made the identical change (the declaration's text on the left equals its text on the right), the row says `same change on both sides` and sorts last in its group. A cherry-picked fix that is already on the target branch is the common case.
- **Labels per side:** a row in *changed on both sides* shows each side's labels after the side's name, as in the two-sided outline (§10). A row in a one-side group shows only that side's labels.
- **Uncertain:** a declaration uncertain on either side goes to the *uncertain* group with that side's candidates, as in the two-sided outline. The join never resolves an uncertain match with information from the other side.
- **Selection** frames the declaration in all three panes: the left side's range on the left, the right side's range on the right, and the base range mapped into the result pane (§6.3). A side where it is absent shows "not present" for that pane; a result pane where the mapping is lost shows "not tracked". Enter moves the caret into the result pane.
- **Summary line:** counts by group, `2 changed on both sides · 2 in main only · 1 in feature only`, with uncertain and unanalyzed counts as in §10. The change type filter (§10) applies to each side's labels; a row is hidden only when every label on both sides is of a hidden type.
- **Engine dropdown, refresh and the outline toggle** behave as in two-sided diffs; one engine runs on both sides.
- **Three-sided diffs** that are not merges get the same outline. Their middle pane is the base itself, so no mapping is needed (§6.1).

## 5. The join

The join is a new IntelliJ-free step in a new package `merge` (§2), which depends on `engine.api` only; the architecture test (§12) adds it to the IntelliJ-free packages, and `ui` may depend on it.

### 5.1 Inputs

Two `ComparisonResult`s from the same engine: `left` for base → left and `right` for base → right. Their old side is the base in both. A result that is not `ANALYZED` or `PARTIAL` on either side makes the outline show that status and reason for that side, with the other side's changes still listed under its own group.

### 5.2 Pairing

1. **Base declarations** pair by old-side range. Every declaration of each side appears in its result exactly once (§7, result tree), unchanged ones included, so each base declaration has a node in both results. Nodes that are `MATCHED`, `REMOVED` or old-side `UNCERTAIN` are indexed by `oldRange` and paired with the other result's node at the same range.
2. **Consistency check:** the two indexes must hold the same base ranges with the same subjects. If they do not (R3), the join gives up and the outline shows each side as a separate two-sided list with the banner "the base was read differently for each side". This is a safeguard; §5.3 should make it unreachable.
3. **Added declarations** pair across sides by key: kind, the path of their containers' subjects in the result tree, name and signature, as rule M1 (§7). A key that occurs exactly once among each side's added nodes pairs into *added on both sides*; any other repetition leaves them unpaired, each in its side's group.
4. **Same change:** for a pair changed on both sides, the join compares the declaration's text on the left with its text on the right. Equal text means *same change on both sides*. Formatting differences between the two sides are not judged in this item: that needs a third comparison, left → right, which the join can ask for later without a model change.
5. **File region:** the two file-region nodes pair directly.
6. **Extraction and rename links** stay with their side. An `Origin` is a base range, so an extraction on the left from a declaration the right changed lists that source under *changed on both sides* with the note `code extracted to …` from the left.

The output is a tree of `MergeNode`s, each with the base subject and range (or the added key), the left node, the right node, and the group. It is a pure function of the two results, so it is deterministic and unit-tested without the IDE.

### 5.3 Engine and request

The engine interface does not change. `ComparisonRequest` gains one optional field, allowed by §5's rule on adding fields:

```kotlin
data class ComparisonRequest(
    // …existing fields…
    val relatedTexts: List<CharSequence> = emptyList(),   // other versions compared with the same old side; hint only
)
```

Both runs of a merge pass the other side as `relatedTexts`. The declaration engine passes it to `DeclarationProvider.dialect`, which the Scala provider then applies to all three texts: Scala 3 when no text has a parse error and any uses Scala 3 syntax, otherwise by error count over all three (§6.2). Providers without dialects ignore it. Engines may ignore the field; the consistency check (§5.2 step 2) catches an engine that should not have.

### 5.4 Limits and threading

The two runs go through `compareSafely` in parallel on pooled threads, each with the size and declaration limits of §9.3. The time limit covers both: the host cancels both after 5 s. Cancelling one cancels the other. Results are discarded as in §9.2, step 3; in a merge window the inputs never change, so only an engine change from the dropdown or a refresh starts a new run.

## 6. Integration

### 6.1 Three-sided diffs

`OutlineDiffExtension` today returns early unless the viewer is a `TwosideTextDiffViewer` with two document contents. It gains a second branch for a `ThreesideTextDiffViewer` with three document contents of one language: the middle content is the base, the left and right contents are the sides. The viewer's component is a `DiffPanelBase` as well (ADR 0005), so the same splitter works. Navigation scrolls all three editors and pauses synchronized scrolling, as for two sides.

### 6.2 The merge window

Spike S6 (§9, Phase 0) decides between:

- **Option A, `DiffExtension`:** if the merge window runs diff extensions for its inner three-sided viewer, the outline attaches exactly as in §6.1, with the result pane as the middle editor. Least code, if it is called.
- **Option B, a delegating `MergeTool`:** `com.intellij.diff.merge.MergeTool` is an extension point (`diff.merge.MergeTool`); the first tool whose `canShow` accepts a request shows it. SMART Diff registers one first, accepting `TextMergeRequest`s of a supported language while the outline is enabled, and delegating to the platform's `TextMergeTool`. Its viewer returns the delegate's toolbar, resolve actions and focus component unchanged and wraps only the delegate's component in the splitter. Risk: platform or plugin code that checks for a `TextMergeViewer` instance would see the wrapper; the spike looks for such checks.
- **Rejected unless both fail:** finding the merge window's component from outside, for example through a window listener. It depends on even more layout details and has no lifecycle to dispose with.

The decision is recorded as ADR 0007. The outline toggle (§10) is added to the merge viewer's toolbar the same way, if the merge window has an equivalent of `Diff.ViewerToolbar`; finding it is part of the spike.

### 6.3 Mapping base ranges into the result pane

When the merge window opens, the result document is expected to hold the base text (to be confirmed by S6). The outline then places a `RangeMarker` on the result document for each reported base range, which the editor keeps up to date through every edit. A marker the reviewer's edits remove or invalidate shows "not tracked" when its row is selected. If the result does not equal the base when the outline attaches (non-conflicting changes already applied, imports already resolved), the base ranges are mapped through a line diff of base and result (`ComparisonManager.compareLines`), and declarations inside changed lines are marked "not tracked" from the start.

The markers are disposed with the viewer and never keep text alive after it (§9.2, step 6).

### 6.4 Not in this item

- **Result status:** whether the reviewer has already resolved the conflicts inside a declaration. It would read the merge viewer's own changes, which is internal API, and is listed under [Later](#11-later).
- **Changes tree:** conflicted files in the Local Changes view get no summary in this item; a summary needs the base and both sides from the VCS merge provider rather than a document, and is listed under [Later](#11-later).

## 7. Fixtures and tests

- **Join unit tests** (no IDE, results built by hand): each group; a rename on one side and a change on the other; removed on one side and changed on the other; added on both sides with the same key, identical and different; a repeated added key left unpaired; moved on one side; same change on both sides; uncertain on one side; one side `FAILED`; the consistency check failing; extraction links on one side.
- **Golden merge fixtures:** `src/test/testData/<language>/merge/<case>/{base,left,right}.<ext>` with `expected-<engine id>.txt`, one line per row with its group, each side's labels and the lines it spans on each side. Each of Java, Kotlin, Scala 2 and Scala 3 covers: changed on both sides, changed on one side only, rename versus change, remove versus change, add on both sides, move versus change, formatting-only versus change, the same change on both sides, and imports changed on both sides. Scala 3 adds a braces-to-indentation change on one side; Scala adds a case where base + one side alone would choose a different dialect than all three (R3).
- **Provider test:** the Scala dialect from three texts (§5.3).
- **Contract:** both runs keep the two-sided contract; the join test checks that every reported node of either result appears in exactly one row, so nothing either side changed is hidden.
- **Integration test:** `MergeOutlineIntegrationTest` opens a three-sided diff and a `TextMergeRequest` built with `DiffRequestFactory`, in the IDE range Plugin Verifier checks: the outline appears, selecting a row frames all three panes, an edit in the result pane keeps the markers in place, and the outline toggle hides it. If S6 shows the merge window cannot run in a headless platform test, the merge half runs the viewer created by the chosen tool directly.

## 8. Validation

Before the feature ships, extend `CorpusValidationTest` with a merge mode, `-Psmartdiff.validation.merges=<list of merge commits>`. For each merge commit with two parents, every file changed on both sides since their merge base is joined from the merge base and the two parents. The corpus is the same repositories as [VALIDATION.md](VALIDATION.md), with Java, Kotlin, Scala 2 and Scala 3 merges.

Flags:

- `join-mismatch`: the consistency check failed (R3).
- `both-sides-clean`: a declaration changed on both sides in a file Git merged without a conflict, to review whether the row helps or alarms (R6).
- `conflict-one-side`: a Git conflict hunk inside a declaration only one side reported as changed in content, the reverse case.
- `slow`: both runs together above 1 s.

Done when there are no join mismatches, every reviewed `both-sides-clean` and `conflict-one-side` file is understood and either fixed or explained in VALIDATION.md, and the median time per file is at most twice the two-sided one.

## 9. Phases

| Phase | Work | Settles | Done when |
| --- | --- | --- | --- |
| 0 | Spike S6 (§13): Option A or B for the merge window; side titles in merge, rebase, cherry-pick and unstash; whether the result starts as the base; whether conflicted files open a three-sided diff from the Changes view; a toolbar group in the merge window; Plugin Verifier output for the needed classes | R1, R2, R5 | A placeholder attaches to a Java merge window and a three-sided diff in a platform test, and ADR 0007 records the choice |
| 1 | Join (§5) in the new `merge` package with its unit tests; `relatedTexts` and the three-text Scala dialect | R3, R8 | Join unit tests and the dialect provider test pass |
| 2 | Three-sided diffs (§6.1): outline rows, groups, three-pane navigation, summary line | — | Golden merge fixtures pass for Java and Kotlin through the three-sided outline |
| 3 | The merge window (§6.2, §6.3): attachment per ADR 0007, result-pane mapping, toggle, layout | R4, R7 | `MergeOutlineIntegrationTest` passes on both Plugin Verifier IDEs |
| 4 | Scala 2 and Scala 3 merge fixtures | R3 | Golden fixtures pass in both dialects |
| 5 | Merge validation (§8), results in VALIDATION.md | R6 | The criteria in §8 are met |

Each phase is one pull request, and each runs the full CI steps (build, tests, Plugin Verifier) before it is pushed.

## 10. Open questions

- **T1 Primary grouping.** Group by "changed on both sides / left only / right only" (proposed), or keep the two-sided outline's tree by container with per-side labels and a filter for "changed on both sides"? Grouping makes likely conflicts stand out; the tree keeps a class's members together.
- **T2 Side names.** Use the window's content titles as written (proposed; they can be long, such as "Changes from feature/rates"), shorten them to the branch or revision when the title names one, or use fixed "left" and "right"? Glossary terms follow the answer: *base* and the two *sides* are new terms, since the glossary's *old side* and *new side* do not fit three versions.
- **T3 Changed on both sides without a conflict.** Show these rows as ordinary members of *changed on both sides* (proposed), mark them "merged without a conflict", or leave them out of the group? They are the merge's silent risk, but also its most frequent false alarm (R6).
- **T4 Opening by default in the merge window.** Follow the existing "Show structural outline automatically" setting (proposed), or add a separate setting for merges, since the merge window is more crowded (R7)?
- **T5 Order in the roadmap.** Before or after the Changes tree level 2 and the other items under Later? The roadmap leaves the order open.

## 11. Later

- **Result status:** mark rows whose conflicts are resolved in the result pane, and show progress (`3 of 5 resolved`), from the merge viewer's own change list if its API allows.
- **Result as a third side:** compare base → result too, so the outline shows what the reviewer's resolution did to each declaration.
- **Conflicted files in the Changes tree:** a summary such as `2 changed on both sides` for each conflicted file in Local Changes and in the merge dialog's file list, from the VCS merge provider's base, left and right revisions. The merge dialog's file list has no known hook; that needs its own spike.
- **Left → right comparison** for pairs changed on both sides, to tell a formatting-only difference between the two sides from a real one.
