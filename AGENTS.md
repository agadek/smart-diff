# Agent instructions

- Before adding or changing a document in `docs/` (a specification, a decision record, a roadmap entry, a validation record), read [docs/SPEC-GUIDE.md](docs/SPEC-GUIDE.md) and follow it.
- Before every push, run the CI steps locally and push only when they pass: `./gradlew buildPlugin check` and `./gradlew verifyPlugin`.
- Never name people in repository content (documents, commit messages, pull requests); write "the maintainer" or leave the person out.
