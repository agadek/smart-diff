# SMART Diff

[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/agadek)
[![Support on Ko-fi](https://img.shields.io/badge/Support-Ko--fi-ff5e5b?logo=kofi&logoColor=white)](https://ko-fi.com/gadek)

A structure-aware diff viewer for IntelliJ-based IDEs.

Source code: [github.com/agadek/smart-diff](https://github.com/agadek/smart-diff)

Plugin ID: `io.github.agadek.smartdiff`

## Use

Open any two-sided diff of a Java, Kotlin or Scala file (local changes, VCS history or "Compare with…"). The structural
outline appears beside the editors: a summary line, then the changed declarations. Select one to see it on both sides,
or press Enter to jump to it. The **Structural Outline** toolbar button switches the diff back to the plain native view,
and *Settings | Tools | SMART Diff* sets whether the outline opens automatically, how formatting-only changes are shown,
the default engine per language and the analysis limits.

## Requirements

- IntelliJ IDEA 2025.1 or newer
- JDK 21

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

- [Product specification](docs/SPECIFICATION.md)
- [Technical specification](docs/TECHNICAL-SPECIFICATION.md)
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
