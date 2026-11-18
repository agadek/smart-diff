# Marketplace listing kit

The kit for the first manual upload of SMART Diff to the [JetBrains Marketplace](https://plugins.jetbrains.com). 0.4.0 has no version suffix, so `publishing.channels` in `build.gradle.kts` resolves to **`default`** — upload to the default channel, not a custom one. Automated publishing stays off until `PUBLISH_TO_MARKETPLACE` is `true` ([RELEASING.md](RELEASING.md#marketplace-publishing)).

## Identity

| Field | Value |
| --- | --- |
| Plugin name | SMART Diff |
| Plugin ID | `io.github.agadek.smartdiff` |
| One-line summary | Review code changes by structure in IntelliJ-based IDEs, with the native text diff always available. |
| Vendor | agadek (`https://github.com/agadek`) |
| License | PolyForm Noncommercial 1.0.0 — `https://polyformproject.org/licenses/noncommercial/1.0.0` |
| Source | `https://github.com/agadek/smart-diff` |
| Issue tracker | `https://github.com/agadek/smart-diff/issues` |
| Description | Use the `<description>` in `src/main/resources/META-INF/plugin.xml` verbatim; it is the source of truth for the shipped feature list. |
| Since build | `251` (2025.1); no until build — every later IDE is supported |
| Channel | `default` |

The listing must not claim that the outline makes reviews faster: the usability study ([outline/06](specs/outline/06-usability-validation.md)) has not reached R7. The description and [0.4.0 changelog](../CHANGELOG.md#040---2026-11-18) make no speed claim.

## Categories and tags

Choose up to three, in this order of preference:

1. **Code review** — the outline, refactorings view and reviewed marks are review tools.
2. **VCS integration** — Local Changes, the Commit tool window, Git log and pull request trees.
3. **Code tools** — declaration-level diffing across languages and formats.

Additional tags to add if the category screen allows them: `diff`, `structural diff`, `merge`, `refactoring`, `code quality`, `Java`, `Kotlin`, `Scala`, `Python`, `Rust`, `JSON`, `YAML`, `XML`.

## Listing screenshots

Marketplace recommends about 1200×760 PNGs. The files below are rendered from the real diff viewer and outline by `RefactoringsScreenshotTest` (see [the folder README](screenshots/refactorings-view/README.md)); they are automated evidence, not a recorded manual pass. Upload these five, in this order:

| # | Path | Actual size | Caption |
| --- | --- | --- | --- |
| 1 | `docs/screenshots/refactorings-view/java-rename-usages-container-light.png` | 1500×920 | Structural outline: changed declarations and a rename with its usages |
| 2 | `docs/screenshots/refactorings-view/java-rename-usages-refactorings-light.png` | 1500×920 | Refactorings view: one row for a rename and its grouped usages |
| 3 | `docs/screenshots/refactorings-view/java-rename-usages-selected-light.png` | 1500×920 | Selecting a rename frames the declaration and its usage in both editors |
| 4 | `docs/screenshots/refactorings-view/kotlin-extraction-refactorings.png` | 1500×920 | An extraction as one row in the refactorings view |
| 5 | `docs/screenshots/refactorings-view/scala-inlining-refactorings.png` | 1500×920 | An inlining as one row in the refactorings view |

All five are 1500×920, larger than the recommended size and with a slightly taller aspect ratio; crop or resize them to about 1200×760 before upload if the upload form enforces the recommendation. They should also be **retaken on a real IDE** before the listing goes public: the folder README records that the manual IDE check is outstanding and that these images were generated headlessly. The two images under `docs/screenshots/changes-tree-manual-2026-11-09/` are 1400×1000 and 1400×855 and come from inconclusive manual checks; do not use them as listing screenshots.

## First upload, step by step

1. On the final `0.4.0` tag, build, test, verify and sign with the release secrets: `./gradlew clean buildPlugin check verifyPlugin signPlugin`. The signed archive is `build/distributions/smart-diff-0.4.0-signed.zip`. Alternatively download that file from the `v0.4.0` GitHub release. Do not upload an unsigned zip.
2. Sign in at `https://plugins.jetbrains.com` with the maintainer account and open [Upload plugin](https://plugins.jetbrains.com/plugin/add).
3. Upload `smart-diff-0.4.0-signed.zip`. The Marketplace reads the ID, name, vendor, description, since build and change notes from the plugin archive; check that the description matches `plugin.xml` and that the change notes show the [0.4.0 section](../CHANGELOG.md#040---2026-11-18) as HTML.
4. Fill in the one-line summary, choose the categories and tags above, select the **PolyForm Noncommercial 1.0.0** license and paste its link, keep the source and issue tracker URLs, and upload the five screenshots with their captions.
5. Keep the **default** channel and submit. JetBrains reviews every first upload and each update; wait for approval before announcing it.
6. After approval, set the `PUBLISH_TOKEN` repository secret and the `PUBLISH_TO_MARKETPLACE` variable to `true` so later releases publish through [release.yml](../.github/workflows/release.yml). The same signing key must be kept for every later upload.
7. Confirm the listing page renders the description, change notes, license and screenshots, and that installing the zip in IC 2025.1.3 and IU 2026.3 loads the plugin.
