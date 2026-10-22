# Releasing SMART Diff

Releases go to the [JetBrains Marketplace](https://plugins.jetbrains.com) as plugin `io.github.agadek.smartdiff`. The
release workflow (`.github/workflows/release.yml`) builds, tests, verifies, signs and publishes the plugin when a GitHub
release is published, and attaches the signed zip to the release.

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
   | `PUBLISH_TOKEN` | a Marketplace token from [your profile's tokens page](https://plugins.jetbrains.com/author/me/tokens) |

3. **First upload by hand.** The Marketplace accepts uploads through its API only for a plugin that already exists, so
   the first version goes through the website:

   ```shell
   export CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private_encrypted.pem)" PRIVATE_KEY_PASSWORD=...
   ./gradlew buildPlugin check verifyPlugin signPlugin
   ```

   Upload `build/distributions/smart-diff-<version>-signed.zip` at
   [Upload plugin](https://plugins.jetbrains.com/plugin/add), choose the license (PolyForm Noncommercial 1.0.0, linked
   from the plugin description) and the tags, and wait for JetBrains' approval. Then publish the `v0.1.0` GitHub release
   and attach the signed zip to it by hand: its workflow run stops at the publish step because the Marketplace already
   has that version. Every later release publishes through the workflow alone.

## Every release

1. Make sure `CHANGELOG.md` lists the changes under `## [Unreleased]`, in user-facing words.
2. On a branch, set `pluginVersion` in `gradle.properties` (for example `0.2.0`, or `0.2.0-beta.1` for the beta channel)
   and run `./gradlew patchChangelog`, which moves the unreleased notes under the new version. Merge it through a pull
   request once CI passes.
3. Publish a GitHub release from `main` with the tag `v<pluginVersion>` (for example `v0.2.0`). The workflow checks that
   the tag matches `pluginVersion`, runs the same build, tests and Plugin Verifier as CI, and then publishes.
4. The Marketplace reviews each update before it appears; the notes shown there come from the version's section of
   `CHANGELOG.md`.

A pre-release suffix picks the Marketplace channel: `0.2.0-beta.1` is published to the `beta` channel, which users add
as a custom plugin repository; a version without a suffix goes to the default channel.
