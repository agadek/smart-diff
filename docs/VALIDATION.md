# SMART Diff Validation

Classification quality beyond this release corpus is measured by the labelled [relationship benchmark](RELATIONSHIP-BENCHMARK.md); this file stays the record of the release runs. Every run below that includes Scala uses the pinned Scala plugin version (`scalaPluginVersion` in `gradle.properties`, currently 2025.1.32).

Results of running the declaration engine on real commits before a release, and what they changed. Rerun with
`CorpusValidationTest` (technical specification §12):

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.corpus=<list>
```

Each line of the list is a local Git repository followed by `git rev-list` arguments, for example
`/src/okhttp --no-merges --max-count=40 HEAD`. The report is written to `build/reports/smartdiff-validation/`.

Merge validation uses the same list format. The merge mode adds `--merges` itself, so the list arguments should not add
`--no-merges`:

```shell
./gradlew test --tests '*CorpusValidationTest*' -Psmartdiff.validation.merges=<list>
```

Its report is written to `build/reports/smartdiff-validation/merge/`.

## 2026-11-07: Commit preview of a summarized row off the EDT (2025.1 and 2026.3)

[changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) R19 and issue #126: opening the Commit tool window's preview of a summarized row must not read a committed revision on the UI thread. PR #127 moves the reads into the preview provider's `loadRequest` and replaces the deprecated `FileEditorManager.openFile` source fallback with `OpenFileDescriptor.navigate`.

**Test evidence.** `ChangesTreeInteractionTest` and `ChangesSummaryTest` pass (20 and 19 tests). The new `test opening the Commit preview reads the revisions off the EDT and switches changes` fails on `origin/main` at the first double-click with `AssertionError: revision content is read on the EDT` from `CheckedRevision.getContent` → `ChangesDiff.content` → `ChangesDiff.request` → `PreviewSession.show`. On the branch it passes: both revisions are read on the background load, and switching the preview to a second change reads that change's revisions there too. `test reopening the Commit preview after its tab closed loads it again` covers a viewer disposed by closing the preview tab.

**Manual run.** The IDE was started from the local IC-251.26927.53 and IU-263.7307.200 distributions with the built plugin and a Git project containing a summarized `Cart.java` change, a staged `Order.java` change and a plain `notes.txt` change. A temporary startup probe activated the Commit tool window, waited for the summary, and drove the tree's real double-click handler and Enter listener on the EDT. It logged the handler time and the time until the preview request had a title. The probe was removed before this change was committed.

| Action | IC 2025.1.3, handler / preview | IU 2026.3, handler / preview |
| --- | --- | --- |
| Summarized row, double-click | 388 ms / 495 ms | 772 ms / 1091 ms |
| Plain row, double-click | 140 ms / 207 ms | 65 ms / 203 ms |
| Summarized row, Enter | 129 ms / 193 ms | 288 ms / 426 ms |
| Plain row, Enter | 66 ms / 113 ms | 10 ms / 62 ms |

The summarized preview is slower than the plain one because it opens the plugin's editor tab and loads through its provider; the first 2026.3 preview includes class loading and editor creation. The 2026-11-06 record's about 14 s delay did not reproduce. No `UI was frozen` or slow-operation warning followed the clicks; the post-startup activity logged `EDT responsiveness: ok` on both IDEs. The only severe log entry in the first 2025.1 run was the disposed-viewer failure below.

**Found and fixed while checking Enter.** Closing the preview tab disposes its `DiffRequestProcessor`, so the next preview reused a disposed viewer and failed with `IncorrectOperationException: parent ... SingleRequestProcessor ... has already been disposed` (2025.1). `PreviewSession` now creates a new processor and diff file after disposal. Enter on the summarized row after closing its preview opened the preview again in 193 ms on 2025.1.

**Verifier.** `./gradlew verifyPlugin` is compatible on IC-251.26927.53 and IU-263.7307.200 with 4 experimental API usages (the existing Scala `PsiNamedElementWithCustomPresentation` reference and `ChangesViewNodeAction`) and **no deprecated API usage**; removing `FileEditorManager.openFile` removed the last deprecated usage.

## 2026-11-06: Changes tree manual check on 2025.1 and 2026.3

This run checked #108, #111 and #112 (PR #124) in running IDEs. `./gradlew buildPlugin` produced
`build/distributions/smart-diff-0.2.0.zip`, installed in isolated `idea.plugins.path` directories. The IDEs were
IntelliJ IDEA Community 2025.1.3 (`IC-251.26927.53`) through `runIde`, and IntelliJ IDEA Ultimate 2026.3
(`IU-263.7307.200`) from the `idea-2026.3-aarch64.dmg` in the Gradle cache. The test project was a real Git repository
with an unstaged `Cart.java` change (`class Cart` changed, `method total()` changed, `method isEmpty()` added), a staged
`Order.java` change and an unstaged plain `notes.txt`. The Commit and Local Changes trees showed the summarized rows.

The check used a temporary startup probe and a `DiffExtension` in the built plugin; both were removed before this change
was committed. The probe used `java.awt.Robot` when macOS allowed it (`ROBOT ... moved=true`) and otherwise dispatched
the same Swing mouse and key events to the tree. The desktop was unlocked, but macOS accessibility was inconsistent:
Robot moved the pointer in some launches and not others, and `osascript`/`CGEventPost` from the shell were not permitted.
A Robot click that produced no visible result is recorded as inconclusive, not as a pass.

### #108: declaration double-click path on 2026.3

The programmatic probe invoked the same declaration handler that a double-click reaches. On 2026.3 it took the platform
`ShowDiffAction` path: the temporary `DiffExtension` recorded `request=LocalChangeListDiffRequest outlinePath=class Cart`,
and the outline reported `state=Shown selected=class Cart`. The log had no
`Cannot call the platform's Changes diff action` warning, so the fallback did not run. The same path was seen on 2025.1,
with the same declaration context and no warning.

The physical Robot route is separate. In a 2026.3 launch where Robot moved the pointer, the physical double-click on the
declaration row produced no visible diff. The `pressed count=2 consumed=true` diagnostic and the `open=none` open-files
check came from different launches, so they are not combined as a physical failure. The physical double-click is still
unverified; #108 stays open for a same-launch click-through with the log checked.

### #111: summarized file rows

- **B1, B2 (double-click and Enter): partial.** On 2025.1, the summarized double-click opened
  `Changes[PreviewDiffFile]`, while the plain double-click opened `TabPreviewDiffVirtualFile`: different preview
  surfaces. The 2025.1 summarized Enter opened nothing, while the plain Enter opened its preview. On 2026.3, the
  physical summarized double-click opened an editor preview (a `SimpleDiffRequest`) and the plain row opened its own
  preview; the summarized preview appeared about 14 seconds after the click, after the first 4-second check missed it.
  The 2026.3 preview-off/diff-on Enter runs opened `Cart.java` for both. B1 and B2 are therefore partial, not a
  summary/plain match.
- **B3 (single-click preview).** Not verified. With
  `show.diff.preview.as.editor.tab.with.single.click=true`, a single click opened no preview for the summarized row
  or for the plain row on either IDE. This may be a tool-window mode or focus issue rather than a product difference,
  but the probe cannot tell.
- **B4 (arrow expansion).** Right expanded and Left collapsed the summarized row on both IDEs
  (`right expanded=true`, `left expanded=false`).
- **B5 (declaration rows): inconclusive.** The `DIFF` log lines predate the B5 dispatch and no new diff followed it, so
  they do not show that B5 opened a diff. Retest with every diff closed before the declaration action.
- **Directory and changelist rows.** The default tree grouped by changelist only, so there were no directory rows.
  Enabling directory grouping in the probe caused a tree-UI assertion, and a physical double-click on the changelist
  row left it collapsed (`expanded=false`); neither is a confirmed pass.

B1/B2 are partial, B3, B5 and the directory/changelist rows are unverified, and B4 passes. #111 stays open.

### #112 / PR #124: tree hide and filter

PR #124 was built from its branch (commit `27c6dff`) in the same worktree and checked with a second temporary probe; it
was not merged. The probe used a `Parser.java` change with `method parse() changed` and `method count() added`.

- **M1 (View Options placement): pass.** On 2025.1 and on IU-263.7307.200, `ChangesView.ViewOptions` existed and the
  `SmartDiff.ChangesTree` group was placed in it (`inViewOptions=true`, `inToolbar=false` on both). PR #124's updated
  description now says both IDEs expose View Options and the toolbar fallback is defensive only; this probe agrees.
- **Additional probe observations.** `analysisRevision` stayed `0` while `showChangesSummary` and
  `showChangesDeclarations` were toggled, so the display toggles do not invalidate analysis. The probe's tree dumps did
  not change after the toggles and explicit rebuild calls, so the visible rebuild for M2–M4 was not confirmed. With
  `hiddenChangesTreeChangeTypes={ADDED}`, the `Parser` summary stayed `1 changed · 1 added` with both rows
  (`class Parser`, `method parse() changed`, `method count() added`), so M6/M7 filter and hidden-count behavior were not
  confirmed.
- **Remaining #112 manual checks.** M2–M9 in PR #124's manual-check list remain: toggles rebuild without a comparison,
  Declarations off, Structural Summary off, the Settings page mirror, filter rules, hidden counts/all hidden, separate
  filters and persistence, and speed search.

M1 passes; M2–M9 remain manual. #112 and PR #124 stay open; PR #124 is not merged.

### Manual steps still needed

1. Start 2025.1 with `./gradlew runIde --args=<project>` and 2026.3 with the built zip installed; open the Git project.
2. **#108:** close every diff, expand the summarized Java file in Local Changes and double-click `class Cart` in the
   same launch. Expect the Changes diff with that outline row selected. Check `idea.log` for
   `Cannot call the platform's Changes diff action`.
3. **#111:** repeat with the preview setting on and off, on the summarized row and a plain row: double-click, Enter,
   single click, Right/Left, and the declaration rows with no diff open. Also check a directory row and a changelist row.
4. **#112:** M1's action placement is covered by the probe and PR #124's updated description; the visual eye-icon
   check is still outstanding. On both 2025.1 and 2026.3 click the eye icon and confirm Structural Summary, Declarations
   and Hide Change Types appear below the platform options. Then run M2–M9 from PR #124's manual-check list, including
   the `Parser` summary `1 changed · 1 added · 1 hidden` with `class Parser` still shown and `method count() added` hidden.
## 2026-11-07: Changes tree hide and filter, M2-M9

This run checked PR #124 at head `7a773ad` in both target IDEs with the real Changes tree and a real Git project. The
plugin was built with `./gradlew buildPlugin` and installed in isolated `idea.plugins.path` directories. The IDEs were
IntelliJ IDEA Community 2025.1.3 (`IC-251.26927.53`, `runIde`) and IntelliJ IDEA Ultimate 2026.3 (`IU-263.7307.200`,
installed from the cached `idea-2026.3-aarch64.dmg`).

The fixture had a changed and an added method under `Parser`, a signature change, a method moved between classes and
changed, a removed method, an added method, a formatting-only change, and a plain text file. A temporary startup probe
opened the eye icon and selected the real View Options actions; it was removed before this change was committed. The
Settings-page check used the real `SmartDiffConfigurable` component. The desktop was unlocked. The eye menu itself was
opened and its actions were selected through the popup's `ListPopupStep`; the "Hide Change Types" submenu could not be
opened by the probe, so its child actions were invoked from the same registered action group. No product code changed.

### Why the earlier probe saw no change

The earlier probe set the settings directly and then called `tree.rebuildTree()` immediately after
`notifyChangesTreeSettingsChanged()`. This trace shows that setting the value and publishing `SmartDiffSettings.CHANGED`
alone rebuilds the real tree within about 0.5 s: `SummaryChangeNode.summary` became empty while the node and its rows
stayed. The explicit `tree.rebuildTree()` is the difference in the earlier probe; it re-rendered the model built before
the setting changed and masked the event-driven refresh. The working path is `SmartDiffSettings.CHANGED` ->
`ChangesSummarySettingsListener` -> `refreshChangesView` -> `ChangesViewModifier.TOPIC`.

### M1: View Options

On both IDEs the eye button's action was `ChangesView.ViewOptions`, and the popup contained, in order:

```text
Directory
Module
Ignored Files
Structural Summary
Declarations
Hide Change Types
```

The three SMART Diff entries were below the platform's own options on both IDEs. M1 passes.

### M2 and M4: toggles

Selecting **Structural Summary** turned the grey text off while the declaration rows stayed
(`SummaryChangeNode summary= children=1`); selecting it again restored the text. Selecting **Declarations** removed the
rows (`children=0`) while the summary text stayed; selecting it again restored the rows. The model root changed on each
action and the analysis stamp stayed `0`; the rebuild was immediate and no comparison progress appeared. M2 and M4 pass.

### M3: Declarations off

With Declarations off every summarized file was a leaf (`children=0`) with its summary text still shown; turning it on
restored the rows. The double-click and Enter behavior on the now-leaf row was not re-driven here; it depends on the
open #111 checks. M3 is partial.

### M5: Settings page

`SmartDiffConfigurable` exposed the two checkboxes and the separate "Hide from the Changes tree" group. Clicking
"Show structural summary in the Changes tree" changed `showChangesSummary` (`summaryOff=false`, `summaryOn=true` after
the second click), and the second "Changed" checkbox set `hiddenChangesTreeChangeTypes=[CHANGED]` while
`hiddenChangeTypes` stayed empty. M5 passes at the component level; the Settings dialog itself was not opened.

### M6: filter rules

- **Added:** `Parser` showed `1 changed · 1 added · 1 hidden`, kept `class Parser` and `method parse() changed`, and
  hid `method count() added`; `MovedTarget` showed `1 added · all hidden` with no rows.
- **Changed:** `Signature` showed `1 changed · all hidden` (the signature change is hidden with changed); `Parser`
  kept the shown added child under the label-less `class Parser` container; the `AddRemove` row
  `rename candidate, changed` stayed, as a rename candidate is never hidden; `MovedTwo` stayed because its
  `moved, changed` row still has moved shown.
- **Removed:** `MovedSource` showed `1 removed · all hidden`.
- **Moved:** `MovedTwo` stayed because its `moved, changed` row still has changed shown; this covers "stays while
  either type is shown". Hiding both moved and changed at once was not run.
- **Formatting-only:** `Formatting` showed `2 formatting-only · all hidden`.

The uncertain and unanalyzed never-hidden rows were not produced by this fixture; only the rename-candidate case was
observed. M6 passes for changed, added, removed, moved and formatting-only, with those two cases still manual.

### M7: hidden counts and all hidden

The `Parser` summary was `1 changed · 1 added · 1 hidden` with its full counts. `MovedTarget` and `Formatting` kept
their file rows, had no declaration rows, and ended with `· all hidden`. Commit inclusion of an all-hidden file was not
checked. M7 is partial.

### M8: separate filters and persistence

On 2025.1, hiding `Added` in the tree left `hiddenChangeTypes` empty and persisted across a restart:
`KEEP summary=true declarations=true hidden=[ADDED] outlineHidden=[] revision=0`, with the `Parser` row still
`1 changed · 1 added · 1 hidden`. The 2026.3 restart and the visual outline comparison were not run. M8 is partial.

### M9: speed search

Not driven. Dispatching the search text to the tree selected no row in either the baseline or the filtered/declarations
off states, so this run cannot tell whether the speed search works. M9 remains manual.

### Remaining manual checks

- M3 open behavior on a leaf row, after #111.
- M6 uncertain/unanalyzed rows and hiding moved and changed together.
- M7 commit inclusion of an all-hidden file.
- M8 the 2026.3 restart and the outline visual comparison.
- M9 speed search.

## 2026-11-05: engine 9 relationship benchmark

`DeclarationEngine.VERSION` 9 includes the assignment and container fixes, so the engine-8 confidence counts are not
assumed to carry over. `RelationshipBenchmarkTest` was run over the existing corpus on this branch:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.gate=no-new-wrong-pairs \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json
```

**Result:** 424 cases (304 checked-in cases and 120 real commits), precision 1.000 and recall 0.826, with no wrong or
forced pair; the gate passed. The 89 rename relationships are 69 high (77.5%), 20 medium and 0 low: Java 19 high and 5
medium, Kotlin 18 and 3, Scala 2 17 and 6, Scala 3 15 and 6. The engine-9 counts therefore equal the engine-8 record on
this corpus.

This run is **not** the [relationships/04](specs/relationships/04-rename-grouping.md) R18 first gate: the list has no
usage-grouping cases, so by R9/R18 it is a smoke run for the usage gate. The current harness does not count that category
yet, which is why its own report says complete; the first gate is the 440-case corpus with the 16 usage-grouping
fixtures (320 checked-in cases and 120 real commits), and each fixture adds one high rename, so that gate has 105
renames: 85 high, 20 medium and 0 low. The report is in `build/reports/smartdiff-relationships/`.

## 2026-11-05: Lazy fallback diff and the 2026.3 declaration path

[changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) R16 now opens a lazy `DiffRequestProducer`, so the fallback reads revision content off the UI thread. The test `test the fallback loads revision content off the EDT` in `ChangesSummaryTest` uses a revision stub that fails if content is read on the EDT. Run it with:

```shell
./gradlew test --tests '*ChangesSummaryTest*'
```

**2026.3 sandbox:** the installed `idea-2026.3-aarch64.dmg` from the Gradle cache is IntelliJ IDEA Ultimate `IU-263.7307.200`. `./gradlew buildPlugin` produced `build/distributions/smart-diff-0.2.0.zip`; it was unpacked into an isolated `idea.plugins.path` and started with isolated `idea.config.path`, `idea.system.path` and `idea.log.path`. The log records the plugin:

```text
INFO - #c.i.p.i.b.AppStarter - Loaded custom plugins: SMART Diff (io.github.agadek.smartdiff, 0.2.0)
```

A temporary `com.intellij.postStartupActivity` (removed before the commit) ran on the EDT, built a `SummaryChangeNode` with a `DeclarationNode` for a synthetic change, and called `DeclarationNodeAction.handleDoubleClick`, the same handler a double-click and Enter reach. The log records:

```text
INFO - #io.github.agadek.smartdiff.ui.changes.ValidationProbe - VALIDATION_PROBE declaration double-click shown=true rootChildren=1
INFO - #c.j.p.c.BeforeFileOpenLoggerListener - beforeFileOpened Diff
INFO - #c.j.p.c.BeforeFileOpenLoggerListener - fileOpened Diff
```

There is no `Cannot call the platform's Changes diff action` warning, so the platform `ShowDiffAction` path ran rather than the R16 fallback, and the diff opened. This is a programmatic call of the same handler, not a mouse double-click: UI automation is not permitted in this environment, so a click-through of the tree on a local project is still a manual check. Issue #108 stays open for that check.

## 2026-11-05: Changes tree file rows

[changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) R17 to R20 make a summarized file row open like a
file without a summary. The desktop session was locked, so the check was a temporary startup probe in the real IDE
rather than a physical mouse click; the probe drove the same mouse and key events the platform receives and was removed
before the change was committed.

**2025.1:** `./gradlew runIde` started IntelliJ IDEA Community 2025.1.3 (IC-251.26927.53) with the built plugin. The
probe found the real Changes tree with the plugin's tree setup installed and the platform's
`ChangesViewToolWindowPanel` handler wrapped. Enter on the summarized file row opened the editor diff preview
(`TabPreviewDiffVirtualFile`), and Enter on a declaration row opened the Changes diff.

**2025.1 double-click note:** `ChangesViewToolWindowPanel` calls
`EditSourceOnDoubleClickHandler.isToggleEvent` before its diff handler. On a non-leaf summarized row that returns
true, because `ChangesListView.getToggleClickCount()` is 2, so the platform handler cannot open the diff even when
the tree's own toggle is suppressed. The plugin now opens a summarized row through the platform setting (Changes
diff, editor preview or source) and a declaration row through `DeclarationNodeAction`; the physical double-click on
2025.1 remains for the unlocked-desktop click-through. The probe recorded:

```text
PROBE ready handler=ChangesTreeSetup$ChangesDoubleClickHandler platform=ChangesViewManager$ChangesViewToolWindowPanel$$Lambda
PROBE result events=[STEP file-enter, handler-enter, viewer title=null, viewer title=Cart.java ... [Changes],
  ... STEP declaration-enter, file TabPreviewDiffVirtualFile, viewer ... [Changes], file Diff ChainDiffVirtualFile]
```

**2026.3:** the installed IU-263.7307.200 IDE loaded the same plugin. The probe found the tree with the platform's
`ChangesViewInputHandler` wrapped. Double-click reached that handler for both a summarized file row and a plain file
row, and Enter on a declaration row opened the Changes diff. The locked session created no diff window for either file
row; the plain row behaved the same, so the summarized row uses the same platform path.

## 2026-11-05: Scala plugin version in the engine version

[relationships/02](specs/relationships/02-relationship-model.md) R24 adds the Scala plugin's version to the declaration
engine version, so a Scala plugin update changes the version. The version test used to accept `Scala unknown`, which
passes even when the lookup never finds the Scala plugin's descriptor. The lookup now reads every
`META-INF/plugin.xml` resource on the Scala language's classloader and takes the version of the descriptor whose id is
`org.intellij.scala`, because a test's shared classloader answers the single-resource lookup with another plugin's
descriptor.

**Unit test:** `DeclarationEngineTest` has a descriptor test over a sample Scala `plugin.xml`, a descriptor with another
plugin's id and one without a version, and its live version test now compares the engine version with
`PluginManagerCore.getPlugin(PluginId.getId("org.intellij.scala"))?.version` and fails when the Scala part reads
`unknown`. Before the fix, the shared test classloader gave `8 (251.26927.53; Scala unknown)`; after it, the same test
gives `8 (251.26927.53; Scala 2025.1.32)`. Run with:

```shell
./gradlew test --tests '*DeclarationEngineTest*'
```

**Sandbox check:** `./gradlew runIde` started IntelliJ IDEA Community 2025.1.3 with the built plugin and the pinned
Scala plugin 2025.1.32. A temporary application lifecycle listener logged `DeclarationEngine(null).version` once at
startup and was removed before the change was committed; `idea.log` recorded:

```text
SCALA_VERSION_PROBE 8 (251.26927.53; Scala 2025.1.32)
```

The log is at `.intellijPlatform/sandbox/smart-diff/IC-2025.1.3/log/idea.log`. So the live version is not `unknown`,
and a Scala plugin update changes the engine version.

## 2026-11-04: relationship model migration

[relationships/02](specs/relationships/02-relationship-model.md) moves the facts the declaration engine already reports
into `ComparisonResult.relationships`; the `ChangeNode` rename, move, kind change and extraction fields are computed
from that list. This run proves the move changes nothing the plugin shows and no pair the engine finds.
`DeclarationEngine.VERSION` is 7, and the engine names the Scala plugin's version too, for example
`7 (251.26927.53; Scala 2025.1.32)`.

**Corpus validation:** `CorpusValidationTest` over `corpus.txt` in the clone set (okhttp, guava, commons-lang, Exposed,
moshi, spring-petclinic), the same list as the earlier runs. 1279 files from 6 clones, 97 flagged. The `summary.tsv`
lists the same files, flags and hunk counts as a run of `main`'s code on the same machine, including the 4 extraction
near misses and the 55 rename candidates. `report.md` diagnoses the same files, with one diagnostic line whose
extraction sources follow the outline's order, largest covered share first, rather than the relationship order, and
with the per-file timings. Reports in `build/reports/smartdiff-validation/`.

**Merge validation:** `CorpusValidationTest` over `merges-2026-11-02.txt`: 275 files from 121 merge commits, 2 files
missing from a parent or the base. Flags: 49 `both-sides-clean`, 12 `conflict-one-side`, 6 `status`; coverage Java 50,
Kotlin 67, Scala 2 117 and Scala 3 41 files. The `summary.tsv` lists the same files, flags and hunk counts as a run of
`main`'s code, and `report.md` diagnoses the same files, apart from the timings. The
[2026-11-02 rerun](#2026-11-02-merge-validation-rerun) records 50 `both-sides-clean`: the
one-file difference comes from the later merge-validation commits that judge moved sides and unchanged children, not
from this change, and `main`'s code gives 49 on the same list too. Reports in
`build/reports/smartdiff-validation/merge/`.

**Relationship benchmark, both gates:** the run is the full list,
`src/test/testData/relationships-corpus-full.txt`, against the unchanged
[2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline), 424 cases (304 checked-in cases and 120 real
commits). Both gates pass:

- `no-new-wrong-pairs`: no new wrong or forced pair; there is no wrong or forced pair at all.
- `tolerance=0`: no lower precision or recall for any corpus and kind than the baseline.

Precision is 1.000 and recall 0.826 overall, and every `(corpus, kind)` pair has the same true positives, false
positives, false negatives, precision and recall as the baseline; the 42 missed relationships are the same list. So no
benchmark difference needed an explanation: no MOVE or KIND_CHANGE pair in the corpus has old and new signatures that
differ, and no extraction source changed its name or signature either. The corpus revision is `87588c7829c5`, the
baseline's, because no label or clone changed. The policy is the default, high `0.85` and medium `0.7`.

### Reported relationships by confidence

The benchmark now reads `ComparisonResult.relationships` and counts the reported relationships of each kind by
confidence (relationships/02 R33). The scale comes from the rename similarity; a move, a kind change and an extraction
are high by rule.

| Corpus | Kind | HIGH | MEDIUM | LOW |
| --- | --- | --- | --- | --- |
| Java | RENAME | 18 | 6 | 0 |
| Java | MOVE | 9 | 0 | 0 |
| Java | KIND_CHANGE | 3 | 0 | 0 |
| Java | EXTRACT | 20 | 0 | 0 |
| Java | INLINE | 0 | 0 | 0 |
| Kotlin | RENAME | 18 | 3 | 0 |
| Kotlin | MOVE | 10 | 0 | 0 |
| Kotlin | KIND_CHANGE | 3 | 0 | 0 |
| Kotlin | EXTRACT | 12 | 0 | 0 |
| Kotlin | INLINE | 0 | 0 | 0 |
| Scala 2 | RENAME | 17 | 6 | 0 |
| Scala 2 | MOVE | 11 | 0 | 0 |
| Scala 2 | KIND_CHANGE | 4 | 0 | 0 |
| Scala 2 | EXTRACT | 12 | 0 | 0 |
| Scala 2 | INLINE | 0 | 0 | 0 |
| Scala 3 | RENAME | 15 | 6 | 0 |
| Scala 3 | MOVE | 10 | 0 | 0 |
| Scala 3 | KIND_CHANGE | 4 | 0 | 0 |
| Scala 3 | EXTRACT | 12 | 0 | 0 |
| Scala 3 | INLINE | 0 | 0 | 0 |

**What stays the same:** the golden fixtures, the merge fixtures, `OutlineModelTest` and the Changes tree tests pass with
no fixture change. The `extracted from …` note keeps the source as the matched node shows it, so the note names the same
name and signature as before. The baseline report is not replaced; [relationships/03](specs/relationships/03-relationship-matching.md)
compares against the [2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline).

The report is in `build/reports/smartdiff-relationships/summary.tsv`, `report.md` and `report.json`.

## 2026-11-04: relationship matching

[relationships/03](specs/relationships/03-relationship-matching.md) replaces the greedy rename pass with a scored,
whole-file assignment (R1 to R13). `DeclarationEngine.VERSION` is 8. The rename weights are
`content 0.83, name 0.03, parameter types 0.05, context 0.03, order 0.06`, and the default confidence policy is
`high = 0.82, medium = 0.7`. The 2026-11-05 retune restores the original rename-plus-reorder fixture (`total` reordered,
body changed with content 0.75); the earlier weights left it below the floor, so they were rejected.

### Tuning run

The tuning run scores the tuning split only; all 120 real-commit labels are held-out, so the run has 202 tuning cases.
The 2026-11-02 baseline has 138 labelled pairs on the same split, and this run has the same 138, so the split is the
baseline's. The full 0.70 to 1.00 table is in the pull request; the rows that decide the value are:

| High threshold | High pairs | Correct high | High precision | Coverage |
| --- | --- | --- | --- | --- |
| 0.70 | 57 | 57 | 1.000 | 1.000 |
| 0.71 | 55 | 55 | 1.000 | 0.965 |
| 0.72 | 53 | 53 | 1.000 | 0.930 |
| 0.73 | 51 | 51 | 1.000 | 0.895 |
| 0.74 | 49 | 49 | 1.000 | 0.860 |
| 0.79 | 45 | 45 | 1.000 | 0.789 |
| 0.81 | 45 | 45 | 1.000 | 0.789 |
| **0.82** | **43** | **43** | **1.000** | **0.754** |
| 0.83 | 41 | 41 | 1.000 | 0.719 |
| 0.91 | 33 | 33 | 1.000 | 0.579 |
| 0.97 to 1.00 | 0 | 0 | 1.000 | 0.000 |

`0.82` is the highest value that keeps precision at least 95% and three quarters of the 57 correct tuning renames high;
`0.83` is below three quarters. The value replaces the starting `0.85`.

The weight runs are tuning-only too. The first column is content, name, parameter types, context and order:

| Weights | Tuning RENAME TP/FN | Result |
| --- | --- | --- |
| 0.50, 0.15, 0.15, 0.10, 0.10 | 41/16 | rejected: loses 16 correct renames; the starting table does not pair the signature-change cases |
| 0.80, 0.05, 0.05, 0.05, 0.05 | 49/8 | rejected: 8 rename-with-signature-change cases are below the floor |
| 0.85, 0.05, 0.05, 0.025, 0.025 | 53/4 | rejected: one signature-change case stays below the floor |
| 0.75, 0.05, 0.05, 0.05, 0.10 | 53/4 | rejected: Kotlin and Scala 3 signature-change cases stay below the floor |
| 0.80, 0.04, 0.04, 0.04, 0.08 | 55/2 | rejected: the Kotlin signature-change cases stay below the floor |
| 0.82, 0.03, 0.03, 0.04, 0.08 | 57/0 | rejected: keeps the tuning renames but the original content-0.75 fixture stays below the floor |
| 0.82, 0.04, 0.04, 0.03, 0.07 | 57/0 | rejected: the original content-0.75 fixture stays below the floor |
| 0.90, 0.02, 0.02, 0.02, 0.04 | 57/0 | rejected: pairs an unrelated removed and added property in the corpus validation |
| **0.83, 0.03, 0.05, 0.03, 0.06** | **57/0** | **chosen**: every tuning rename, the original content-0.75 fixture, and no changed corpus pair outside the intended two files |

### Relationship benchmark, both gates

The run is the full list, `src/test/testData/relationships-corpus-full.txt`, against the unchanged
[2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline), 424 cases (304 checked-in cases and 120 real
commits). Both gates pass:

- `no-new-wrong-pairs`: no wrong or forced pair; there is no wrong or forced pair at all.
- `tolerance=0`: no lower precision or recall for any corpus and kind than the baseline.

Precision is 1.000 and recall 0.826 overall. Every `(corpus, kind)` row has the same true positives, false positives,
false negatives, precision and recall as the baseline; the 42 missed relationships are the same list. So the assignment
adds no new correct pair and loses none on this corpus. A low-confidence relationship is not scored: the run has none
on this corpus, and the benchmark test checks that a low relationship is counted only in the confidence table.

| Corpus | Kind | TP | FP | FN | Precision | Recall |
| --- | --- | --- | --- | --- | --- | --- |
| Java | RENAME | 24 | 0 | 1 | 1.000 | 0.960 |
| Java | MOVE | 9 | 0 | 0 | 1.000 | 1.000 |
| Java | KIND_CHANGE | 3 | 0 | 0 | 1.000 | 1.000 |
| Java | EXTRACT | 20 | 0 | 14 | 1.000 | 0.588 |
| Java | INLINE | 0 | 0 | 3 | n/a | 0.000 |
| Kotlin | RENAME | 21 | 0 | 2 | 1.000 | 0.913 |
| Kotlin | MOVE | 10 | 0 | 0 | 1.000 | 1.000 |
| Kotlin | KIND_CHANGE | 3 | 0 | 0 | 1.000 | 1.000 |
| Kotlin | EXTRACT | 12 | 0 | 4 | 1.000 | 0.750 |
| Kotlin | INLINE | 0 | 0 | 3 | n/a | 0.000 |
| Scala 2 | RENAME | 23 | 0 | 0 | 1.000 | 1.000 |
| Scala 2 | MOVE | 11 | 0 | 3 | 1.000 | 0.786 |
| Scala 2 | KIND_CHANGE | 4 | 0 | 0 | 1.000 | 1.000 |
| Scala 2 | EXTRACT | 12 | 0 | 3 | 1.000 | 0.800 |
| Scala 2 | INLINE | 0 | 0 | 3 | n/a | 0.000 |
| Scala 3 | RENAME | 21 | 0 | 0 | 1.000 | 1.000 |
| Scala 3 | MOVE | 10 | 0 | 0 | 1.000 | 1.000 |
| Scala 3 | KIND_CHANGE | 4 | 0 | 0 | 1.000 | 1.000 |
| Scala 3 | EXTRACT | 12 | 0 | 3 | 1.000 | 0.800 |
| Scala 3 | INLINE | 0 | 0 | 3 | n/a | 0.000 |

The confidence counts move one Java rename from medium to high because of the lower high threshold; every other row is
the same as the [2026-11-04 relationship model run](#reported-relationships-by-confidence). The report is in
`build/reports/smartdiff-relationships/summary.tsv`, `report.md` and `report.json`.

### Corpus validation

`CorpusValidationTest` over `two-sided-2026-11-04.txt`: 1970 files from 386 commits, 1968 `ANALYZED` and 2 `PARTIAL`,
no fallback, no `hidden` or `empty` flag. 125 files are flagged, with the same counts as the
[2026-11-04 two-sided rerun](#2026-11-04-two-sided-corpus-rerun) except `rename` 26 (was 29) and `rename-candidate`
61 (was 59). Comparing every `summary.tsv` row with that run, six fields changed in three rows, on two files:

| File | Commit | Change | Review |
| --- | --- | --- | --- |
| guava `android/guava-tests/test/com/google/common/collect/AbstractBiMapTest.java` | `67b6ad17db` | `testIdentityKeySetIteratorRemove` → `testEntryNotAccessedAfterKeySetIteratorRemove` and `testIdentityEntrySetIteratorRemove` → `testEntryNotAccessedAfterEntrySetIteratorRemove` are now rename candidates instead of removed and added; reported 15 → 13; flag `rename` → `rename-candidate` | right: the same two tests were renamed and their bodies changed from `IdentityHashMap` to the new `InvalidatingEntryMap` |
| guava `guava-tests/test/com/google/common/collect/AbstractBiMapTest.java` | `67b6ad17db` | the same change in the second copy | right |
| spring-petclinic `src/test/java/org/springframework/samples/petclinic/owner/PetControllerTests.java` | `37c7f98bf1` | `testProcessCreationFormWithBlankName` → `processCreationFormWithBlankName` is now a rename candidate instead of removed and added; reported 11 → 10; flags `rename,rename-candidate` → `rename-candidate` | right: the method was renamed by dropping the `test` prefix and its body is unchanged |

No other row changed its status, hunks, reported count, formatting-only count, flags or dialect. The rename notes on
the remaining rows are the recorded ones; no wrong row or pair was found.

**Time:** the overall median is 7 ms and the 95th percentile 74 ms, against 7 ms and 80 ms recorded. The slowest file
takes 572 ms (zio `ZIO.scala`), against 599 ms. All 1970 files take 41.0 s, against 43.0 s. By dialect, the median is
4 ms for Java and Kotlin, 23 ms for Scala 2 and 16 ms for Scala 3; the slowest file in every group is below the 5 s
analysis limit (140 ms, 572 ms and 352 ms).

### Merge validation

`CorpusValidationTest` over `merges-2026-11-02.txt`: 275 files from 121 merge commits, 2 files missing from a parent
or the base. Flags: 49 `both-sides-clean`, 12 `conflict-one-side`, 6 `status`; coverage Java 50, Kotlin 67, Scala 2
117 and Scala 3 41 files. The `summary.tsv` matches the
[2026-11-04 relationship model run](#2026-11-04-relationship-model-migration): the same files, flags, hunk counts and
diagnostics, apart from the timings (merge median 29 ms, the same as the recorded 29 ms). The one-file difference from the
[2026-11-02 rerun](#2026-11-02-merge-validation-rerun), `commons-lang` `StringEscapeUtilsTest.java` not being
`both-sides-clean`, is the one that section already explains: later merge-validation commits judge moved sides and
unchanged children, and the fix is not part of the matcher. Reports in `build/reports/smartdiff-validation/merge/`.

**Golden fixtures:** `GoldenFixturesTest`, `MergeFixturesTest`, `OutlineModelTest` and the Changes tree tests pass with
no fixture change, so no golden row changed (R17). The high-confidence rename keeps the rename-candidate mark, as
decided, so the outline does not change.

The corpus reports are in `build/reports/smartdiff-validation/summary.tsv` and `report.md`; the relationship benchmark
reports are in `build/reports/smartdiff-relationships/summary.tsv`, `report.md` and `report.json`.

## 2026-11-04: two-sided corpus rerun

This is the full two-sided run the 0.2.0 release gate in [RELEASING.md](RELEASING.md) step 3 asks for. It supersedes the
[partial rerun](#2026-11-04-two-sided-corpus-partial-rerun) below: it adds zio, which that run lacked, and records every
clone's commit.

**Corpus:** the [Java and Kotlin list](#2026-10-22-before-010) and the [Scala list](#2026-10-22-scala), with every clone's
HEAD pinned so the run can be replayed. `CorpusValidationTest` appends `-- *.java *.kt *.scala` to each line, so the lines
carry no pathspec; that is also why the Scala lines take the last 40 or 80 commits touching one of the three languages and
then compare their modified files (145 of the recorded 160 had one). `/src/...` stands for the clone location on the
machine running the validation:

```text
/src/okhttp --no-merges --max-count=40 2b6399483639405e9277d33a3a1d355d5fabda1c
/src/guava --no-merges --max-count=40 b157643c41f9ee6a38732b4fa1a590ba187912d9
/src/commons-lang --no-merges --max-count=40 b9b30efb01e861a16503232bb96b684107e73199
/src/Exposed --no-merges --max-count=40 09f814c61c22851b76840ccb65c1ac2c4db97452
/src/moshi --no-merges --max-count=40 889013ec2edb8d8034902662a1dc8c4f3b3f8111
/src/spring-petclinic --no-merges --max-count=40 500158f732419217507c7656904b8e6aa1bcc0d6
/src/cats --no-merges --max-count=40 e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca
/src/zio --no-merges --max-count=80 df4c5b29ddaa1df8a7ffe122cd5b5d783b60c706
/src/scala3 --no-merges --max-count=40 3e003afbf3854d9b3189a51a47258d0b9e31e792
```

| Clone | HEAD | Selected commits | With a modified file | Files |
| --- | --- | --- | --- | --- |
| okhttp | `2b6399483639405e9277d33a3a1d355d5fabda1c` | 40 | 37 | 159 |
| guava | `b157643c41f9ee6a38732b4fa1a590ba187912d9` | 40 | 40 | 297 |
| commons-lang | `b9b30efb01e861a16503232bb96b684107e73199` | 40 | 39 | 75 |
| Exposed | `09f814c61c22851b76840ccb65c1ac2c4db97452` | 40 | 39 | 273 |
| moshi | `889013ec2edb8d8034902662a1dc8c4f3b3f8111` | 40 | 40 | 301 |
| spring-petclinic | `500158f732419217507c7656904b8e6aa1bcc0d6` | 40 | 39 | 174 |
| cats | `e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca` | 40 | 40 | 307 |
| zio | `df4c5b29ddaa1df8a7ffe122cd5b5d783b60c706` | 80 | 72 | 127 |
| scala3 | `3e003afbf3854d9b3189a51a47258d0b9e31e792` | 40 | 40 | 257 |

The six Java and Kotlin clones are at the commits the [2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline)
records and give the [2026-10-23 run's](#2026-10-23-extraction-detection) 234 commits and 1279 files. cats is at its
recorded head and gives 307 files. zio and scala3 take a different window than the one recorded on 2026-10-22, whose heads
were not recorded: zio gives 80 selected commits, 72 with a modified file and 127 files, against 118 files recorded;
scala3 at the commit the [2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline) pins gives 257 files, against
171. The zio clone is shallow at depth 1000 (`git rev-list --count HEAD` is 1000), and all 80 selected commits have their
parent in that history, so no commit was skipped for a missing parent. The 8 selected commits without a modified file
contribute no rows because the harness compares modified files only. So the Java and Kotlin half is the recorded set and
the Scala half is not; the comparison below says which counts can be compared.

**Versions:** engine `smartdiff.declarations` 7, named `7 (251.26927.53; Scala 2025.1.32)`; IDE 2025.1.3, build
`251.26927.53`; Scala plugin 2025.1.32; JVM 21.0.9; macOS 15.7.7 on arm64.

**Result:** 1970 files from 386 commits: 1279 Java and Kotlin (97 flagged), 307 cats (2), 127 zio (13) and 257 scala3
(13). 1968 are `ANALYZED` and 2 `PARTIAL`; none fell back to the native diff. No file has `hidden` or `empty`: every
difference lies inside a reported change, and every changed text has one. 125 files are flagged:

| Flag | Java and Kotlin | Scala | All |
| --- | --- | --- | --- |
| `extraction` | 10 | 5 | 15 |
| `extraction-near-miss` | 4 | 4 | 8 |
| `noisy` | 12 | 11 | 23 |
| `rename` | 21 | 8 | 29 |
| `rename-candidate` | 55 | 4 | 59 |
| `same-name` | 1 | 1 | 2 |
| `status` (`PARTIAL`) | 0 | 2 | 2 |
| `uncertain` | 1 | 0 | 1 |
| **Flagged files** | **97** | **28** | **125** |

The 15 extraction files link 19 added declarations: 14 in the Java and Kotlin half and 5 in zio. The 8 near-miss files are
the recorded rows with unchanged shares: guava `Maps.java` (both copies) `lastEntry` ← `descendingEntryIterator` 0.56/0.90,
Exposed `EntityCache.kt` `trimToFirst` ← `maxEntitiesToStore` 0.54/0.89, moshi `-MoshiKotlinTypesExtensions.kt`
`subtypeOf`/`supertypeOf` 0.55/0.94, and zio `package.scala` `make` ← `live` 0.58/0.98, `ZTestLogger.scala` `apply` ← `make`
0.63/1.00, `Annotations.scala` `make` ← `live` 0.85/0.64 and `TestRandom.scala` `make` ← `make` 0.82/0.67.

**Comparison with the 2026-10-23 run:** the Java and Kotlin half reproduces it exactly. It has the same 1279 files and 97
flagged files, and the same count for every flag: `extraction` 10, `extraction-near-miss` 4, `noisy` 12, `rename` 21,
`rename-candidate` 55, `same-name` 1 and `uncertain` 1. The 10 extraction files and 4 near-miss files are the recorded
ones, and the recorded review of those flags still applies. The Scala half cannot be compared file by file, because the
zio and scala3 windows still differ (zio has 127 files against 118, scala3 257 against 171); its 691 files include 28
flagged files, all in the recorded kinds:

| Scala flag | 2026-10-23 (596 files) | 2026-11-04 (691 files) |
| --- | --- | --- |
| `status` | 1 | 2 |
| `noisy` | 5 | 11 |
| `rename` | 9 | 8 |
| `rename-candidate` | 4 | 4 |
| `same-name` | 0 | 1 |
| `extraction` | 8 | 5 |
| `extraction-near-miss` | 4 | 4 |
| **Flagged files** | **29** | **28** |

No flag kind is new. The 28 Scala files were each reviewed:

| File | Commit | Flags | Review |
| --- | --- | --- | --- |
| cats `core/src/main/scala/cats/FlatMap.scala` | `3a2a2304` | `rename-candidate` | right: `FoldableFlatMap` → `AbstractFoldableFlatMap`; recorded |
| cats `core/src/main/scala/cats/NonEmptyReducible.scala` | `503d4887` | `noisy` | right: the class split into a trait and its members moved; recorded |
| zio `test/shared/src/main/scala/zio/test/TestAspect.scala` | `948a3d50` | `noisy` | right: five GraalVM aspects added |
| zio `core/shared/src/main/scala/zio/FiberRefs.scala` | `fda16f73` | `rename` | right prompt: `needsTransformWhenForked` → `needTransformWhenForked`, rewritten; recorded judgement call |
| zio `test/shared/src/main/scala/zio/test/package.scala` | `b1bd5e47` | `extraction-near-miss` | recorded: `make` ← `live` 0.58/0.98 |
| zio `core/shared/src/main/scala/zio/Supervisor.scala` | `76820f7f` | `extraction` | right: `fibersIn` extracted; recorded |
| zio `test/shared/src/main/scala/zio/test/ZTestLogger.scala` | `76820f7f` | `extraction-near-miss` | recorded: `apply` ← `make` 0.63/1.00 |
| zio `test/shared/src/main/scala/zio/test/Annotations.scala` | `da2aab5c` | `extraction-near-miss` | recorded: `make` ← `live` 0.85/0.64 |
| zio `test/shared/src/main/scala/zio/test/TestClock.scala` | `da2aab5c` | `extraction` | right: `make` extracted from `live` |
| zio `test/shared/src/main/scala/zio/test/TestConsole.scala` | `da2aab5c` | `extraction` | right: `make` extracted from `make` |
| zio `test/shared/src/main/scala/zio/test/TestRandom.scala` | `da2aab5c` | `extraction-near-miss` | recorded: `make` ← `make` 0.82/0.67 |
| zio `specs/snippet-check/showcase.scala` | `b7d39ee9` | `rename`, `rename-candidate`, `noisy` | right: rewritten; recorded |
| zio `core/shared/src/main/scala/zio/Queue.scala` | `efd715d7` | `extraction` | right: `offerToTaker` extracted from `tryOffer` |
| zio `core/jvm-native/src/main/scala/zio/internal/OneShot.scala` | `0ed7e33d` | `extraction` | right: `tryGet(Long)` extracted from `get(Long)`, which now delegates to it |
| zio `concurrent/src/main/scala/zio/concurrent/CyclicBarrier.scala` | `01ec1b45` | `rename` | prompt only: rewritten on promises and a semaphore, no rename; recorded as right: rewritten |
| scala3 `compiler/src/dotty/tools/dotc/config/ScalaSettings.scala` | `a3a50265` | `noisy` | explained limit: the abstract class became a type alias and is not paired, so its members show as removed |
| scala3 `compiler/src/dotty/tools/dotc/config/Settings.scala` | `a3a50265` | `rename` | prompt only: dead code removed and helpers added; no rename is shown |
| scala3 `tests/neg/i7746.scala` | `878f4dba` | `status` | `PARTIAL`: an intentionally invalid negative test |
| scala3 `compiler/src/dotty/tools/dotc/classpath/ClassPathFactory.scala` | `32e78d4f` | `same-name` | explained limit: the class became an object and is not paired; `sourcesInPath` and `classesInPath` gained parameters |
| scala3 `library/src/scala/collection/immutable/LazyListIterable.scala` | `d708ca17` | `status` | `PARTIAL`: capture-set syntax `Function0[_]^{this}` |
| scala3 `compiler/src/dotty/tools/dotc/core/Definitions.scala` | `82f931c0` | `rename`, `noisy` | right: `NullMarkedAnnots` → `NullMarkedAnnot`, `NullUnmarkedAnnots` → `NullUnmarkedAnnot`, `KotlinMetadataAnnot` added |
| scala3 `compiler/src/dotty/tools/dotc/core/ImplicitNullInterop.scala` | `82f931c0` | `rename` | right: `ownerNullMarking` replaced by `nullMarking`, with helpers added |
| scala3 `compiler/src/dotty/tools/dotc/core/classfile/ClassfileConstants.scala` | `82f931c0` | `noisy` | right: 14 constants added |
| scala3 `tests/explicit-nulls/neg/nullmarked/J.java` | `82f931c0` | `rename`, `rename-candidate`, `noisy` | right: the test was rewritten; `innerGet` → `get` is a rename candidate |
| scala3 `tests/explicit-nulls/neg/nullmarked/S.scala` | `82f931c0` | `rename`, `rename-candidate`, `noisy` | right: the test was rewritten; `a`/`b`/`c` → `j5`/`j6`/`j7` are rename candidates |
| scala3 `tests/explicit-nulls/neg/nullmarked-separate/S_3.scala` | `477509b0` | `noisy` | right: five test functions added |
| scala3 `tests/explicit-nulls/neg/nullmarked/S.scala` | `477509b0` | `noisy` | right: five test functions added |
| scala3 `compiler/src/dotty/tools/dotc/util/MutableSet.scala` | `0033a28d` | `noisy` | right: six implementations added |

Two `rename` prompts do not correspond to a rename: `Settings.scala` (dead code removed and helpers added) and
`CyclicBarrier.scala` (rewritten). The `rename` flag is the coarse removed-plus-added prompt, not a claim in the outline,
and neither file shows a rename pair; the recorded Java and Kotlin review describes most of its 21 `rename` files the same
way. The other `rename` flags name real replacements, and the shown rename candidates were checked. No wrong row or pair
was found in the reviewed files.

**Time:** the median is 4 ms for Java and Kotlin, the recorded value, and 20 ms for Scala, against 22.5 ms with extraction
detection in the recorded run. The overall median is 7 ms and the 95th percentile 81 ms. The slowest file takes 599 ms
(zio `ZIO.scala`), against 545 ms recorded. All 1279 Java and Kotlin files take 10.7 s, against 10.1 s, and all 1970 files
take 43.0 s; the recorded Scala half alone, on a different window, took 27.8 s.

**Gate:** all files were analyzed, or `PARTIAL` in 2 cases for a Scala plugin parse gap; none fell back, none broke the
nothing-hidden rule, and no changed text was left unreported. Every flagged file was reviewed: no wrong row or pair was
found, and the two `rename` prompts that do not name a rename are the coarse heuristic described above. Both 0.2.0 gates
in [RELEASING.md](RELEASING.md) step 3 are therefore met: this section records the corpus validation, and the
[2026-11-04 relationship model run](#2026-11-04-relationship-model-migration) records the relationship benchmark with both
of its gates passing. 0.2.0 was released on 2026-11-05; the tag and GitHub release are published with the signed plugin attached.
Marketplace publishing remains off.

The report is in `build/reports/smartdiff-validation/summary.tsv` and `report.md`.

## 2026-11-04: two-sided corpus partial rerun

**Corpus:** the six Java and Kotlin clones from the [2026-10-23 extraction run](#2026-10-23-extraction-detection) at the
same commits, plus cats and scala3 at their current heads. The zio clone is not available on this machine, so this is
only a partial rerun: the Java and Kotlin half is the recorded set, the Scala half is not (scala3 has moved since the
recorded run, and zio's 118 files are missing).

**Java and Kotlin:** 1279 files, all `ANALYZED`, the same count as the recorded run. 10 files have links, matching the
recorded count, and the 4 non-zio near misses are the recorded guava, Exposed and moshi rows. The other flags have the
same counts as the [0.1.0 noise pass](#2026-10-22-010-noise-pass): `rename` 21, `rename-candidate` 55, `noisy` 12,
`same-name` 1 and `uncertain` 1. The median is 5 ms against the recorded 4 ms, the slowest file takes 168 ms and all
1279 files take 10.7 s, close to the recorded 9.9 to 10.1 s.

**Scala:** cats has 307 files, the recorded count; scala3 has 257 against 171 recorded, because its head moved after the
2026-10-23 run. 562 of the 564 files are `ANALYZED` and 2 `PARTIAL`. No extraction is flagged on these heads; the 8
recorded Scala links include zio and scala3 commits outside the current list, so this subset cannot be compared with the
recorded result and says nothing about the missing zio files.

**Result:** the Java and Kotlin half reproduces the recorded run; the Scala half is not comparable, and the full corpus,
zio included, is still required. This partial run does not close the 0.2.0 release gate.

## 2026-11-02: relationship benchmark baseline

This is the R29 baseline: the first run with no shortfall. It covers the full case matrix and 30 labelled real
commits per corpus, so it is a complete run, not the [2026-11-01 smoke run](#2026-11-01-relationship-benchmark)
below.

This baseline was re-recorded after the #68 adapter fix (#79): the `replicateA_` move is no longer wrong, and Scala 2
MOVE went from 10/1/4 to 11/0/3.

**Corpus:** `src/test/testData/relationships-corpus-full.txt` lists all 304 checked-in cases and the real commits.
The cases are 25 categories with 3 cases for each of Java, Kotlin, Scala 2 and Scala 3, plus the four starter cases
(300 + 4); Scala 2 and Scala 3 stay separate rows. The real-commit lines name the clones by relative path, so the list
contains no user name; on another machine the `/src/...` prefix stands for the clone location, as in the older
validations:

```text
/src/commons-lang --no-walk <17 commit hashes>
/src/guava --no-walk <3 commit hashes>
/src/moshi --no-walk <8 commit hashes>
/src/okhttp --no-walk <5 commit hashes>
/src/spring-petclinic --no-walk <9 commit hashes>
/src/Exposed --no-walk <18 commit hashes>
/src/cats --no-walk <30 commit hashes>
/src/scala3 --no-walk <30 commit hashes>
```

The hashes are pinned with `--no-walk`, so the run does not depend on each clone's current branch tip. Every selected
commit changes one Java, Kotlin or Scala file, and every one of its files has a reviewed label. The clones were at:

| Clone | HEAD |
| --- | --- |
| commons-lang | `b9b30efb01e861a16503232bb96b684107e73199` |
| guava | `b157643c41f9ee6a38732b4fa1a590ba187912d9` |
| moshi | `889013ec2edb8d8034902662a1dc8c4f3b3f8111` |
| okhttp | `2b6399483639405e9277d33a3a1d355d5fabda1c` |
| spring-petclinic | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| Exposed | `09f814c61c22851b76840ccb65c1ac2c4db97452` |
| cats | `e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca` |
| scala3 | `3e003afbf3854d9b3189a51a47258d0b9e31e792` |

**Versions:** engine `smartdiff.declarations` 6; IDE 2025.1.3, build `251.26927.53`; Scala plugin 2025.1.32; JVM
21.0.9; macOS 15.7.7 on arm64. Corpus revision `87588c7829c5`; gate `no-new-wrong-pairs`, with no earlier baseline
because this is the first complete run. The gate passed. The corpus revision hashes the list and every case, label and
clone HEAD it reaches, so it changes when a label changes.

**Result:** 424 cases (304 checked-in cases and 120 real commits); 30 labelled commits per corpus. No case was
unlabelled or skipped, none broke the nothing-hidden rule, 6 fell back to the native diff (the malformed pairs), and
no pair was wrong or forced. Precision is 1.000 and recall 0.826 overall. The 42 missed relationships are listed in
`report.md`; they are the known limitations below, not a shortfall.

### Per corpus

| Corpus | Cases | Precision | Recall | Wrong share | Presence | Content | Moved | Signature | Kind | Median | p95 | Slowest |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Java | 106 | 1.000 | 0.757 | 0.000 | 0.995 | 0.995 | 1.000 | 1.000 | 1.000 | 1 ms | 34 ms | 122 ms |
| Kotlin | 106 | 1.000 | 0.836 | 0.000 | 0.986 | 0.986 | 1.000 | 1.000 | 1.000 | 0 ms | 29 ms | 85 ms |
| Scala 2 | 106 | 1.000 | 0.847 | 0.000 | 1.000 | 0.986 | 0.824 | 1.000 | 1.000 | 0 ms | 55 ms | 209 ms |
| Scala 3 | 106 | 1.000 | 0.887 | 0.000 | 1.000 | 0.970 | 1.000 | 1.000 | 1.000 | 0 ms | 146 ms | 273 ms |
| tuning | 202 | 1.000 | 0.884 | 0.000 | 1.000 | 0.990 | 1.000 | 1.000 | 1.000 | 0 ms | 1 ms | 70 ms |
| held-out | 222 | 1.000 | 0.748 | 0.000 | 0.991 | 0.980 | 0.864 | 1.000 | 1.000 | 2 ms | 91 ms | 273 ms |

The case counts in the original table were recorded as 153 tuning and 271 held-out; that was wrong. The labels mark 202
checked-in cases tuning and 222 held-out (102 checked-in plus the 120 real-commit labels). The labelled-pair counts
(138 tuning and 103 held-out) and every metric above are unchanged. No held-out case was used for tuning.

### Per kind

A precision or recall with nothing to divide is `n/a`, not `1.000`; it means the engine reported no pair of that kind,
not a measured perfect score.

| Corpus | Kind | TP | FP | FN | Reported | Precision | Recall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Java | RENAME | 24 | 0 | 1 | 24 | 1.000 | 0.960 |
| Java | MOVE | 9 | 0 | 0 | 9 | 1.000 | 1.000 |
| Java | KIND_CHANGE | 3 | 0 | 0 | 3 | 1.000 | 1.000 |
| Java | EXTRACT | 20 | 0 | 14 | 20 | 1.000 | 0.588 |
| Java | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Kotlin | RENAME | 21 | 0 | 2 | 21 | 1.000 | 0.913 |
| Kotlin | MOVE | 10 | 0 | 0 | 10 | 1.000 | 1.000 |
| Kotlin | KIND_CHANGE | 3 | 0 | 0 | 3 | 1.000 | 1.000 |
| Kotlin | EXTRACT | 12 | 0 | 4 | 12 | 1.000 | 0.750 |
| Kotlin | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Scala 2 | RENAME | 23 | 0 | 0 | 23 | 1.000 | 1.000 |
| Scala 2 | MOVE | 11 | 0 | 3 | 11 | 1.000 | 0.786 |
| Scala 2 | KIND_CHANGE | 4 | 0 | 0 | 4 | 1.000 | 1.000 |
| Scala 2 | EXTRACT | 12 | 0 | 3 | 12 | 1.000 | 0.800 |
| Scala 2 | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |
| Scala 3 | RENAME | 21 | 0 | 0 | 21 | 1.000 | 1.000 |
| Scala 3 | MOVE | 10 | 0 | 0 | 10 | 1.000 | 1.000 |
| Scala 3 | KIND_CHANGE | 4 | 0 | 0 | 4 | 1.000 | 1.000 |
| Scala 3 | EXTRACT | 12 | 0 | 3 | 12 | 1.000 | 0.800 |
| Scala 3 | INLINE | 0 | 0 | 3 | 0 | n/a | 0.000 |

**Wrong and forced pairs:** none. The adapter now names a move's old container from the old side's declaration tree,
so the Scala 2 `cats-0a776c7-applicative` move of `replicateA_` matches its label and the former cross-container move
limitation is fixed.

**Known limitations:** inlining detection is not implemented, so every INLINE label is missed and its recall starts
at zero (relationships/05). Extraction to several sources links only one source in the synthetic case (24 misses over
four corpora), and the Scala parser marks the two capture-checking real-commit files and the incomplete Scala
parse-error cases differently from the hand labels (UNANALYZED versus CHANGED); those are listed in `report.md`, not
counted as shortfalls.

**Baseline report:** `src/test/testData/relationships-baseline/report.json` is this run's `report.json`, checked in so
later gates compare against a file (relationships/01 R21, R29, R40). Its corpus revision is `87588c7829c5`.

**Report:** `build/reports/smartdiff-relationships/summary.tsv`, `report.md` and `report.json`. Run it with:

```shell
./gradlew test --tests '*RelationshipBenchmarkTest*' \
  -Psmartdiff.relationships.corpus=src/test/testData/relationships-corpus-full.txt \
  -Psmartdiff.relationships.baseline=src/test/testData/relationships-baseline/report.json
```

The [2026-11-01 smoke run](#2026-11-01-relationship-benchmark) below is kept as the harness check; it is not this
baseline.

## 2026-11-01: relationship benchmark

**Corpus:** the four starter cases in `src/test/testData/{java,kotlin,scala2,scala3}/relationships/`:
Java method rename, Kotlin method move, Scala 2 formatting-only and Scala 3 kind change. This is a **smoke run** of
the harness, not the full labelled matrix or real-commit baseline that relationships/03 needs.

The run reports `"smoke": true` because it is below the R30 minimums (3 cases per category and corpus, 30 labelled
commits per corpus). It is therefore not the R29 baseline: a later run refuses it with `baseline is a smoke run`, and
the baseline is the first run with no shortfall.

**Result:** precision 1.000 and recall 1.000 over the three relationship kinds; declaration classification
7 of 7; no forced pair, no nothing-hidden violation and no fallback. Median 23 ms, p95 and slowest 72 ms.
The gate was `no-new-wrong-pairs`; this is the first recorded run because no earlier report exists.

The report is written to `build/reports/smartdiff-relationships/`. The checked-in corpus still needed the full
category matrix and real-commit labels at the time of this run; the [2026-11-02 baseline](#2026-11-02-relationship-benchmark-baseline)
above adds them and is the R29 baseline, so relationships/01 is implemented from that run.

## 2026-11-01: merge validation

**Corpus:** merge commits from the Java and Kotlin clones and from the Scala clones added for this run, selected with
`git rev-list` arguments. The `/src/...` paths stand for the local clone location; use the clone paths on the machine
running the validation.

```text
/src/commons-lang --max-count=240 HEAD -- *.java
/src/moshi --max-count=80 HEAD -- *.java *.kt
/src/spring-petclinic --max-count=85 HEAD -- *.java
/src/guava --max-count=1 HEAD -- *.java
/src/Exposed --max-count=15 HEAD -- *.kt
/src/cats --max-count=100 HEAD -- *.scala
/src/scala3 --max-count=100 HEAD -- *.scala
```

**Result:** 275 files from 121 merge commits; 2 files were skipped because they were missing from the base or a parent,
and no merge commit had more than two parents or lacked a merge base. No file had a `join-mismatch`. The flags were
50 `both-sides-clean`, 17 `conflict-one-side` and 6 `status`; the report lists the conflicts and outline of every
flagged file.

| Language | Files | Minimum |
| --- | --- | --- |
| Java | 50 | 30 |
| Kotlin | 67 | 30 |
| Scala 2 | 117 | 30 |
| Scala 3 | 41 | 30 |

**Time:** the median was 33 ms for both comparisons together and 23 ms for one base-side comparison, so the merge
outline is within the twice-the-side limit. The two merge comparisons run in parallel, so "both comparisons together"
is wall time, not their sum.

**Ready to ship:** no `join-mismatch` and the time ratio pass. The 67 `both-sides-clean`/`conflict-one-side` files still
need review or an explanation ([review issue #54](https://github.com/agadek/smart-diff/issues/54)), so the merge outline
is not ready to ship from this run yet. The [2026-11-02 rerun](#2026-11-02-merge-validation-rerun) reviews these flags
and records the verdicts.

## 2026-11-02: merge validation rerun

The [2026-11-01 run](#2026-11-01-merge-validation) left 67 files to review. This run replays the same merge commits on the engine that will ship in 0.2.0, gives each flagged file one verdict, and records the validation fixes the review needed.

**Corpus:** the 2026-11-01 list with each clone's `HEAD` replaced by the commit below. The 2026-11-01 run did not record its clone commits, so the commit lists of the two runs cannot be compared; the file and merge commit counts match exactly. The `/src/...` paths stand for the local clones; use the clone paths on the machine running the validation.

```text
/src/commons-lang --max-count=240 b9b30efb01e861a16503232bb96b684107e73199 -- *.java
/src/moshi --max-count=80 889013ec2edb8d8034902662a1dc8c4f3b3f8111 -- *.java *.kt
/src/spring-petclinic --max-count=85 500158f732419217507c7656904b8e6aa1bcc0d6 -- *.java
/src/guava --max-count=1 b157643c41f9ee6a38732b4fa1a590ba187912d9 -- *.java
/src/Exposed --max-count=15 09f814c61c22851b76840ccb65c1ac2c4db97452 -- *.kt
/src/cats --max-count=100 e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca -- *.scala
/src/scala3 --max-count=100 3e003afbf3854d9b3189a51a47258d0b9e31e792 -- *.scala
```

| Clone | Commit on 2026-11-02 |
| --- | --- |
| commons-lang | `b9b30efb01e861a16503232bb96b684107e73199` |
| moshi | `889013ec2edb8d8034902662a1dc8c4f3b3f8111` |
| spring-petclinic | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| guava | `b157643c41f9ee6a38732b4fa1a590ba187912d9` |
| Exposed | `09f814c61c22851b76840ccb65c1ac2c4db97452` |
| cats | `e28985fe2a0d7123a8fb84cbb70c72b5e3d935ca` |
| scala3 | `3e003afbf3854d9b3189a51a47258d0b9e31e792` |

**Result:** 275 files from 121 merge commits, as in the first run; no merge commit had more than two parents or lacked a merge base, and 2 files were skipped for a missing side. No file had `join-mismatch` or `hidden`.

| Flag | 2026-11-01 | 2026-11-02 | Change |
| --- | --- | --- | --- |
| `both-sides-clean` | 50 | 49 | −1, the R8 fix below |
| `conflict-one-side` | 17 | 12 | −5, the R29 fixes below |
| `status` | 6 | 6 | none |
| `join-mismatch` | 0 | 0 | none |

The 2026-11-01 run predates the #50 fix (#58). Replaying the same commits with it changed none of these counts, so none of the flagged files was that pattern.

**R29 fix, `conflict-one-side`:** the first 2026-11-02 run had 17 `conflict-one-side` flags. Five of them came from a container whose own source changed for a real reason while the conflicting declaration under it was changed on both sides; the flag judged the container and not the declaration that holds the conflict. Each of the five containers had changed its own non-whitespace source, so declarations/02 R8 held: `class StrLookup` gained a `//----` separator comment, `class StringUtils` lost the javadoc's `@version` line, `class SpringTransactionManager` changed its constructor to `private val _dataSource`, `class Entity` changed its type parameter bound to `Comparable<ID>`, and `trait SetInstances` gained `import SetInstances._`. The check now judges the part of the conflict in the declaration's own source outside its children, so a hunk that only starts in the blank line above a child belongs to the child, and a hunk that covers a container's own header is the container's (#69). `MergeValidationTest` has a case for each. The rerun after the fixes still has 12: the five are no longer flagged, and the #69 case is already inside a file flagged for another declaration.

**R29 fix, `both-sides-clean`:** `StringEscapeUtilsTest` was flagged with a formatting-only left side. R8 counts a declaration changed beyond formatting, so the check now ignores a side that is only formatting-only. The file is no longer flagged (50 → 49), and `MergeValidationTest` has a case for it (#67).

**Time:** the median was 29 ms for both comparisons together and 23 ms for one base-side comparison, a ratio of 1.26, against 33 ms and 23 ms in the first run. Within the twice-the-side limit (R17).

**Flagged files:** 60 files, 61 flag rows. Every `both-sides-clean` and `conflict-one-side` file gets one row, and a file with both flags gets one row per flag (`DMLTests.kt`).

### Expected

| File | Merge commit | Flag | Verdict | Reason |
| --- | --- | --- | --- | --- |
| `commons-lang` `src/main/java/org/apache/commons/lang3/ClassUtils.java` | `bd7956949e` | `both-sides-clean` | expected | left renamed a caught exception, right added the method's `@throws` javadoc |
| `commons-lang` `src/main/java/org/apache/commons/lang3/reflect/MethodUtils.java` | `494dfc9e6b` | `both-sides-clean` | expected | left replaced `Validate.notNull`, right added the method's `@throws` javadoc |
| `commons-lang` `src/main/java/org/apache/commons/lang3/text/WordUtils.java` | `9604c85306` | `both-sides-clean` | expected | left removed the class javadoc's `@version` line, right added `containsAllWords` with its javadoc |
| `moshi` `moshi/src/main/java/com/squareup/moshi/ClassJsonAdapter.java` | `afb82cb3e8` | `both-sides-clean` | expected | both sides changed the `FACTORY` adapter body in different places |
| `moshi` `kotlin/codegen/src/main/java/com/squareup/moshi/kotlin/codegen/TypeRenderer.kt` | `a8102bccd2` | `both-sides-clean` | expected | both sides changed `render(TypeName)` in different places |
| `moshi` `adapters/src/main/java/com/squareup/moshi/adapters/RuntimeJsonAdapterFactory.java` | `1896e0f118` | `both-sides-clean` | expected | left rewrote the class javadoc, right made the class public |
| `moshi` `kotlin/src/main/java/com/squareup/moshi/kotlin/KotlinJsonAdapter.kt` | `98c4358615` | `both-sides-clean` | expected | left changed the name handling, right moved the null check |
| `moshi` `moshi/src/main/java/com/squareup/moshi/JsonWriter.java` | `707f33226c` | `both-sides-clean` | expected | both sides changed `name(String)` in different places |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/SQLiteDialect.kt` | `b71568b295` | `both-sides-clean` | expected | left moved the function, right changed its signature |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/dao/Entity.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed the two `setValue` overrides and `flushInserts` |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Queries.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `insertAndGetId` in different places |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Table.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `nullable()` in different places |
| `Exposed` `src/test/kotlin/org/jetbrains/exposed/sql/tests/shared/DMLTests.kt` | `e179d32bf1` | `both-sides-clean` | expected | both sides changed `testGroupBy03`, `testGroupBy06` and `testInsert03` |
| `cats` `core/src/main/scala/cats/data/Ior.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/data/Validated.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/either.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/list.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/map.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/option.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/queue.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/seq.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/sortedMap.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/try.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/instances/vector.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `core/src/main/scala/cats/package.scala` | `bc21eb0484` | `both-sides-clean` | expected | left added the `as`, `tuple`, `fproduct` and `unzip` overrides, right changed the mixin list |
| `cats` `free/src/test/scala/cats/free/FreeSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `kernel-laws/shared/src/test/scala/cats/kernel/laws/LawTests.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/EitherSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/FunctionSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedReaderWriterStateTSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedStateTSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/NestedSuite.scala` | `e4086ed60e` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/AsSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/IndexedStateTSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `tests/shared/src/test/scala/cats/tests/NestedSuite.scala` | `6bf8d456b0` | `both-sides-clean` | expected | left changed the class-level imports to `*`, right changed code in the class body |
| `cats` `core/src/main/scala-2.13+/cats/instances/arraySeq.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/data/Chain.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/list.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/queue.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/seq.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `core/src/main/scala/cats/instances/vector.scala` | `bfda65b225` | `both-sides-clean` | expected | left changed `with` to `&` in the mixin lists, right renamed `traverse_` to `traverseVoid` |
| `cats` `tests/shared/src/test/scala/cats/tests/ApplicativeSuite.scala` | `a7c5ee9afe` | `both-sides-clean` | expected | left added the `replicateA_` test, right added the by-name ops test |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Typer.scala` | `b873e5a76c` | `both-sides-clean` | expected | both sides changed the same methods in different places |
| `scala3` `compiler/src/dotty/tools/dotc/transform/PostTyper.scala` | `b6a95b255f` | `both-sides-clean` | expected | left changed the GADT constraint handling, right added the inline-trait and specialized checks |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Typer.scala` | `980d135836` | `both-sides-clean` | expected | both sides changed the same methods in different places |
| `scala3` `tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala` | `9521ae1b0b` | `both-sides-clean` | expected | each side added and removed different list entries |
| `scala3` `tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala` | `1adb568dbb` | `both-sides-clean` | expected | each side added and removed different list entries |
| `scala3` `compiler/src/dotty/tools/dotc/core/SymDenotations.scala` | `0de561343f` | `both-sides-clean` | expected | left rewrote the exception handling, right added the `BadSymbolicReference` handling |
| `scala3` `compiler/src/dotty/tools/dotc/cc/Setup.scala` | `54bf0412cb` | `both-sides-clean` | expected | left added `initialVariance` and `addVar`, right added `copyAnnots` |
| `commons-lang` `src/test/java/org/apache/commons/lang3/StreamsTest.java` | `ee87df8472` | `conflict-one-side` | expected | left added `testToArray` above `asIntConsumer`, right changed its signature; the hunk starts at the method's first line |
| `Exposed` `exposed/src/main/kotlin/org/jetbrains/exposed/sql/Database.kt` | `a93cb8443c` | `conflict-one-side` | expected | right added `useNestedTransactions` above `metadata`, left changed its signature |
| `Exposed` `exposed/src/main/kotlin/org/jetbrains/exposed/sql/transactions/ThreadLocalTransactionManager.kt` | `7ca1c6bf39` | `conflict-one-side` | expected | left removed the two logging-exception functions, right reformatted them |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/statements/DeleteStatement.kt` | `b71568b295` | `conflict-one-side` | expected | left removed the blank line above `where`, right changed its signature |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/Default.kt` | `b71568b295` | `conflict-one-side` | expected | left removed `insert` and `replace`, right changed `delete` next to them; the hunk covers the three adjacent declarations |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/vendors/Mysql.kt` | `b71568b295` | `conflict-one-side` | expected | left moved `replace`, `insert` and `delete` to `MysqlFunctionProvider`, right changed `delete` in place |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/sql/Op.kt` | `e179d32bf1` | `conflict-one-side` | expected | left reformatted the declarations, right changed them |
| `Exposed` `src/test/kotlin/org/jetbrains/exposed/sql/tests/shared/DMLTests.kt` | `e179d32bf1` | `conflict-one-side` | expected | left changed `testInsert03`, right reformatted it |
| `cats` `core/src/main/scala/cats/syntax/apply.scala` | `1889e48805` | `conflict-one-side` | expected | left changed the trait's supertype, right changed its first member |
| `cats` `project/TupleBifunctorInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |
| `cats` `project/TupleBitraverseInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |
| `cats` `project/TupleMonadInstancesBoiler.scala` | `531b51a386` | `conflict-one-side` | expected | left rewrote the `content` template, right re-indented the same lines |

### Wrong flag

These six files were reviewed on the first 2026-11-02 run, before the R29 fixes. In the final rerun none of the five `conflict-one-side` flags is raised; `Entity.kt` keeps only `both-sides-clean`. No issue was opened: a wrong flag is fixed in the validation (R29), not explained as a known limit.

| File | Merge commit | Flag | Verdict | Reason |
| --- | --- | --- | --- | --- |
| `commons-lang` `src/main/java/org/apache/commons/lang3/text/StrLookup.java` | `80644cdab9` | `conflict-one-side` | wrong flag | the conflict is in `systemPropertiesLookup`, changed on both sides; the flag came from `class StrLookup`, which gained a `//----` separator comment on the left (R6) |
| `commons-lang` `src/main/java/org/apache/commons/lang3/StringUtils.java` | `9604c85306` | `conflict-one-side` | wrong flag | the conflict is in `containsAny(CharSequence, CharSequence...)`, changed on both sides; the flag came from `class StringUtils`, whose javadoc lost the `@version $Id$` line on the left (R6) |
| `Exposed` `spring-transaction/src/main/kotlin/org/jetbrains/exposed/spring/SpringTransactionManager.kt` | `7ca1c6bf39` | `conflict-one-side` | wrong flag | the conflicts are in `newTransaction` and `initTransaction`, changed on both sides; the flag came from `class SpringTransactionManager`, whose own header changed the constructor from `dataSource: DataSource` to `private val _dataSource: DataSource` on the left |
| `Exposed` `src/main/kotlin/org/jetbrains/exposed/dao/Entity.kt` | `e179d32bf1` | `conflict-one-side` | wrong flag | the conflicts are in the two `setValue` overrides, changed on both sides; the flag came from `class Entity`, whose own header changed the type parameter bound from `Entity<ID:Any>` to `Entity<ID:Comparable<ID>>` on the right |
| `cats` `alleycats-core/src/main/scala/alleycats/std/set.scala` | `78af8a7d66` | `conflict-one-side` | wrong flag | the conflict is in `alleyCatsStdSetMonad`, changed on both sides; the flag came from `trait SetInstances`, which gained `import SetInstances._` on the left |
| `commons-lang` `src/test/java/org/apache/commons/lang3/StringEscapeUtilsTest.java` | `1a256d3e74` | `both-sides-clean` | wrong flag | the left only removed blank lines, so the outline's `formatting-only` is right; the flag counted that side as changed (R8). The check now ignores it and the file is no longer flagged (#67) |

### Fixed and known limit

No file has the verdict `fixed`: the review found no wrong outline. No file has the verdict `known limit`, and no issue is open for this run.

### Fallbacks

The six `status` files are all `PARTIAL`: some source in a Scala 3 file could not be parsed and was left unanalyzed, which is the specified fallback. No comparison threw, so the report says only `not ready` for the review and not `not ready: comparison threw in <file>` (R32).

| File | Merge commit | Side | Status | Reason |
| --- | --- | --- | --- | --- |
| `scala3` `compiler/src/dotty/tools/dotc/cc/Capability.scala` | `7744b8720d` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/mutable/LongMap.scala` | `14f423944c` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/caps/package.scala` | `9521ae1b0b` | left | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/Iterator.scala` | `9521ae1b0b` | left | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/collection/Iterator.scala` | `9521ae1b0b` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `library/src/scala/NamedTuple.scala` | `955de873ba` | right | partial | some source could not be parsed and was not analyzed |
| `scala3` `compiler/src/dotty/tools/dotc/typer/Implicits.scala` | `7c845272aa` | left | partial | some source could not be parsed and was not analyzed |

### Ready to ship

| Check | Result |
| --- | --- |
| R15: no file has `join-mismatch` | yes |
| R16: every `both-sides-clean` and `conflict-one-side` file is reviewed and fixed or explained | yes |
| R17: the median is at most twice the side comparison | yes, 29 ms against 23 ms |
| R32: no `status` file fell back because a comparison threw | yes |

## 2026-10-23: extraction detection

**Corpus:** the same Java and Kotlin list as the [0.1.0 noise pass](#2026-10-22-010-noise-pass) (1279 files from 234
commits) and the same [Scala](#2026-10-22-scala) list (596 files from 145 commits). The report gains two flags,
`extraction` for a file with a link and `extraction-near-miss` for a link that a covered or removed share from 0.5 up to
the threshold would make ([extraction detection](EXTRACTION-DETECTION.md) §8).

**First run, with the specified values:** 8 Java and Kotlin files had links, all right, but moshi `AdapterGenerator`
was missed. Its `generateFromJsonFun` body of about 375 lines moved whole into `generateFromJsonRegular`, and tiling
that compared every token pair of the two needed more than `MAX_WORK` on its own, so detection was skipped for the
file. Tiling now indexes the source's runs of `MIN_TILE` tokens (§4.6). A comparison of both on random token
sequences found the old tiling also took runs of 4 tokens, one short of `MIN_TILE`, when no longer run was left;
the indexed tiling takes the same tiles otherwise, so the covered shares below are slightly lower than in the first
run. The near misses showed real extractions below `MIN_SHARE`: commons-lang `containsTypeVariables(Type)` into
`containsTypeVariables(Type, Set<Type>)` and okhttp `EchOnFakeNetworkTest`, a third test sharing
`handshakerWithUpdatedEchConfigList`. Of the near misses under 0.65 only the moshi pairs below are real, and reaching them at 0.55 would also link the wrong guava and Exposed pairs. `MIN_SHARE` is therefore
0.65; the other values stay as specified.

**Thresholds:** `MIN_TOKENS` 10, `MIN_TILE` 5, `MIN_SHARE` 0.65, `MIN_REMOVED` 0.75, `MAX_WORK` 2 × 10^7 token
comparisons.

**Result:** 10 Java and Kotlin files and 8 Scala files have links, 24 added declarations in all, moshi
`generateFromJsonRegular` among them; the two extractions of the `noisy-change` fixture are linked too. Each link was checked against its
outline: the target's name and body size, the source and the old range the code came from. None is wrong. Typical
cases are test setup moved into helpers (okhttp `EchOnFakeNetworkTest`, `FakeNetworkOkHttpTest`, spring-petclinic
`ValidatorTests`), one helper taken from several sources (okhttp `handshakerWithUpdatedEchConfigList` from three
tests, commons-lang `getPackageName` from both `toFullyQualified` methods), an overload that takes over the body
(commons-lang `isAssignable`, zio `Supervisor.fibersIn`), and small predicates pulled out of a large method (scala3
`RefChecks.checkAllOverrides`, `TryCatchPatterns.isCatchCase`).

8 near misses remain (7 rows, because the guava row covers both copies of `Maps.java`):

| File | Near miss | Covered | Removed |
| --- | --- | --- | --- |
| guava `Maps.java` (both copies) | `lastEntry` ← `descendingEntryIterator` | 0.56 | 0.90 |
| Exposed `EntityCache.kt` | `trimToFirst` ← `maxEntitiesToStore` | 0.54 | 0.89 |
| moshi `-MoshiKotlinTypesExtensions.kt` | `subtypeOf` ← `subtypeOf`, `supertypeOf` ← `supertypeOf` | 0.55 | 0.94 |
| zio `test/package.scala` | `make` ← `live` | 0.58 | 0.98 |
| zio `ZTestLogger.scala` | `apply` ← `make` | 0.63 | 1.00 |
| zio `Annotations.scala` | `make` ← `live` | 0.85 | 0.64 |
| zio `TestRandom.scala` | `make` ← `make` | 0.82 | 0.67 |

The moshi pairs are the one real extraction left out: the no-argument `subtypeOf()` and `supertypeOf()` now call new
`KType` overloads, but most of each overload's body is new code. The crossed pairs in the first run, `subtypeOf` from
`supertypeOf`, show why a lower threshold would link the wrong source. The zio `make` ← `live` cases in `Annotations`
and `TestRandom` kept the code in place, so the removed share stops them as intended.

**Time:** the median stays at 4 ms for Java and Kotlin, against 4 ms in the noise pass run. The slowest file takes
145 ms (guava `Maps.java`, 155 ms before), and no file among the ten slowest is more than 20% slower in both of two
runs. All 1279 files together take 10.1 s and 9.9 s in two runs, against 9.4 s before, about 5 to 7% more, with 2%
between the two runs alone. The quadratic tiling of the first run had pushed the slowest file to 272 ms (guava
`LocalCache.java`), which now takes 54 ms. For Scala, main without extraction detection, run twice on the same base, took a median
of 24 and 23 ms, a slowest file of 578 and 605 ms (zio `ZIO.scala`) and 29.1 and 28.4 s in all; with it, 22.5 ms,
545 ms and 27.8 s. The difference is within the noise between runs. The 19 ms of the Scala run below predates two
Scala fixes on main.

## 2026-10-22: Scala

**Corpus:** the last non-merge commits touching Scala in three projects, with the Scala plugin 2025.1.32:
typelevel/cats (Scala 2 syntax, 40 commits), zio/zio (cross-built for Scala 2 and 3, 80 commits) and scala/scala3
(the compiler, written in Scala 3 with indentation syntax, and its tests, 40 commits). 145 of the 160 commits modified
a Scala file: 596 files (cats 307, zio 118, scala3 171). The summary now records the dialect each file was parsed in.

**Overall:** 536 files were `ANALYZED` and 60 `PARTIAL`; none fell back to the native diff. The median analysis time
was 19 ms, against 4 ms for Java and Kotlin, since the dialect is chosen by parsing both sides first; the slowest file
took 542 ms (zio `ZStream.scala`, 6300 lines). No file broke the nothing-hidden rule and none had a difference with
nothing reported. Every file, cats and zio included, took the Scala 3 dialect: the Scala 3 parser accepts their Scala 2
syntax, and no file used a construct Scala 3 dropped.

79 files were flagged for review:

| Flag | Files |
| --- | --- |
| `status` (`PARTIAL`) | 60 |
| `noisy` | 15, 8 of them `PARTIAL` |
| `rename` | 10 |
| `rename-candidate` | 4 |
| `same-name` | 1 |

### Wrong: Scaladoc markup counted as a parse error

50 of the 60 `PARTIAL` files (cats 18, zio 2, scala3 30) parse without a single error in code. Their errors are the
Scaladoc parser's: wiki links that close before they open (`[[F[A]]]` in prose, `` `[[Ior.Left Left]][A]` ``), links
that cross tags, and an unbalanced `{{{ … }}}` code block. The provider counts every `PsiErrorElement`, so each declaration whose doc
comment has one is `UNANALYZED`, changed or not: cats `Alternative.scala` shows the untouched `separateFoldable` as
unanalyzed, and the 177-line `package object cats` is unanalyzed for a two-line change to one of its properties. The
same errors are counted for both dialects, so they never changed the dialect.

### Wrong: valid Scala 3 the Scala plugin cannot parse

The other 10 `PARTIAL` files are scala3 compiler sources that compile, but where the Scala plugin's parser reports
errors: `this.match` used as a selector, a leading infix operator (`|| sym.owner == …`) continuing a condition on the
next line, `for (orig, forwarded) <- …` with a tuple pattern, `then""` without a space, `.tpe: @unchecked match`, a
trailing comma after a definition, and an interpolated string ending in `}$debugInfo"`. The provider cannot do better
than the PSI, but each such error makes its whole declaration unanalyzed: `Scanners.scala` shows the 1445-line class
`Scanner` as unanalyzed for a one-line change elsewhere.

Across all 60 files, 163 declaration rows are unanalyzed, many of them unchanged on both sides.

### Noisy: a trait turned into a class

cats "Reduce jar size by using abstract classes" changes `private trait RepresentableMonad` into `abstract private
class RepresentableMonad`, and the same in `Func.scala` and `IndexedReaderWriterStateT.scala`. A kind change is a
removed and an added declaration (§6.8), so each one-line change shows the container removed, the new one added and
every member as moved; in `IndexedReaderWriterStateT.scala` the unchanged `implicit def F` even shows as removed and
added, since other containers in the file have members named `F` too.

### Checked against `git show`

22 flagged files across all three repositories:

| File | Outline | Verdict |
| --- | --- | --- |
| cats `Alternative.scala` | unchanged `separateFoldable` unanalyzed | wrong: Scaladoc |
| cats `package.scala` | `package object cats` unanalyzed | wrong: Scaladoc |
| zio `ZStream.scala` | unchanged declarations unanalyzed | wrong: Scaladoc |
| scala3 `Parsers.scala` | `object trim` and its members unanalyzed | wrong: Scaladoc |
| scala3 `SymDenotations.scala` | class `ClassDenotation` unanalyzed | wrong: parser (leading `\|\|`) |
| scala3 `Namer.scala` | declarations unanalyzed | wrong: parser (`for (a, b) <-`) |
| cats `Representable.scala` | traits removed, classes added, members moved | noisy: kind change |
| cats `Func.scala` | `AppFuncApplicative` removed and added | noisy: kind change |
| cats `IndexedReaderWriterStateT.scala` | `IRWSTFunctor` removed and added, `F` removed and added | noisy: kind change |
| cats `FlatMap.scala` | `AbstractFoldableFlatMap` rename candidate from `FoldableFlatMap` | right (besides one Scaladoc row) |
| cats `NonEmptyReducible.scala` | members moved into the new `NonEmptyReducibleTrait` | right |
| cats `NonEmptyList.scala` | a doc example's spacing shows as changed | right: comment text |
| zio `FiberRefs.scala` | `needsTransformWhenForked` removed, `needTransformWhenForked` added | judgement call: renamed and rewritten |
| zio `CyclicBarrier.scala` | most members removed and added | right: rewritten |
| zio `showcase.scala` | declarations removed and added | right: rewritten |
| scala3 `AggregateClassPath.scala` | `searchLocations` rename candidate from `asURLs` | judgement call: one method replaced another |
| scala3 `ClassPath.scala` and four siblings | `asURLs` removed, `searchLocations` added | right |
| scala3 `FileWriters.scala` | `openOptions` rename candidate from `fastOpenOptions` | right |
| scala3 `ScaladocConfigs0.scala` | `def dottyExternalMapping` removed, `val dottyExternalMapping` added | judgement call: kind change |
| scala3 `ScaladocGeneration.scala` | 219 lines of declarations removed | right |
| scala3 `api.scala` | new enum, class and object added | right |
| scala3 `CustomReflectSelectableTestScala3.scala` | tests added | right |

### Whitespace

34 files had whitespace-only changes. 31 came out formatting-only, all in one cats reformatting commit; the other 3
changed whitespace inside comments, which is a comment change, as for Java and Kotlin. Since every file took Scala 3,
Scala 2 code also runs under `INDENTATION_SIGNIFICANT`, so re-indenting it would be a content change; no commit in the
corpus did that.

### What to fix

1. Ignore parse errors inside Scaladoc comments, both for `hasParseError` and in the dialect choice; Scaladoc markup
   is not code. This removes 50 of the 60 `PARTIAL` results.
2. Treat a declaration whose own tokens are identical on both sides as `UNCHANGED` even when it has a parse error,
   so a parser gap only marks code that changed (§6.7). This removes the unanalyzed rows for unchanged declarations.
3. Pair a trait and a class (and a `def` and a `val`) with the same name in the same container as a kind change of
   one declaration, rather than a removed and an added one, so their members match in place.
4. Consider choosing Scala 2 for code that parses in both dialects but has no Scala 3 syntax, so re-indenting Scala 2
   code stays formatting-only.
5. Recheck the 10 parser-gap files with newer Scala plugin releases; report the constructs to the Scala plugin if
   they still fail.

Items 1, 2, 3 and 4 shipped in 0.1.1; item 5 remains a check for later Scala plugin releases. The text above and below
records the run before those fixes.

**After fixes 1, 2 and 4** (Scaladoc errors ignored, identical declarations unchanged despite parse errors, Scala 2 for
code without Scala 3 syntax), on the same corpus: 595 files `ANALYZED` and 1 `PARTIAL` (scala3 `Implicits.scala`,
where the changed declaration itself has a parser gap), against 536 and 60; 1 unanalyzed row, against 163. All 307
cats files now take Scala 2, zio 109 of 118 (the other 9 are in `scala-3/` source directories or use top-level
definitions) and scala3 17 of 171, 433 in all. 21 files are flagged, against 79: `rename` 10, `noisy` 7,
`rename-candidate` 4, `same-name` 1, `status` 1; the trait-to-class files remain. The median time was 23 ms and the
slowest file took 619 ms (zio `ZStream.scala`), since files without Scala 3 syntax are now parsed in both dialects.

**After the kind-change fix** (fix 3, rule M4b), on the same corpus and with extraction detection in both runs: 29 files
are flagged, against 33 with the rule turned off: `rename` 9 (10), `noisy` 5 (7), `same-name` 0 (1), the extraction
flags unchanged (8 and 4), `rename-candidate` 4 and `status` 1. The three cats trait-to-class files are clean:
`Representable.scala` shows `trait → class RepresentableMonad` and `RepresentableBimonad` and nothing else (11
reported rows before, 2 now), `Func.scala` shows `trait → class AppFuncApplicative` with its members in place (9 to 4),
and in `IndexedReaderWriterStateT.scala` `class → trait IRWSTFunctor` keeps its unchanged `implicit def F` unreported,
while the `F` really added to `RWSTAlternative` stays added (9 to 5). scala3 `ScaladocConfigs0.scala` shows its two
`def` → `val` changes as `function → property dottyExternalMapping` and `javaExternalMapping` (15 to 13 rows); the
rest of that file is a real rewrite. Every file is still `ANALYZED` but scala3 `Implicits.scala`; the median time was
22 ms.

## 2026-10-22: 0.1.0 noise pass

**Corpus:** the same 234 commits and 1279 files as before 0.1.0, from the same clones.

**Overall:** every file was `ANALYZED`. The median analysis time was 4 ms and the slowest file took 158 ms (okhttp
`CallTest.kt`). The nothing-hidden check is now exact and about linear for local changes, so it ran on all 1279 files
(865 before): none broke the rule and none had a difference with nothing reported.

84 files were flagged for review, against 100 before 0.1.0:

| Flag | Before the noise pass | After the noise pass (0.1.0) |
| --- | --- | --- |
| `rename` (removed + added that look like a rename) | 70 | 21 |
| `uncertain` | 7 overload sets, plus the M4 cases fixed for 0.1.0 | 1 |
| `rename-candidate` (new) | | 55 |
| `noisy` | 16 | 12 |
| `same-name` | 1 | 1 |

### Rename candidates

55 files have rename candidates. Of the 15 checked against `git show` across all six repositories, 14 were right and
one was a judgement call: moshi `AdapterGenerator` split `generateToJsonFun` into `generateToJson` and
`generateToJsonRegular`, and the candidate follows the bulk of the body to `generateToJsonRegular`. The guava
`entryIterator` → `internalEntryIterator` commit and the spring-petclinic `testFoo()` → `foo()` commit now read as
renames. Sibling tests with the same body are told apart by their names; one petclinic test,
`testProcessCreationFormWithBlankName`, still is not, because its siblings' names are about as close.

The 21 files still flagged `rename` are mostly real rewrites (okhttp `EchTest.kt`, commons-lang
`LockingVisitorsTest`), declarations too small to compare by content (guava `abstract int distinctElements();`, the
empty `reset()` → `clear()` of the fixtures), renames with heavily changed bodies (guava `TempFileCreator`
`createTempFile` overrides) and one-to-two splits the margin leaves alone (Exposed
`minimumPluginConfigurationsForDatabaseApplied` → `…Applied01` and `…Applied02`).

### Overloads

The commons-lang `TypeUtils`, moshi `JsonAdapter` and Exposed `Queries.kt` overload sets now pair as signature
changes. The one file still uncertain is moshi `JvmDescriptors.kt`, where one extension property,
`Executable.jvmMethodSignature`, became two, `Constructor<*>.jvmMethodSignature` and `Method.jvmMethodSignature`: a
real ambiguity.

### Labels

Labels keep a space between words (`Class<out Annotation>`, `vararg Class<out Annotation>`) and show a Kotlin receiver
before the name (`Cart.isFull()`).

## 2026-10-22: before 0.1.0

**Corpus:** the last 40 non-merge commits touching Java or Kotlin in square/okhttp, google/guava, apache/commons-lang,
JetBrains/Exposed, square/moshi and spring-projects/spring-petclinic: 234 commits, 1279 modified files.

**Overall:** every file was `ANALYZED`; no file fell back to the native diff. The median analysis time was 5 ms and the
slowest file took 179 ms (okhttp `CallTest.kt`), far inside the 5 s timeout. No checked file broke the nothing-hidden
rule and none had a difference with nothing reported. The nothing-hidden check aligns the texts character by
character, so it ran only on files where the product of the two lengths is at most 4 × 10^8: 865 of the 1279 files.
The 414 larger files were analyzed but not checked.

100 files were flagged for review. Grouped by cause:

### Wrong: unchanged declarations reported as uncertain

Rule M4 makes every declaration whose (kind, path, name) key occurs more than once on a side uncertain, even when
the declarations are identical on both sides. The outline then shows untouched code as uncertain, often with a dozen
candidates each.

| Case | Example |
| --- | --- |
| Several unnamed declarations of one kind in a container | commons-lang `ClassUtils`: four unchanged static initializers, uncertain in both commits that touched the file |
| Equal signature keys for different declarations | guava `ImmutableSortedMultiset`: twelve `of(…)` overloads appear twice (the deprecated copies have the same parameter types), 24 uncertain nodes for a change that only adds a `serialVersionUID` field |
| Kotlin `vararg` dropped from the signature (§6.6) | moshi `Moshi.kt`: `adapter(Type, Class<out Annotation>)` and `adapter(Type, vararg Class<out Annotation>)` share the key `Type,Class<outAnnotation>`, so they are uncertain in all 7 commits that touched the file, including one that only changed imports |
| Same-signature members in one container | Exposed `References.kt`: two unchanged `orderBy(Pair<Expression<*>,SortOrder>)` |

Proposed fix, before the release: pair same-key declarations whose text is identical, then pair the rest of a
same-key group in source order when both sides have the same number of them; keep `...` for a Kotlin `vararg`
parameter, as for Java.

### Noisy: overload sets whose signatures all changed

When every overload of a name changes signature at once, rule M3 cannot pair them, so all of them are uncertain
with each other as candidates. Examples: commons-lang `TypeUtils` (each `isAssignable(…, Map)` gained a `boolean`
parameter), moshi `JsonAdapter` (`T?` → `T` on three `toJson` overloads), Exposed `Queries.kt` (`batchInsert` gained
a parameter). This is the conservative behaviour ADR 0003 asks for, but a pairing by parameter-list similarity and
position would resolve most of them.

### Noisy: renames reported literally

70 files: a renamed method, field or class is reported as removed + added, as the first release intends (Q2). Most are
at the same lines with the same or nearly the same body, for example a guava commit renaming `entryIterator` and
`distinctElements` across 13 files and a spring-petclinic commit renaming test methods `testFoo()` to `foo()` in 10
files. This is the strongest case for [rename candidates](ROADMAP.md#rename-candidates).

### Noisy: extractions

moshi `AdapterGenerator`: the body of `generateFromJsonFun` moved into a new `generateFromJsonRegular`, shown as a
changed shell plus an added method, as [extraction detection](ROADMAP.md#extraction-detection) describes. Linked since
[extraction detection](#2026-10-23-extraction-detection).

### Labels hard to read

Removing all whitespace from signatures (§6.6) also removes it from the labels: `Class<outAnnotation>`,
`Multiset<?extendsE>`. A Kotlin extension function without parameters reads `formatIsoDate(Date.)`. Proposed fix:
collapse whitespace to one space where it separates words, and show the receiver before the name
(`Date.formatIsoDate()`).

### Not a problem

- `noisy` (16 files): every case was a large addition of new tests or classes, correctly reported as added.
- `same-name` (1 file): okhttp `FakeSocket.kt`, where `close()` was removed from two classes and added to a new one;
  correct.
