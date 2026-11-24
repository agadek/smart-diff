# Releasing SMART Diff

Releases are GitHub releases with the signed plugin zip attached, which users install from disk. The release workflow
(`.github/workflows/release.yml`) builds, tests, verifies and signs the plugin when a GitHub release is published, and
attaches the signed zip to the release. Publishing to the [JetBrains Marketplace](https://plugins.jetbrains.com), as
plugin `io.github.agadek.smartdiff`, is off until the repository variable `PUBLISH_TO_MARKETPLACE` is `true`
(see [Marketplace publishing](#marketplace-publishing)).

## One-time setup

These steps need the maintainer's own accounts and keys; nothing in the repository holds them.

1. **Signing key.** Create a key and a self-signed certificate (the Marketplace accepts self-signed ones):

   ```shell
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl req -key private_encrypted.pem -new -x509 -days 3650 -out chain.crt
   ```

   Keep both files and the password outside the repository.

2. **Repository secrets** (GitHub: Settings | Secrets and variables | Actions):

   | Secret | Value |
   |---|---|
   | `CERTIFICATE_CHAIN` | the contents of `chain.crt` |
   | `PRIVATE_KEY` | the contents of `private_encrypted.pem` |
   | `PRIVATE_KEY_PASSWORD` | the key's password |

   The key signs every release, so keep the same one: the Marketplace rejects updates signed with a different key.

## Version on main

Between releases, `pluginVersion` on main names the next pre-release with a `-SNAPSHOT` suffix, for example
`0.4.0-beta.2-SNAPSHOT` after `0.4.0-beta.1` is released. The IDE then sorts a local build above the last released
version and below the next one. Release branches replace it with the release version (step 2 below), and after a
release main moves on to the next snapshot.

## Every release

1. Make sure `CHANGELOG.md` lists the changes under `## [Unreleased]`, in user-facing words.
2. On a branch, set `pluginVersion` in `gradle.properties` (for example `0.2.0`, or `0.2.0-beta.1` for the beta channel)
   and run `./gradlew patchChangelog`, which moves the unreleased notes under the new version. Merge it through a pull
   request once CI passes.
3. Run the corpus validation in [VALIDATION.md](VALIDATION.md) and record the results. Once [relationships/07](specs/relationships/07-relationship-validation.md) is accepted, run the [relationship benchmark](RELATIONSHIP-BENCHMARK.md) and record its results too. Neither gate may be skipped for a release that changes matching, classification or extraction.
4. Check the [early-access Plugin Verifier](../.github/workflows/eap-verifier.yml) run: the latest scheduled or on-demand run must be green, or its failure reviewed and recorded in the release notes or the pull request ([platform/01](specs/platform/01-platform-api-stability.md) R5). Then run `./gradlew verifyPlugin` and `./gradlew checkPlatformApiInventory`, which fails when a verifier report names an API use that [PLATFORM-API.md](PLATFORM-API.md) does not list (R8); update the inventory and its adapter's architecture rule for any new use.
5. Publish a GitHub release with the tag `v<pluginVersion>` (for example `v0.2.0`), with the version's section of
   `CHANGELOG.md` as its notes. The workflow checks that the tag matches `pluginVersion`, runs the same build, tests and
   Plugin Verifier as CI, signs the plugin and attaches `smart-diff-<version>-signed.zip` to the release.
6. With Marketplace publishing on, the workflow then publishes the same zip. The Marketplace reviews each update before
   it appears; the notes shown there come from the version's section of `CHANGELOG.md`.

Plugin Verifier runs on IntelliJ IDEA Community 2025.1.3, the latest IntelliJ IDEA release, PyCharm 2025.1.3 and the latest PyCharm release, RustRover 2025.1.3 and the latest RustRover release, and WebStorm 2025.1.3 and the latest WebStorm release, and resolves the optional Scala, Python, Rust and JavaScript dependencies for each, installing compatible plugin versions. CI runs it as a matrix over IDE families (`idea`, `pycharm`, `rustrover`, `webstorm`) with `-Psmartdiff.verifier.ides=<family>`; the release workflow runs `verifyPlugin` once per family in the same way. Each family passes only its own ignored problems to the verifier: `idea` ignores the JavaScript plugin's PSI, which IntelliJ IDEA Community does not bundle, and `webstorm` the Java plugin's, which WebStorm does not bundle ([PLATFORM-API.md](PLATFORM-API.md#ignored-verifier-problems)). The Scala, Python, Rust and JavaScript providers' and resolvers' use of the plugin PSI is in the internal- or experimental-API report, and every reported use is listed in [PLATFORM-API.md](PLATFORM-API.md). A weekly run verifies the newest IntelliJ IDEA early-access build with `-Psmartdiff.verifier.ides=eap`, which is not in the default set. See [ROADMAP.md](ROADMAP.md#scala).

Before publishing, check that `CHANGELOG.md` and `ROADMAP.md` use the same release labels: work still marked as planned in the roadmap must not appear as released in the changelog, and the other way around. `./gradlew patchChangelog` moves the notes, so check the roadmap by hand.

A pre-release suffix picks the Marketplace channel: `0.2.0-beta.1` is published to the `beta` channel, which users add
as a custom plugin repository; a version without a suffix goes to the default channel.

## Marketplace publishing

Off for now. To turn it on:

1. Add the repository secret `PUBLISH_TOKEN`, a Marketplace token from
   [your profile's tokens page](https://plugins.jetbrains.com/author/me/tokens).
2. **First upload by hand.** The Marketplace accepts uploads through its API only for a plugin that already exists, so
   the first version goes through the website. Download the signed zip of the latest GitHub release and upload it at
   [Upload plugin](https://plugins.jetbrains.com/plugin/add), choose the license (PolyForm Noncommercial 1.0.0, linked
   from the plugin description) and the tags, and wait for JetBrains' approval. The listing details, categories, tags,
   screenshots and the exact upload steps are in [MARKETPLACE.md](MARKETPLACE.md).
3. Add the repository variable `PUBLISH_TO_MARKETPLACE` with the value `true` (GitHub: Settings | Secrets and variables
   | Actions | Variables). Every later release then publishes through the workflow.
