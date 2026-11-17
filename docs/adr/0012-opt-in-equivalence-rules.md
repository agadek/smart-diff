---
status: accepted
---

# Opt-in equivalence rules, one language at a time

[ADR 0003](0003-conservative-classification.md) left syntax rewrites that do not change a value, such as `0x10` written as `16`, out of classification, because each one is a behavioural claim about a language. The [roadmap](../ROADMAP.md#opt-in-equivalence-rules) asks to reconsider them as opt-in rules, one language at a time, each with fixtures that prove it. This amends ADR 0003 for that case only.

## Decision

- An equivalence rule is language-specific, names one kind of rewrite, and claims only that: the rewritten literal has the same value, or the removed parentheses group the same expression.
- Rules never change classification. A rewritten declaration is still `changed` for the engine, the Changes tree, the merge outline, the benchmark and the corpus validation. The outline alone can show it as `rewrite-only`, and only when the user turns the option on.
- Every rule can be turned off on its own, and the option is off by default, so ADR 0003 still holds for every user who does not ask for this.
- A rule ships only with fixtures for every case it accepts and the nearest cases it must reject. A rule that would need types or name resolution is out of scope.
- A comment change, a signature change or a parse error keeps a declaration changed.

The first rules are Java's numeric literals, string escapes and redundant parentheses ([declarations/08](../specs/declarations/08-equivalence-rules.md)).

## Considered Options

- **Keep ADR 0003 as it is:** no claim to maintain, but literal and parenthesis clean-ups stay as noise next to the real change, which is the gap the 2026-10-22 review found.
- **Rules inside classification, on by default:** less noise for everyone, but a wrong rule would hide a real change by default, against product rule P3.
- **Opt-in rules as an outline label (chosen):** the pattern of [comment-only changes](../specs/outline/11-comment-only-changes.md), with one setting per rule.

## Consequences

- Product rule P2 gains one exception: a rewrite-only label claims what its rule names and nothing more.
- `ChangeNode` carries the rules each node needs, so a rule turned off or on takes effect without comparing again.
- Each further language needs its own rules, tests and a spec change; no rule is shared between languages by default.
