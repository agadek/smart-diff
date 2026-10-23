# SMART Diff Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/agadek/smart-diff/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/agadek/smart-diff/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/agadek/smart-diff/commits/v0.1.0
