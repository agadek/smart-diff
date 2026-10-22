# SMART Diff Changelog

## [Unreleased]

### Added

- Structural outline beside the native side-by-side diff for Java and Kotlin files: a summary line, then the changed declarations, each labelled added, removed, changed, moved, signature changed, formatting-only, rename candidate, uncertain or unanalyzed.
- Renamed declarations with similar content are shown once, as `method total → sum()` rename candidates that the filter never hides, and overloads whose signatures all changed are paired.
- Selecting a declaration frames it on both sides of the diff; Enter jumps to it.
- Formatting-only changes are kept apart from changes in content, in a collapsed group by default.
- A filter that hides chosen change types from the outline, and a SMART Diff side-by-side viewer that fades the changes the filter hides.
- A Structural Outline toolbar button in every diff to switch back to the plain native diff.
- Settings under Settings | Tools | SMART Diff: whether the outline opens automatically, how formatting-only changes are shown, the default engine per language and the analysis limits.
- Each changed Java or Kotlin file in Local Changes and the Commit tool window shows its structural summary, such as `2 changed · 1 added`, with a setting to turn it off.
- Unsupported, malformed or oversized files fall back to the native diff with the reason shown.
