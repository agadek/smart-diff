# Writing SMART Diff Specifications

Status: 2026-10-31. How to write and maintain the documents in `docs/`, for anyone adding or changing one, coding agents included. It describes the conventions the existing documents already follow; where they differ, it says which one to use from now on.

## 1. Which document

| Document | Holds | Change it when |
| --- | --- | --- |
| [SPECIFICATION.md](SPECIFICATION.md) | The product: problem, users, goals, non-goals, user-visible requirements | A goal, non-goal or user-visible requirement changes |
| [TECHNICAL-SPECIFICATION.md](TECHNICAL-SPECIFICATION.md) | How the shipped plugin works, by numbered section (§), the single list of spikes (§13) and the general open questions (Q1, Q2, …, §14) | Behaviour, an interface, a limit or a test layer changes, including when a feature spec's work lands |
| Feature specification, such as [EXTRACTION-DETECTION.md](EXTRACTION-DETECTION.md) or [THREE-WAY-MERGE.md](THREE-WAY-MERGE.md) | One roadmap item in full: problem, behaviour, design, validation, tests, plan | Before the item is built, and as its decisions are made |
| [Decision records](adr/) | One decision that is hard to reverse, with the options it rejected | A design choice is made that later work depends on |
| [ROADMAP.md](ROADMAP.md) | What comes next and in which order, a short entry per item | An item is added, reordered, specified or implemented |
| [VALIDATION.md](VALIDATION.md) | Dated results of running the engine on real commits, and what they changed | After each corpus run |
| [GLOSSARY.md](GLOSSARY.md) | The terms the documents and the UI use, with terms to avoid | A spec introduces a term |
| [RESEARCH.md](RESEARCH.md) | Background on other products and techniques | New background informs a decision |

A new feature normally starts as a roadmap entry. When it needs more than a few bullets to describe, it gets its own feature specification, and the roadmap entry links to it ("Specified in [NAME.md](NAME.md)"). Do not put a feature's full design in the roadmap or in the technical specification before it is decided.

## 2. Files and names

- Specifications are in `docs/`, named in upper case with hyphens after the feature: `EXTRACTION-DETECTION.md`, `THREE-WAY-MERGE.md`.
- Decision records are in `docs/adr/`, numbered in order and named after the decision in lower case: `0007-merge-outline-attaches-through-a-delegating-merge-tool.md`.
- A new document is added to the Documentation list in the [README](../README.md).

## 3. The header

Every specification starts with a title and a status paragraph, before the first section:

```markdown
# Extraction Detection Specification

Status: proposal, 2026-10-22. Third item after the first release, following ... It expands the roadmap's
[extraction detection](ROADMAP.md#extraction-detection) entry. Section numbers (§) refer to the
[technical specification](TECHNICAL-SPECIFICATION.md), and terms follow the [glossary](GLOSSARY.md).
```

The status paragraph says:

- **The state and its date:** `proposal`, `draft`, `implemented` or `historical`, with the ISO date it was reached. Keep earlier states when they explain the document: "proposal, 2026-10-23, with its open questions T1 to T5 decided the same day".
- **Where the results are**, once there are any: "implemented, 2026-10-22; the validation results are in [VALIDATION.md](VALIDATION.md)".
- **Where it sits:** the roadmap entry it expands and what comes before it.
- **The references** it relies on: the technical specification for §, the glossary for terms, and the scope it assumes (for example the supported languages).

Update the status paragraph in the same change that changes the state. A reader should be able to tell from it alone whether the document describes shipped behaviour or a plan.

## 4. Feature specification layout

Number the sections, so they can be referred to as §1, §4.5. Use these sections in this order, and leave out the ones that do not apply:

1. **Problem.** What the reviewer has to do today and why it is hard, with concrete cases: a table of examples, a fixture such as `noisy-change`, or a finding from [VALIDATION.md](VALIDATION.md). No solution yet.
2. **Goals and non-goals.** Two bullet lists. Each goal is something the reviewer can see or do. Non-goals name the nearby work that is left out and, where it helps, why or where it goes instead ("the engine compares one file at a time"). State how the feature keeps to [ADR 0003](adr/0003-conservative-classification.md): nothing hidden, nothing called equivalent.
3. **Risks first** (for work that depends on platform behaviour or other unproven assumptions). Each risk has an ID (R1, R2, …), says what could go wrong, and names the phase or spike that settles it. When it is settled, add the outcome in place, starting with *Settled by S6:* or *S6 found*.
4. **Behaviour.** What the user sees, before any design. Start from a rendered example in a `text` block (the outline, a summary line, a note), using the same fixture as §1. Then one bullet per rule, each starting with a bold name: **Selection**, **Summary line**, **Several links**. Cover every surface the feature touches: the outline, the summary line, the change type filter, the Changes tree, the SMART Diff viewer, and say "no change" for the ones it leaves alone.
5. **Design sections**, as many as needed and named after what they describe (*Detection*, *The join*, *Model and result*, *Integration*). Name new classes, packages and fields as they will be in the code, and say which existing step they run after and what they never change. Give every threshold a constant name and a value (`MIN_SHARE` 0.65), and say how its cost is bounded and what happens when the bound is hit.
6. **Languages**, when languages differ: one subsection per language with what its provider must give and its special cases.
7. **Validation.** How the feature is checked on real commits before it ships: the corpus, the report flags it adds, and **Done when** criteria that can be measured ("no reviewed link is wrong", "the slowest file takes at most 20% longer"). Say that the results are added to VALIDATION.md.
8. **Tests.** By layer, each a bold-named bullet listing the cases: analysis unit tests (no IDE), provider tests, golden fixtures, the contract suite, UI model tests, platform tests.
9. **Open questions.** Each with an ID and a bold title: **X1 Thresholds.** Say what decides it (the validation, a spike, the maintainer).
10. **Decisions.** Questions once decided, with the date, the choice, the reason and the options rejected (see §6).
11. **Later.** Follow-ups that are out of scope, each one sentence on what it is and what it waits for.
12. **Implementation plan** or **Phases.** A numbered list, or a table `| Phase | Work | Settles | Done when |` when there are risks to settle. Each step or phase is one pull request, and each runs the full CI steps (build, tests, Plugin Verifier) before it is pushed.

Results of a spike that belong to the feature go in a last section of their own (*Spike S6 results*), with the date, the IDE version and the test that covers it.

## 5. Writing style

- **Plain, present-tense English.** The spec states what the plugin does: "Selecting it frames the declaration on the new side", not "should" or "will". Use "must" only for a requirement on other code, such as a provider.
- **Short sentences, one rule each.** Prefer a bullet list of rules to a long paragraph. Lead each rule bullet with a bold name.
- **Glossary terms only.** Write *old side* and *new side*, not left/right or before/after; *declaration*, not node or element; *comparison engine*, not diff engine. A new term goes into [GLOSSARY.md](GLOSSARY.md) in the same change, with the words to avoid.
- **Concrete over abstract.** Show an example with real names (`describeItems(Order,StringBuilder)`), a rendered row, a table of cases. Reuse the existing fixtures and corpus repositories so examples can be checked.
- **Code identifiers in backticks**: classes, fields, constants, settings, registry keys, file names, Gradle tasks. Model changes go in a `kotlin` block that shows only the new fields, with `// …existing fields…` for the rest.
- **Numbers with units and names.** Limits, thresholds and timings are named constants with values and units (`MAX_WORK` 2 × 10^7 token comparisons, 5 s).
- **Dates are ISO**: 2026-10-23.
- **No people's names** in any document, commit message or pull request. Write "the maintainer" or leave the person out.

## 6. IDs, decisions and references

- **IDs.** Open questions and decisions take a letter for their document and a number: Q in the technical specification, X in extraction detection, T in the three-way merge plan. A new feature specification picks a letter that is not used yet and keeps it. Risks are R1, R2, …, spikes S1, S2, … (numbered across the project in technical specification §13), matching rules M1, M2, …. An ID is never reused or renumbered.
- **Decisions.** Record a decision where its question was: "**T3 Changed on both sides without a conflict.** Same group, marked `merged without conflict` …", with the date, the reason, and a "Rejected:" sentence naming the other options and why. Then change every place in the spec that depended on it, so the behaviour sections never contradict a decision. A question that was resolved in place keeps its ID and says so: "**Q1 Default visibility (resolved 2026-10-21).**"
- **Decision records.** A decision that later work builds on, that is costly to reverse, or that chose between real alternatives gets an ADR in `docs/adr/`: front matter `status: accepted`, a title that states the decision as a sentence, a paragraph of context, then *Decision*, *Considered Options* (each with **(chosen)** or **(rejected)** and why) and *Consequences*. Add a section on what the platform offers when the decision came from a spike. The spec links the ADR where the decision applies.
- **Spikes.** A question that only an experiment can answer becomes a spike: add a row to the table in technical specification §13 (question, done when, result) and refer to it by its number. A spike runs as a platform test, and its result names the test that keeps covering it.
- **References.** Link other documents with relative links, to the section where possible: [technical spec §9.4](TECHNICAL-SPECIFICATION.md#94-changes-tree-summary), [ADR 0003](adr/0003-conservative-classification.md). In a feature specification a bare § means the technical specification, as its header says; refer to the document's own sections with a link, such as [§12](#12-spike-s6-results), so the two cannot be confused.

## 7. Keeping documents current

A specification describes either the current behaviour or a plan, and says which in its status paragraph. When work lands, the same pull request:

- updates the feature specification's status paragraph, and replaces wording that described the plan ("once the provider is merged", "before the feature ships") with what was done, keeping acceptance criteria as the record of how it was accepted;
- updates the technical specification sections the feature changes, so it stays the reference for shipped behaviour;
- marks the roadmap entry implemented and links to where it is specified;
- adds a dated section at the top of [VALIDATION.md](VALIDATION.md) with the corpus, the results and what they changed, when a validation ran;
- adds new terms to the glossary, new documents to the README, and an entry to the [changelog](../CHANGELOG.md) for user-visible changes.

A spec change that only decides or plans, with no code, is still its own pull request on a `docs/` branch.

## 8. Checklist

Before opening a pull request that adds or changes a specification:

- The status paragraph gives the state, the date, the roadmap entry and the references.
- §1 shows a concrete case, and §4 shows what the user sees before any design.
- Goals and non-goals say how the feature stays conservative (ADR 0003).
- Every threshold and limit has a name, a value and a unit; every cost has a bound.
- Validation and phases have measurable *Done when* criteria.
- Every open question has an ID, and every decision has a date, a reason and the rejected options.
- New terms are in the glossary, the roadmap links to the spec, and the README lists any new document.
- No person is named.
