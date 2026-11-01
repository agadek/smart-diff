# Writing SMART Diff Specifications

Status: 2026-10-31. How to write feature specifications, for anyone adding or changing one, coding agents included.

## 1. Spec first

SMART Diff follows spec-driven development. The specification says what the plugin does, and the code and tests follow it.

- Write or change the feature's spec before you write the code.
- Get the spec change reviewed before the code that depends on it.
- When the code must behave differently, change the spec first, in the same pull request or an earlier one.
- Every requirement can be checked by a test or a manual check.
- Tests name the requirement they check, by folder, file and ID, for example `// declarations/05-extraction-detection R3`.
- When the code and the spec disagree, the spec is right until it is changed.

## 2. Folders

Feature specs are in `docs/specs/`, in one folder per context. A context is a part of the product that a reviewer would name, such as:

| Folder | Context |
| --- | --- |
| `docs/specs/outline/` | The structural outline in a two-sided diff: rows, selection, filter, summary line |
| `docs/specs/engines/` | Comparison engines, their selection and the engine dropdown |
| `docs/specs/declarations/` | The declaration engine: matching, classification, rename candidates, extraction detection |
| `docs/specs/languages/` | What each supported language adds: Java, Kotlin, Scala |
| `docs/specs/changes-tree/` | The summary and declarations in the Changes tree |
| `docs/specs/merge/` | The outline in three-way merges and three-sided diffs |
| `docs/specs/relationships/` | Relationships between declarations: their model, matching, rename grouping, inlining, refactorings view, benchmark and validation |
| `docs/specs/settings/` | Settings and toolbar toggles |

- Put a feature in the folder of the context it changes most.
- Add a new folder only when a feature fits none of the existing ones.
- Folder names are lower case, with hyphens.

## 3. Files

Each feature has its own file, named with an order number and the feature name:

```text
docs/specs/declarations/05-extraction-detection.md
```

- The number has two digits and gives the feature's order inside its folder: the order it was built in, or will be built in.
- A new feature takes the next free number in its folder.
- Never renumber a file or reuse a number, so links and test references stay valid.
- The name is the feature's name in lower case, with hyphens.
- One file describes one feature. If a file needs two use cases that a reviewer would ask for separately, split it.
- Every feature file is listed in [docs/specs/README.md](specs/README.md), by folder and in order, with its status.

## 4. What a feature file contains

Every feature file has these parts, in this order:

1. **Title:** the feature name, as a heading.
2. **Status:** `proposed`, `accepted`, `implemented` or `retired`, with the date it was reached.
3. **Description:** one to three sentences on what the feature does.
4. **Use case:** who uses it, in which situation, and what they get from it.
5. **Requirements:** a bullet list. Each requirement has an ID (R1, R2, …) and is one short sentence.

A feature can end with two optional parts:

- **Out of scope:** short bullets on nearby work the feature does not do.
- **References:** links to the decision records, technical sections and validation results behind it.

Nothing else goes in a feature file. How the feature is built (algorithms, classes, thresholds and their reasons) goes in the [technical specification](TECHNICAL-SPECIFICATION.md) or a [decision record](adr/), linked from References.

## 5. Writing requirements

- Write one short sentence per requirement, with one thing to check.
- Use simple words and the present tense: "The outline shows …", not "The outline should show …".
- Say what the user sees or what the plugin does, not how the code does it.
- Name the part of the product as the subject: the outline, the summary line, the Changes tree.
- Use numbers with units for limits: "within 5 s", "at most 3 names".
- Give each requirement an ID, R1, R2, …, in the order of the list. Never renumber or reuse an ID. Mark a dropped requirement as *(retired)* instead of deleting it.
- Leave out reasons and examples. If a requirement needs a reason, put it in a decision record and link it.

## 6. Key words and the dictionary

The dictionary is [GLOSSARY.md](GLOSSARY.md). It defines the words the specs and the UI use.

- Link a key word to its dictionary entry the first time it appears in a file: `[declaration](../../GLOSSARY.md#declaration)`.
- A key word is a word with a meaning in SMART Diff that a new reader might not know, such as *declaration*, *old side*, *comparison engine* or *rename candidate*.
- Use only the words the dictionary defines. Do not use the words it lists under *Avoid*.
- When a feature needs a new key word, add its entry to the dictionary in the same change.
- Each dictionary entry is a `###` heading with the term, so it can be linked, followed by a one-sentence definition and the words to avoid.

## 7. Example

```markdown
# Extraction detection

Status: implemented, 2026-10-22

**Description:** Links an added method to the code it was extracted from, when that code was removed
from a changed declaration in the same file.

**Use case:** A reviewer reads a change where a long method was split into a helper. They want to see
that the helper's code was moved unchanged, without searching for it on the old side.

## Requirements

- **R1** An added [declaration](../../GLOSSARY.md#declaration) whose body is mostly code removed from a
  changed declaration is an [extraction](../../GLOSSARY.md#extraction).
- **R2** An extraction stays added, with the note `extracted from <source>`.
- **R3** The [source](../../GLOSSARY.md#extraction-source) stays changed, with the note `code extracted to <declaration>`.
- **R4** Selecting an extraction frames the extracted code on the [old side](../../GLOSSARY.md#old-side).
- **R5** A note lists at most 3 names, then `+N more`.
- **R6** The summary line counts extractions as `extracted`, not `added`.
- **R7** Hiding added changes also hides extractions.
- **R8** Detection never changes how a declaration is matched or classified.

## Out of scope

- Code extracted to another file.
- Code extracted into a lambda or a local function.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Validation, 2026-10-23](../../VALIDATION.md#2026-10-23-extraction-detection)
```

## 8. Checklist

Before opening a pull request that adds or changes a feature spec:

- The file is in the right context folder and named `NN-feature-name.md` with the next free number.
- The file is listed in `docs/specs/README.md`.
- It has a title, a status with a date, a description, a use case and requirements.
- Each requirement is one short, simple sentence with an ID, and can be checked.
- Each key word links to the dictionary the first time it appears, and new key words are added there.
- How it is built is in the technical specification or a decision record, not in the feature file.
- No person is named in the file, the commit message or the pull request.
