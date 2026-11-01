---
status: proposed
---

# Relationships are the source of truth for pairing

The [structural relationships plan](../STRUCTURAL-RELATIONSHIPS.md) adds relationships between declarations: renames, moves, kind changes, extractions and inlinings, with a confidence and evidence. Today these facts live in fields of `ChangeNode` (`renamedFrom`, `moved`, `kindChangedFrom`, `extractedFrom`, `extractedTo`), set by `DeclarationMatcher` and `ExtractionDetector`, with no confidence or evidence. Questions Q1 to Q6 of the plan were decided on 2026-10-30; this record keeps those decisions with their reasons.

## Decision

- **Placement (Q2).** `ComparisonResult` gains a top-level `relationships` list. It is the only source of truth. The existing `ChangeNode` fields become values computed from it and are then deprecated, so the outline, the Changes tree and the merge outline keep working unchanged.
- **Confidence (Q1).** The API exposes an ordinal, `HIGH`, `MEDIUM` or `LOW`. The numeric score stays in the evidence for debugging and the benchmark. A `ConfidencePolicy` passed in the request, like `limits`, holds the thresholds and what each level shows: `HIGH` pairs, `MEDIUM` is a candidate as rename candidates are today, `LOW` leaves both sides added and removed with a possible link. A registry key sets it in a running IDE.
- **References (Q3).** Grouping a rename with its usages is a separate analysis pass with project access, run after the engine. The engine stays text-only, and [ADR 0002](0002-parser-neutral-model-with-psi-first.md) is unchanged.
- **Cross-file scope (Q4).** Later, as a commit-level layer over per-file results; the per-file engine and its contract stay as they are.
- **Languages (Q5).** The reference pass covers Java, Kotlin and Scala together, with one resolver per language; the Kotlin and Scala resolvers get a Plugin Verifier review on both IDEs.
- **Viewer (Q6).** When the refactorings view comes, a toolbar toggle switches between it and the container tree, remembered as a setting.

## Considered Options

- **Relationships per `ChangeNode` (rejected):** a link between two nodes, or one node and several, has no single owner; storing it on both ends needs a symmetry rule for every kind.
- **Relationships next to the existing fields, both kept (rejected):** two sources of truth for renames, moves and kind changes, with a contract rule to keep them equal.
- **Top-level list, fields computed from it (chosen):** one source, and every consumer keeps its current fields during the move.
- **A calibrated numeric confidence in the API (rejected):** calibration needs the benchmark first, and a percentage reads as a claim of correctness, which [ADR 0003](0003-conservative-classification.md) rules out.
- **Project context in the engine request (rejected):** it breaks ADR 0002 for every engine, and diff sides built from text have no classpath to resolve against.

## Consequences

- The engine contract gains relationship checks, and every engine returns an empty list until it detects relationships.
- The relationship benchmark runs before the first matcher change, so the baseline is today's engine.
- Pairing that has no clear winner still leaves both sides unpaired: the margin check comes before any ordering rule.
