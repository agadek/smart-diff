# SMART Diff

[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/agadek)
[![Support on Ko-fi](https://img.shields.io/badge/Support-Ko--fi-ff5e5b?logo=kofi&logoColor=white)](https://ko-fi.com/gadek)

A structure-aware diff viewer for IntelliJ-based IDEs.

SMART Diff lets you review a change by the declarations it touches instead of line by line. Beside the side-by-side
diff it lists the classes, methods, functions and keys that were added, removed, changed or moved. A brace-style
reformat or re-indentation is grouped as formatting-only, apart from the changes that alter content, and moves,
extractions, inlinings and renames show up as single rows. The native text diff stays one toolbar click away.

![A dark-theme IntelliJ IDEA diff of OrderService.java in the SMART Diff side-by-side viewer. The structural outline on the right starts with the summary "7 changed · 2 extracted · 3 added · 1 removed", lists under Refactorings the moved applyDiscount, describeItems extracted from summary, the moved validate and costFor extracted from shippingCost, and under Other changes groups the brace-style reformat of each class as Formatting-only.](docs/screenshots/readme/hero.png)

*A noisy change to `OrderService.java`: the brace style was reformatted throughout, and a few methods were added,
moved, extracted or removed. The outline lists the refactorings first and groups the reformat as formatting-only.*

Source code: [github.com/agadek/smart-diff](https://github.com/agadek/smart-diff)

Plugin ID: `io.github.agadek.smartdiff`

## Features

### Structural outline

Open a two-sided diff (local changes, VCS history or "Compare with…") and the outline appears beside the editors: a
summary line, then the declarations that changed, each with its kind: added, removed, changed, moved, signature
changed, formatting-only or rename candidate. Select a row to frame the declaration on both sides, or press Enter to
jump to it. Moved code gets markers in both editors, and *Expand Declaration* opens collapsed unchanged fragments of
the declaration at the caret.

### Refactorings view

One toolbar toggle lists the refactorings before the other changes: each rename with its grouped usages, each move,
each extraction (`extracted from <source>`) and each inlining (`inlined into <target>`), with a confidence word, the
evidence behind it and a filter by kind. Selecting a row frames every endpoint on both sides.

### Reviewed marks and filters

Mark a change as reviewed from the context menu or with Space, follow `N of M reviewed` in the summary line, and hide
reviewed rows with the reviewed filter. The change type filter hides chosen kinds from the outline, the SMART Diff
side-by-side viewer can fade them, and formatting-only changes stay apart from content changes.

### Summaries in review trees

Local Changes, the Commit tool window, the Git log's commit details and the changes of a GitHub pull request or GitLab
merge request show each changed file's summary, such as `2 changed · 1 added`. Expand a file to see its changed
declarations, and double-click one to open the diff with that declaration selected.

### Merge and three-sided diffs

The merge window and three-sided diffs show a structural outline grouped by side, so changes on both sides stay
visible.

### Languages and formats

- Java, Kotlin (including `.gradle.kts` scripts), Scala 2 and Scala 3, Groovy and Gradle scripts, Python, Rust,
  JavaScript and TypeScript (JSX and TSX included), Go, PHP
- SQL: tables and their columns, views, indexes, sequences, functions, procedures, triggers, types and schemas
- CSS, SCSS and Less: rule sets, at-rules such as `@media` and `@keyframes`, custom properties, variables and mixins
- Swift, through a bundled Tree-sitter grammar when the IDE has no Swift support
- Shell scripts, JSON, JSON with comments and JSON5, YAML, TOML, Java properties, XML and HTML, Markdown; for JSON and
  YAML, keys are declarations, including keys inside identified array items
- Other languages get an outline from the IDE's structure view; plain text and unsupported files list moved lines as
  `lines A–B → C–D`

Scala, Python, Rust, JavaScript, TypeScript, Go, the stylesheet languages, PHP and SQL need their IDE plugins (Python
and Rust are bundled in PyCharm and RustRover, JavaScript, TypeScript, CSS, SCSS and Less in WebStorm, IntelliJ IDEA
Ultimate and the other paid IDEs, Go in GoLand, PHP in PhpStorm, SQL in DataGrip, IntelliJ IDEA and the other paid
IDEs); the other formats use language support bundled with the IDE. Unsupported, malformed or oversized files stay in the plain
native diff with the reason shown.

### Settings

*Settings | Tools | SMART Diff* sets whether the outline opens automatically, how formatting-only changes are shown,
the default engine per language and the analysis limits. The **Structural Outline** toolbar button switches any diff
back to the plain native view.

## Compatibility

IntelliJ-based IDEs from version 2025.1.3 (build 251) onward, including IntelliJ IDEA, PyCharm, RustRover, WebStorm,
GoLand, PhpStorm and DataGrip.

## Install

Download `smart-diff-<version>-signed.zip` from the [latest release](https://github.com/agadek/smart-diff/releases/latest),
then in the IDE open *Settings | Plugins*, click the gear icon, choose **Install Plugin from Disk…** and select the zip.
Don't unpack it first.

Once the JetBrains Marketplace listing is approved, you can also search for **SMART Diff** in
*Settings | Plugins | Marketplace*.

## Requirements

- An IntelliJ-based IDE, 2025.1.3 or newer
- JDK 21 to build from source

## Build and run

```shell
./gradlew buildPlugin
./gradlew runIde
```

The distributable plugin is written to `build/distributions/`.

## Test and verify

```shell
./gradlew check         # unit, platform and architecture tests
./gradlew verifyPlugin  # Plugin Verifier on IntelliJ IDEA 2025.1 and the latest release
```

CI runs both on every pull request (`.github/workflows/build.yml`).

## Documentation

- [Technical specification](docs/TECHNICAL-SPECIFICATION.md)
- [Product scope](docs/specs/README.md#product-scope)
- [Writing specifications](docs/SPEC-GUIDE.md)
- [Feature specifications](docs/specs/README.md)
- [Roadmap](docs/ROADMAP.md)
- [Changelog](CHANGELOG.md)
- [Releasing](docs/RELEASING.md)
- [Decision records](docs/adr/)
- [Glossary](docs/GLOSSARY.md)
- [Validation on real commits](docs/VALIDATION.md)

## Support

SMART Diff is free for noncommercial use and maintained in spare time. It is developed with the help of Claude Code,
and sponsorships help cover the AI token budget that goes into new features and fixes. If the plugin saves you time in
reviews, you can chip in through [GitHub Sponsors](https://github.com/sponsors/agadek) or [Ko-fi](https://ko-fi.com/gadek).

## License

[PolyForm Noncommercial License 1.0.0](LICENSE). Noncommercial use (personal, educational, research, nonprofit
organizations) is allowed free of charge. Commercial use requires a separate license from the author.
