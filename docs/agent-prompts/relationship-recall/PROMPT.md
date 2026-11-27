# Agent prompt: improve relationship recall, scored on the smartdiff corpus

Written 2026-11-27. Matcher task; the full task description is [TASK.md](TASK.md). Paste the block below into a new agent session (written for Kiro running without an IDE, but any coding agent works). It expects the repository at `semantic-diff/` and the corpus clones beside it at `../smartdiff-corpus/`, the paths `relationships-corpus-full.txt` uses.

```text
Repository agadek/smart-diff (JetBrains plugin "SMART Diff", Kotlin, Gradle, IntelliJ Platform). Improve relationship recall (renames, moves, extractions, inlinings) on the relationship benchmark over the real commits of the smartdiff corpus, without one new wrong pair, forced pair or hidden change, and without fitting the matcher to the held-out cases.

Start: git fetch origin && git checkout feat/relationship-recall-corpus-misses. That branch already holds docs/agent-prompts/relationship-recall/TASK.md. Read TASK.md in full: it is the task, with the twelve known misses, the overfitting rules, the steps, the commands and the acceptance criteria. Then check that no other open PR or branch already does this work (look for branches or PRs about relationship recall or benchmark misses); if one exists, stop and say so. Check that ../smartdiff-corpus holds the clones the corpus list names (okhttp, guava, commons-lang, spring-petclinic, moshi, Exposed, cats, zio, scala3, flask, attrs); if not, stop and say so.

Read before writing anything: AGENTS.md, docs/SPEC-GUIDE.md, docs/adr/0003, docs/RELATIONSHIP-BENCHMARK.md, docs/specs/relationships/01 to 08 (each with its -details.md; 01, 03, 05 and 07 closely), docs/specs/declarations/05-extraction-detection.md, the "2026-11-25: 0.5.0 release validation" and "2026-11-23: relationship recall" sections of docs/VALIDATION.md, and PR #218 (git show 33b6aa4) as the model for this kind of change. Code: engine/declarations/analysis/ (DeclarationMatcher.kt, ExtractionDetector.kt, GlobalAssignment.kt, CodeMoveDetector.kt, ContentClassifier.kt), DeclarationEngine.kt (VERSION), and the tests RelationshipBenchmarkTest, RelationshipTuningTest, RelationshipMatchingTest and the extraction and inlining tests.

Steps (TASK.md has the detail for each):
 1. Baseline: run the benchmark on main's engine with relationships-corpus-full.txt and keep report.json and report.md outside the repository (../relationship-recall-baseline/). Check the corpus revision against VALIDATION.md's 0.5.0 run. Never overwrite src/test/testData/relationships-baseline/report.json.
 2. Fresh held-out labels, before any engine change: at least 5 new real commits per language (Java, Kotlin, Scala 2, Scala 3, Python) from the clones, not already in the list, each with a refactoring you can see in the commit. Draft with -Psmartdiff.relationships.drafts, review every draft by hand against git show, set reviewed: true and split: held-out, add them to the list, rerun step 1 and keep that report as the baseline. Commit alone: "test(benchmark): add fresh held-out real-commit labels".
 3. Diagnose every miss in main's report: label error, engine miss fixed (mechanism + requirement), or engine miss not fixed (reason). Explain by general mechanism, never by a case's names or sizes.
 4. Spec first: amend the owning spec (relationships/03, 05, declarations/05, or the move rules) with new requirement IDs and their "not" cases, details files and status lines. Commit alone: "docs: specify … (relationships/0N R2x …)".
 5. Synthetic tuning-split cases (at least three per new requirement, in two languages where it applies, one a near-miss that stays unpaired), added to relationships-corpus.txt and relationships-corpus-full.txt, and a unit test per requirement named "// relationships/0N R2x".
 6. Implement in engine/declarations/analysis/. Change no existing threshold, weight or floor; a new rule is a new named condition with its own documented constant. Bump DeclarationEngine.VERSION to 27.
 7. Score: rerun the benchmark gated against ../relationship-recall-baseline/report.json, plus LimitsBenchmarkTest and CorpusValidationTest as TASK.md says. Write a new dated section at the top of docs/VALIDATION.md with the before/after table (tuning, old held-out and fresh held-out recall separately) and the miss table.
 8. Golden fixtures: run check without -Psmartdiff.updateGolden=true first, read every failure, update a golden file only after reading and agreeing with its diff, and list each with its reason. Never mass-update to get green.
 9. Docs: CHANGELOG.md [Unreleased] in user terms; set the amended specs to implemented with the date in the last commit.
10. Local CI, all of it, before any push: ./gradlew buildPlugin check, then ./gradlew verifyPlugin (every IDE family: idea, pycharm, rustrover, webstorm, goland, phpstorm, datagrip; it downloads several IDEs, so free disk space first if needed). Both must pass, with no new internal, experimental or deprecated API use and no change to docs/PLATFORM-API.md. Fix and re-run until green. If a step cannot run on this machine, stop and report it; do not push and hope CI catches it. Write down each command, its result (pass/fail, test counts, verifier verdict per IDE build) and the date: they go in the PR body.
11. Push feat/relationship-recall-corpus-misses and open the PR yourself, to main, titled "feat: improve relationship recall on the smartdiff corpus (relationships/…)", with gh pr create --base main --head feat/relationship-recall-corpus-misses. Pushing the branch is not enough: the task is not done until the PR exists. The PR body has: the requirements added; the before/after benchmark table; the miss table; the fresh held-out labels listed for a second review; each changed golden fixture with its reason; and a "Local CI" section with the commands, results and date from step 10, plus anything you did not run, marked as not run. If gh is not installed or not signed in, stop after pushing and report that, with the compare URL; never say a PR exists when it does not. Then stop and report the PR link. Do not review, approve or merge the PR; a separate review follows, and fixes it asks for come back to you as a list. For every later fix push, rerun step 10 first and add the new local CI result to the PR as a comment.

Rules:
- The spec wins over the code until the spec is changed; change the spec first when the code must differ. When a case is unclear, choose the conservative answer (no pair; the change stays visible), write it into the spec, and say so in the PR.
- Precision first: one new wrong or forced pair fails the task, whatever the recall gain.
- Branch names are typed and descriptive (feat/…, fix/…, docs/…, test/…, chore/…). Use feat/relationship-recall-corpus-misses for this task; never a generic or generated name.
- Never name a person in repository content, commit messages, PR titles or PR bodies; write "the maintainer" or leave the person out.
- Commit messages are descriptive, in the repository's style (feat:, docs:, test:, fix: prefixes).
- Run the full local CI (step 10) before every push, including pushes of later fixes.
- Do not skip, disable, weaken or delete any test to get green.
- Keep machine-specific paths, baseline reports and corpus lists you build out of the repository.
```

## How to start Kiro

From the repository on a machine with JDK 21, the corpus clones in `../smartdiff-corpus` and `gh` signed in:

```shell
cd semantic-diff
git fetch origin && git checkout feat/relationship-recall-corpus-misses
kiro-cli chat --trust-all-tools "$(sed -n '/^```text$/,/^```$/p' docs/agent-prompts/relationship-recall/PROMPT.md | sed '1d;$d')"
```

Or start `kiro-cli chat` and paste the block above.
