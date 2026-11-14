# SMART Diff Changelog

## [Unreleased]

### Added

- Groovy provider (M2 part 1): a dedicated structural outline for Groovy files when the Groovy plugin is installed, including `.groovy`, `.gvy` and `.gradle` scripts. Classes, interfaces, traits, enums, annotation types and records are declarations, as are methods, constructors, fields, properties, enum constants and class initializers; script-level methods and classes are top-level declarations, while top-level statements stay in the file region. Whitespace inside a line is formatting-only, and joining or splitting lines inside a declaration is a change. Build-script top-level blocks stay in the file region until M2b.
- Structure-view fallback: files whose language has a structure view but no dedicated provider show a structural outline built from the IDE's structure view (on by default, Settings | Tools | SMART Diff, *Use the IDE's structure view for other languages*). Such outlines are marked `· structure view`, use the generic `ITEM` kind where needed, treat whitespace as significant, and fall back to the native diff if the structure view cannot be read. Dedicated providers still take precedence.
- With a row selected in the structural outline, the mouse wheel over the outline steps to the next or previous change and shows it in the editors.
- JSON, including JSON with comments and JSON5: each object member is shown as a key, with nested keys as members, reordered keys as moved, reformatted files as formatting-only, and a renamed key with the same value as a rename candidate. Arrays of objects with a shared identifying key (`name`, `id` or `key`) show each item as a key named `[name=web]`, so reordered items move instead of changing the array. The structural outline, Changes tree and merge outline work for JSON files when the IDE has JSON support.
- YAML: each mapping entry is shown as a key, with nested mappings as members, reordered keys as moved, and a renamed key with the same value as a rename candidate. A multi-document file shows each document as a section, and sequences of mappings with a shared identifying key (`name`, `id` or `key`) show each item as a key named `[name=web]`. Anchors and aliases are compared as written. Whitespace is compared as significant, so spacing and indentation changes are not treated as formatting-only. The structural outline, Changes tree and merge outline work for YAML files when the IDE has the YAML plugin.
- The refactorings view: a second structural outline, one toolbar toggle away, lists each rename, move, kind change, extraction and inlining before `Other changes`. Rows show both names, their kinds and a confidence word; a rename lists its grouped usages and a low-confidence row also appears under both declarations. Expanding a row shows its evidence, selecting it frames every endpoint and usage, and each rename's usages get their own theme-safe colour in both editors. The change type filter, a separate hide-by-kind menu and the same summary line work in both views.
- The relationship kind filter in the refactorings view has its own icon, so it is not mistaken for the change type filter.
- Inlining detection: a removed method or function whose body was moved into a changed declaration is labelled `inlined into <target>`, and the target is labelled `code inlined from <declaration>`. Selecting the removed declaration frames the code on the new side, selecting the target highlights it, the summary counts it as `inlined` instead of `removed`, and hiding removed changes hides it. Java, Kotlin and Scala 2/3 are covered.
- Rename grouping: a high-confidence rename is shown once with its changed same-file usages, such as `method total → sum()  rename candidate, 12 usages`, and a declaration whose only change is those usages is labelled `usages renamed`. Selecting the rename frames every grouped usage on both sides. Java, Kotlin K2 and Scala 2/3 are covered, with one resolver per language; unrelated, unresolved or also-changed usages stay ordinary changes.

### Fixed

- The structural outline follows the caret: moving it into another changed declaration on either side selects and frames that row without scrolling the editors, and a selected row no longer snaps the editors back to it when the outline's rows are rebuilt.
- Extraction detection links a helper to every method it was taken from. When the call that replaced the code repeated a few of its tokens, such as `return doubled(x) + 1;`, that method was not linked; now only runs of at least 5 tokens the method still has keep it from being a source.
- The outline panel keeps a minimum width of 200 px, scaled with the IDE font size, so dragging the splitter no longer squeezes the summary line and rows into a narrow column. A remembered width below the minimum opens the panel at the minimum, and the merge outline keeps the same floor.
- The refactorings view attaches a low-confidence `possible …` link only to declarations on its own side, so equal old and new offsets no longer add the link to an unrelated row. The relationship-kind filter is hidden in the container tree, where it has no effect.
- Scala 2 rename-only declarations are compared with the line-break whitespace policy the Scala 2 parser used, not the stricter Scala 3 indentation policy, and the shared rename tokenizer recognizes Kotlin and Scala backtick identifiers, Scala symbols and chars, and nested comments.

## [0.3.0] - 2026-11-08

### Added

- The Changes tree follows the caret in the editor and in both sides of a two-sided diff, selecting the changed declaration under the caret or the file row, while respecting the tree's filter and Declarations toggle.
- The Changes view's View Options menu has Structural Summary and Declarations toggles and a Hide Change Types submenu. The same controls are on Settings | Tools | SMART Diff, as the "Show declarations in the Changes tree" checkbox and the separate "Hide from the Changes tree" group. The tree's filter is separate from the outline's, keeps hidden changes counted in the summary as `· N hidden` or `· all hidden`, and changing it rebuilds the tree without comparing files again.
- Rename grouping, step 1 for Java: a high-confidence rename is shown once with its changed same-file usages, such as `method total → sum()  rename candidate, 12 usages`, and a declaration whose only change is those usages is labelled `usages renamed`. Selecting the rename frames every grouped usage on both sides. This first release groups Java usages only; Kotlin and Scala usages are not grouped yet.
- The outline panel has its own Hide and Minimise buttons. Minimising leaves a thin strip at the right edge with the summary line or fallback reason, and clicking it or pressing Enter restores the panel at its previous width without comparing again. The Structural Outline toggle has the default shortcut `Ctrl+Alt+Shift+O` (`Cmd+Option+Shift+O` on macOS), is first in the diff toolbar, and the minimised state and panel width are remembered for the next diff.
- The SMART Diff viewer has its own side-by-side-with-filter icon, with light and dark variants, so it can be told apart from the native viewer where the viewer switcher shows icons (2026.2 and later).
- Renamed or reordered methods and functions whose body also changed are more often shown as one `rename candidate` row, for example two tests renamed in the same commit, instead of one removed and one added row. When several renames or reordered declarations compete, the outline chooses over the whole file and leaves a genuine tie as two changes.

### Fixed
- Following the caret in a diff no longer logs `Read access is allowed from inside read-action only`: the diff viewer lookup builds one data context inside an application read action.
- Opening a Java, Kotlin or Scala diff no longer logs `Slow operations are prohibited on EDT`: the outline checks the project file index on its pooled thread, and a file outside the project keeps the engine result.
- A selected moved declaration's frames stay aligned on both sides while scrolling, in a two-sided diff and in the three-sided merge outline, instead of drifting apart when the unchanged code around them differs.
- Following the caret in either platform Changes preview, the editor tab or the Local Changes splitter, keeps the preview open by selecting the file row, instead of a declaration row that used to close or empty it.
- Following the caret in an added or deleted file's one-sided diff now selects that file's row, including a deleted file opened without a diff viewer in the Commit tool window.
- A caret event from the platform Changes preview, or from an editor without focus, no longer replaces a follow that is waiting for a rebuilt summary, so the declaration under the caret is still selected when the summary arrives.
- When the platform's Changes diff action cannot open a declaration, the fallback diff reads the committed revisions off the UI thread, so a slow VCS does not freeze the IDE.
- Double-click or Enter on a changed file with a structural summary in Local Changes or the Commit tool window now uses the platform's own preview, diff or source choice, so it opens the same editor preview as a file without a summary, and Enter works on 2025.1. Declaration rows open their diff through the tree's real selection.
- A large group of similar renamed declarations no longer makes the analysis time out: the assignment stops at its work limit and leaves that group unpaired, as it does for a group over the pair limit.
- A low-confidence container rename keeps its members paired and shows them moved, instead of leaving them unchanged under the added container.
- The declaration engine version names the installed Scala plugin's real version from its descriptor, instead of `Scala unknown`, so a Scala plugin update changes the version.

## [0.2.0] - 2026-11-05

### Added

- Each changed Java, Kotlin or Scala file in Local Changes and the Commit tool window can be expanded to show its changed declarations, labelled as in the outline. Double-click or Enter on one opens the file's diff with that declaration selected in the outline.
- Three-sided diffs for Java, Kotlin, Scala 2 and Scala 3 show the merge outline: declarations grouped by side, side names from the pane titles, a container-tree layout, the change-type filter and three-pane navigation.
- The merge window shows the same outline for Java, Kotlin, Scala 2 and Scala 3. Selecting a row frames the declaration in the editable result, follows edits, says `not tracked` when the result no longer contains it, and marks rows changed on both sides without a conflict as `merged without conflict`.
- The structural outline also appears for a same-language text diff that no engine supports, such as YAML, showing why no engine applies while the native diff stays in place.

### Fixed

- A changed file in the Changes tree that was not analyzed shows the reason in place of its summary, such as `not analyzed: too large`, `not analyzed: timed out`, `not analyzed: parse errors` or `not analyzed: analysis failed`.
- Scala and Kotlin: adding a declaration above another no longer marks the unchanged declaration as changed; the line break before it is shown as formatting-only.
- A failed analysis names the engine that failed in the outline's reason.

## [0.1.1] - 2026-10-23

### Added

- Extraction detection: a method or function added with code taken out of a changed declaration in the same file is labelled `extracted from <declaration>`, and the source `code extracted to <declaration>`. Selecting the extraction frames the extracted code on the old side; selecting the source highlights it. The summary line counts extractions as `extracted`.

### Fixed

- Scala: Scaladoc markup the Scala plugin flags, such as `[[F[A]]]` in prose, no longer marks declarations unanalyzed.
- A declaration with a parse error is shown as unchanged when its source is the same on both sides, so a parse error marks only code that changed.
- A class turned into a trait or interface, or a Scala `def` turned into a `val`, is shown as one declaration that changed kind, such as `trait → class Shape`, instead of being removed and added with all its members moved.
- Scala 2 code that the Scala 3 parser also accepts is now read as Scala 2, so re-indenting it is shown as formatting-only.

## [0.1.0] - 2026-10-22

### Added

- Structural outline beside the native side-by-side diff for Java, Kotlin and Scala files: a summary line, then the changed declarations, each labelled added, removed, changed, moved, signature changed, formatting-only, rename candidate, uncertain or unanalyzed.
- Renamed declarations with similar content are shown once, as `method total → sum()` rename candidates that the filter never hides, and overloads whose signatures all changed are paired.
- Selecting a declaration frames it on both sides of the diff; Enter jumps to it.
- Formatting-only changes are kept apart from changes in content, in a collapsed group by default.
- A filter that hides chosen change types from the outline, and a SMART Diff side-by-side viewer that fades the changes the filter hides.
- A Structural Outline toolbar button in every diff to switch back to the plain native diff.
- Settings under Settings | Tools | SMART Diff: whether the outline opens automatically, how formatting-only changes are shown, the default engine per language and the analysis limits.
- Each changed Java, Kotlin or Scala file in Local Changes and the Commit tool window shows its structural summary, such as `2 changed · 1 added`, with a setting to turn it off.
- Scala 2 and Scala 3 support, when the Scala plugin is installed: classes, traits, objects, enums, methods, values, type aliases, givens and extension methods, with Scala 3 indentation changes never shown as formatting-only.
- Unsupported, malformed or oversized files fall back to the native diff with the reason shown.

[Unreleased]: https://github.com/agadek/smart-diff/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/agadek/smart-diff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/agadek/smart-diff/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/agadek/smart-diff/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/agadek/smart-diff/commits/v0.1.0
