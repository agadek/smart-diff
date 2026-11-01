# Relationship matching: details

Status: proposed, 2026-11-01

Feature: [Relationship matching](03-relationship-matching.md)

## Design

### Where it plugs in

`DeclarationMatcher` keeps rules M1 to M4b. They pair by key, and their pairs stay high confidence. The change is to rule M5 and to overload pairing, which today use `Similarity.pairs`: a greedy pass that takes the most similar option first and leaves both unpaired when a rival is within `MARGIN` (0.1).

### Candidates and score

For each container, the unpaired old and new declarations of the same kind family form a candidate graph. Each edge gets a score from 0 to 1:

| Feature | Weight | Source |
| --- | --- | --- |
| Content | 0.5 | Dice coefficient of adjacent token pairs, without names, as today |
| Name | 0.15 | Character-pair similarity of the names, ignoring case |
| Parameter types | 0.15 | Share of equal parameter types, in order |
| Context | 0.1 | Same container path, same nearest sibling before or after |
| Order | 0.1 | 1 minus the normalised distance between the two positions |

Order alone is at most 0.1, below every threshold, so it cannot pair two declarations by itself. The weights are a starting point, tuned on the benchmark's tuning split only.

### Assignment

1. Drop edges below the medium threshold of the confidence policy.
2. Find the assignment with the largest total score (Hungarian algorithm), per container group.
3. Keep a pair only when its score beats the next best edge of each of its two declarations by the margin (0.1).
4. A pair that fails the margin becomes a low-confidence relationship for each option, and both declarations stay unpaired.
5. Same-body groups (equal token bags) of more than one declaration in a container are not paired by content: their content weight is 0, so name, parameter types and context must decide.
6. Ties are broken by the old side's order, then the new side's.

A group larger than `MAX_SIMILARITY_PAIRS` (10 000 edges) falls back to today's greedy pass. The assignment checks for cancellation every row.

### Combined changes

A pair found by M2, M3, M4b or the assignment gets every kind that applies: rename when the names differ, move when the containers or order differ, kind change when the kinds differ. The change's content and signature fields are set as today.

### Thresholds and reasons

- Margin 0.1: today's value, which the validation found to prevent wrong pairs between near duplicates.
- Hungarian assignment over greedy: greedy takes a 0.9 pair that leaves a 0.85 option with nothing, where the global choice pairs both. The benchmark decides whether it ships; if it adds no correct pairs, the greedy pass stays and only the scoring changes.

### Failure behaviour

When the time limit is reached during assignment, the matcher returns today's greedy result for the remaining groups; the result is still `ANALYZED`.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `DeclarationMatcherTest`: every M1 to M4b fixture still pairs the same way |
| R2, R3 | Score tests with one feature changed at a time; an order-only pair stays unpaired |
| R4, R5 | A fixture where greedy and global assignment differ; a near-tie fixture stays unpaired |
| R6 | The near-tie fixture has a low-confidence relationship for each option |
| R7, R8 | Duplicate-body and overload fixtures in Java, Kotlin, Scala 2 and Scala 3 |
| R9 | A rename + move + kind change + signature change fixture |
| R10 | A policy test that moves a pair from high to medium |
| R11 | A tie fixture where only order decides |
| R12 | A generated file with more than 10 000 edges |
| R13 | A cancellation test during assignment |
| R14 | `LimitsBenchmarkTest` |
| R15, R16 | `RelationshipBenchmarkTest` with the recorded baseline, run in the pull request |
| R17 | `GoldenFixturesTest`; changed rows listed in the pull request |
| R18 | Engine version test |
