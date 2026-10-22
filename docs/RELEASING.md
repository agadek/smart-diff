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

## Every release

1. Make sure `CHANGELOG.md` lists the changes under `## [Unreleased]`, in user-facing words.
2. On a branch, set `pluginVersion` in `gradle.properties` (for example `0.2.0`, or `0.2.0-beta.1` for the beta channel)
   and run `./gradlew patchChangelog`, which moves the unreleased notes under the new version. Merge it through a pull
   request once CI passes.
3. Publish a GitHub release with the tag `v<pluginVersion>` (for example `v0.2.0`), with the version's section of
   `CHANGELOG.md` as its notes. The workflow checks that the tag matches `pluginVersion`, runs the same build, tests and
   Plugin Verifier as CI, signs the plugin and attaches `smart-diff-<version>-signed.zip` to the release.
4. With Marketplace publishing on, the workflow then publishes the same zip. The Marketplace reviews each update before
   it appears; the notes shown there come from the version's section of `CHANGELOG.md`.

A pre-release suffix picks the Marketplace channel: `0.2.0-beta.1` is published to the `beta` channel, which users add
as a custom plugin repository; a version without a suffix goes to the default channel.

## Marketplace publishing

Off for now. To turn it on:

1. Add the repository secret `PUBLISH_TOKEN`, a Marketplace token from
   [your profile's tokens page](https://plugins.jetbrains.com/author/me/tokens).
2. **First upload by hand.** The Marketplace accepts uploads through its API only for a plugin that already exists, so
   the first version goes through the website. Download the signed zip of the latest GitHub release and upload it at
   [Upload plugin](https://plugins.jetbrains.com/plugin/add), choose the license (PolyForm Noncommercial 1.0.0, linked
   from the plugin description) and the tags, and wait for JetBrains' approval.
3. Add the repository variable `PUBLISH_TO_MARKETPLACE` with the value `true` (GitHub: Settings | Secrets and variables
   | Actions | Variables). Every later release then publishes through the workflow.
