# SMART Diff

A structure-aware diff viewer for IntelliJ-based IDEs.

Source code: [github.com/agadek/smart-diff](https://github.com/agadek/smart-diff)

Plugin ID: `io.github.agadek.smartdiff`

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
- [Decision records](docs/adr/)
- [Glossary](docs/GLOSSARY.md)
