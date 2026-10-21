---
status: accepted
---

# Conservative classification: nothing hidden, nothing called equivalent

SMART Diff never hides a change and never claims that two versions behave the same. "Formatting-only" is narrow: the token sequences are equal once whitespace that the language treats as insignificant is ignored. Comments, string contents and significant whitespace always count as changes. Any pairing the matcher cannot make unambiguously is shown as uncertain rather than guessed. A reviewer who trusts a wrong "formatting-only" or a wrong match can miss a real change, which costs far more than a little extra noise.

## Considered Options

- **SemanticDiff-style invariance rules** (for example treating some literal or syntax rewrites as equivalent): less noise, but every rule is a per-language behavioural claim that needs proof and maintenance.
- **Global "ignore whitespace/comments" switches:** simple, but wrong for languages where whitespace matters and they hide comment edits.
- **Token equality with per-language whitespace policy (chosen).**

## Consequences

- Renames are not detected; a renamed declaration appears as one removal and one addition.
- In Kotlin, adding or removing a line break between tokens is not treated as formatting-only, because line breaks can change how statements are parsed. Re-wrapped Kotlin code therefore shows as changed.
- Languages where indentation is part of the syntax (Python, YAML) never receive a formatting-only claim until a dedicated rule is written and tested.
