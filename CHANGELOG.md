# SMART Diff Changelog

## [Unreleased]

### Added

- The Changes tree follows the caret in the editor and in both sides of a two-sided diff, selecting the changed declaration under the caret or the file row, while respecting the tree's filter and Declarations toggle.
- The Changes view's View Options menu has Structural Summary and Declarations toggles and a Hide Change Types submenu. The tree's filter is separate from the outline's, keeps hidden changes counted in the summary as `· N hidden` or `· all hidden`, and changing it rebuilds the tree without comparing files again.
- Inlining detection: a removed method or function whose body was moved into a changed declaration is labelled `inlined into <target>`, and the target is labelled `code inlined from <declaration>`. Selecting the removed declaration frames the code on the new side, selecting the target highlights it, the summary counts it as `inlined` instead of `removed`, and hiding removed changes hides it. Java, Kotlin and Scala 2/3 are covered.
- Rename grouping: a high-confidence rename is shown once with its changed same-file usages, such as `method total → sum()  rename candidate, 12 usages`, and a declaration whose only change is those usages is labelled `usages renamed`. Selecting the rename frames every grouped usage on both sides. Java, Kotlin K2 and Scala 2/3 are covered, with one resolver per language; unrelated, unresolved or also-changed usages stay ordinary changes.
- The outline panel has its own Hide and Minimise buttons. Minimising leaves a thin strip at the right edge with the summary line or fallback reason, and clicking it or pressing Enter restores the panel at its previous width without comparing again. The Structural Outline toggle has the default shortcut `Ctrl+Alt+Shift+O` (`Cmd+Option+Shift+O` on macOS), is first in the diff toolbar, and the minimised state and panel width are remembered for the next diff.
- The SMART Diff viewer has its own side-by-side-with-filter icon, with light and dark variants, so it can be told apart from the native viewer where the viewer switcher shows icons (2026.2 and later).
- Renamed or reordered methods and functions whose body also changed are more often shown as one `rename candidate` row, for example two tests renamed in the same commit, instead of one removed and one added row. When several renames or reordered declarations compete, the outline chooses over the whole file and leaves a genuine tie as two changes.

### Fixed

- Following the caret in either platform Changes preview, the editor tab or the Local Changes splitter, keeps the preview open by selecting the file row, instead of a declaration row that used to close or empty it.
- Scala 2 rename-only declarations are compared with the line-break whitespace policy the Scala 2 parser used, not the stricter Scala 3 indentation policy, and the shared rename tokenizer recognizes Kotlin and Scala backtick identifiers, Scala symbols and chars, and nested comments.
- When the platform's Changes diff action cannot open a declaration, the fallback diff reads the committed revisions off the UI thread, so a slow VCS does not freeze the IDE.
- Double-click or Enter on a changed file with a structural summary in Local Changes or the Commit tool window now uses the platform's own preview, diff or source choice, so it opens the same editor preview as a file without a summary, and Enter works on 2025.1. Declaration rows open their diff through the tree's real selection.

## [0.2.0] - 2026-11-05

### Added

- Each changed Java, Kotlin or Scala file in Local Changes and the Commit tool window can be expanded to show its changed declarations, labelled as in the outline. Double-click or Enter on one opens the file's diff with that declaration selected in the outline.
- Three-sided diffs for Java, Kotlin, Scala 2 and Scala 3 show the merge outline: declarations grouped by side, side names from the pane titles, a container-tree layout, the change-type filter and three-pane navigation.
- The merge window shows the same outline for Java, Kotlin, Scala 2 and Scala 3. Selecting a row frames the declaration in the editable result, follows edits, says `not tracked` when the result no longer contains it, and marks rows changed on both sides without a conflict as `merged without conflict`.
- The structural outline also appears for a same-language text diff that no engine supports, such as YAML, showing why no engine applies while the native diff stays in place.

### Fixed

- A large group of similar renamed declarations no longer makes the analysis time out: the assignment stops at its work limit and leaves that group unpaired, as it does for a group over the pair limit.
- A low-confidence container rename keeps its members paired and shows them moved, instead of leaving them unchanged under the added container.
- The declaration engine version names the installed Scala plugin's real version from its descriptor, instead of `Scala unknown`, so a Scala plugin update changes the version.
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

[Unreleased]: https://github.com/agadek/smart-diff/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/agadek/smart-diff/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/agadek/smart-diff/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/agadek/smart-diff/commits/v0.1.0
