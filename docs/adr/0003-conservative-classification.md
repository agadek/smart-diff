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

- Renames are not detected as such. From 0.1.0, a removed and an added declaration of the same kind with similar content are paired as a *rename candidate*: it is labeled as a candidate, and the change type filter never hides it, since the pairing may be wrong. When two declarations are about as similar, neither is paired. The same rule pairs overloads that all changed signature, which otherwise stay uncertain.
- In Kotlin, adding or removing a line break between tokens is not treated as formatting-only, because line breaks can change how statements are parsed. Re-wrapped Kotlin code therefore shows as changed.
- Languages where indentation is part of the syntax (Python, YAML) never receive a formatting-only claim until a dedicated rule is written and tested.
- The engine still reports every change. The outline's change type filter lets the user hide types of changes they chose, such as formatting-only; the summary keeps counting them and says how many are hidden, and uncertain and unanalyzed changes cannot be hidden ([technical specification §10](../TECHNICAL-SPECIFICATION.md#10-structural-outline)).
