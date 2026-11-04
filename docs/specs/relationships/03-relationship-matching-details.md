# Relationship matching: details

Status: accepted, 2026-11-04

Feature: [Relationship matching](03-relationship-matching.md)

## Design

### Where it plugs in

`DeclarationMatcher` keeps rules M1, M2, M4 and M4b, and the unique-key part of M3, as they are. They pair by key, and their pairs stay high confidence. The change is to M3's repeated overload groups and to rule M5 (rename candidates): their greedy pass `Similarity.pairs` becomes a global assignment over the candidate graph, and rule M5's rename score gains the features of the [structural relationships plan](../../STRUCTURAL-RELATIONSHIPS.md#6-matching). The extraction detector is unchanged.

The building blocks that exist today and stay:

| Name | Value | Role |
| --- | --- | --- |
| `RENAME_THRESHOLD` | 0.7 | Rule M5's pairing floor. It stays a constant of the matcher, not a request threshold. |
| `OVERLOAD_THRESHOLD` | 0.5 | Rule M3's pairing floor. It stays. |
| `MARGIN` | 0.1 | How far ahead of every rival a pair must be. |
| `TIEBREAK_MIN` | 0.5 | The name similarity a close pair needs to be taken. |
| `TIEBREAK_MARGIN` | 0.2 | How much better the pair's names must be than every close rival's. |
| `MAX_SIMILARITY_PAIRS` | 10 000 | The largest number of possible pairs in a group the matcher compares; a larger group is not compared. |

### Candidate groups

- **Rule M5:** one group for each pair of matched containers and for the top level, split by exact `DeclarationKind`. It is not split by kind family: a declaration that changed kind is found by M4b before M5 runs, and M5 never pairs across kinds.
- **Rule M3:** one group for each path and name that occurs more than once on a side.
- M1, M2 and the first unique-key pairing of M3 run as they do today. M3's repeated groups are then solved by the assignment, the remaining unique-key M3 declarations pair by key, and M4 marks what is left of a shared path and name as uncertain. M4b runs next, and M5 last. M4's uncertain declarations are excluded from M5, so M5 only sees declarations that are still unmatched and not uncertain.
- The groups are disjoint at the moment they are solved, because a declaration paired by an earlier group is no longer unmatched when a later group is built. Rule M3's groups are solved first, then M5's. Together they are the whole candidate graph of the file, so one group's choice never takes an option from another.

Rule M5 only pairs inside a pair of matched containers or at the top level. A rename that also moved to another container is therefore not a candidate of this feature; such a change is found by M2 or M4b only when the name is unchanged, which is not a rename. Rename candidates across unmatched containers are out of scope, as the feature file says.

### Score for rename candidates (M5)

Each edge gets a score from 0 to 1:

| Feature | Weight | Source |
| --- | --- | --- |
| Content | 0.5 | Dice coefficient of adjacent token pairs, without names, as today |
| Name | 0.15 | Character-pair similarity of the names, ignoring case, as today |
| Parameter types | 0.15 | Dice coefficient of the parameter types as bags, split at top-level commas, as today |
| Context | 0.1 | 1 when the nearest sibling before or after is matched on both sides to the same pair, 0 otherwise |
| Order | 0.1 | 1 minus the normalised distance between the two positions among siblings |

- **Order:** `1 - min(1, |oldPosition - newPosition| / max(oldSiblingCount, newSiblingCount))`, where a position is the index among the declaration's siblings, or the top-level index.
- **Context:** a sibling counts only when an earlier rule matched it: M1, M2, M3 or M4b. A sibling paired by M5, including by the same assignment, does not count, so the feature never depends on its own output. Within one group the container path is the same on both sides, so the container itself is not a score term.
- **Order alone** is at most 0.1 and context plus order at most 0.2, both below every floor, so neither can pair two declarations by itself (R3).
- **Weights** are a starting point. They are tuned on the benchmark's tuning split only, never on the held-out split (R20).

**Same-body groups (R7).** When a declaration in a group has at least one other declaration with the same bigram bag — the same multiset of adjacent token pairs, names masked, that the content feature uses — content cannot identify it. For an edge that touches such a declaration the content weight becomes 0 and the other weights are renormalised to sum to 1: name 0.3, parameter types 0.3, context 0.2, order 0.2. Edges that touch no duplicated body keep the normal weights. Without the renormalisation the remaining evidence could reach only 0.5 and a duplicate body could never be paired, which contradicts today's behaviour and R7's "not by content alone". With it, a duplicate body pairs only when name, signature or position is clear.

### Score for overloads (M3)

Overloads keep today's score, `(parameter-type Dice + content Dice) / 2`, and today's floor `OVERLOAD_THRESHOLD` (0.5). The rename weights do not apply: a group shares its name, so name similarity is constant, and the weighted score with the 0.7 floor would drop pairs that pair today (R8, R17). There is no name tie-break, because the names are equal; equal totals go to the old side's order, then the new side's (R11).

An overload pair has the same name and path; it differs in its signature or its content, and it is not a relationship (relationships/02, `Today's relationships`), so the confidence policy does not classify it and no low-confidence relationship is made for an overload group. A group the assignment leaves unpaired goes to M4 and stays uncertain with candidates, not with relationships (relationships/02 R29).

### Pairing floor and confidence policy

- The rename floor is `RENAME_THRESHOLD` (0.7), a constant of the matcher. The assignment can only choose edges at or above it. It is **not** the policy's `medium` threshold: the policy classifies the pairs the matcher forms and never decides which pairs those are (relationships/02 R9, R13, R25; R19).
- With the default policy `medium` is 0.7, the floor itself, so a default rename is high or medium, never low.
- Raising `medium` makes a pair between 0.7 and `medium` low. The assignment has already taken it, so it still occupies both endpoints and its rivals stay unpaired; only the confidence and the computed fields change. The same pairs are found as with the default policy (R19).
- Lowering `medium` below 0.7 forms no new pairs, because the floor stays at 0.7.
- The policy is applied after the assignment. No threshold can change the candidate graph.

### Assignment

For each candidate group:

1. Build an edge for every old and new declaration in the group. Only edges at or above the group's floor can be chosen.
2. A **rival** of a chosen edge is an edge from either of its endpoints to a declaration that the assignment leaves unpaired. A rival's score is its raw score, even when it is below the floor. The chosen edge **passes the margin** when every rival scores no more than the chosen score minus `MARGIN` (0.1).
3. In a rename group, an edge that fails the margin still passes when the name tie-break below decides for it.
4. Take the assignment with the largest total score among the assignments whose chosen edges all pass; each declaration appears at most once. This is a maximum-weight bipartite matching, for example the Hungarian algorithm. An implementation may solve, review and drop failing edges and solve again on the remaining edges; it must give this result.
5. Of assignments with equal total score, compare their pairs as `(old index, new index)` lists sorted by old index and then new index, lexicographically; if one list is a prefix of the other, the longer list wins. This gives the old side's source order, then the new side's (R11) as one deterministic order, and it never makes a pair the margin refuses (ADR 0008).
6. A group with more possible pairs than `MAX_SIMILARITY_PAIRS` is not compared; its declarations stay unpaired by this group. An M3 group's declarations then go through the second unique-key M3 pass and M4, and an M5 group's declarations stay added and removed (R12).
7. The matcher checks for cancellation every row (R13). A group still unfinished at the time limit uses the greedy result for that group (see Failure behaviour).

**Name tie-break (kept).** An edge that fails the score margin still passes when its name similarity is at least `TIEBREAK_MIN` (0.5) and every rival's name similarity is at least `TIEBREAK_MARGIN` (0.2) lower. This is today's rule with today's constants. It is applied to a chosen edge after the assignment, as part of the pass test; it is not a search key or a score. It is kept rather than replaced, because it is what lets today's near-ties with a clear name pair, and replacing it would lose pairs the golden fixtures and the benchmark show as correct. The name feature (0.15) also contributes to the score; the tie-break only resolves a near-tie the score cannot. The review notes of the [structural relationships plan](../../STRUCTURAL-RELATIONSHIPS.md#review-comments) ask for this choice to be explicit.

**Ties and low-confidence relationships (R6).** A declaration that the assignment leaves unpaired may still have a close option. For every such declaration, let `best` be its highest-scoring edge to another unpaired declaration, below the floor included. Every edge at or above the floor to another unpaired declaration whose score is within `MARGIN` of `best` is reported once, as a low-confidence relationship with the `RENAME` kind. The relationship carries `Name` and `Score` evidence; it claims no move, kind change or position, because nothing was paired. Its confidence is `LOW` whatever its score, even when the score is at or above `high`; the score stays in `Score` evidence (relationships/02 R6). The declarations keep no computed fields and stay added and removed (relationships/02 R13).

This is rule M5 only. M4's uncertainty is different and stays as it is: its declarations keep `candidates` and produce no relationship (relationships/02 R29). M4 runs before M5, so the two never describe the same declaration.

### How a low-confidence tie shows

- Both declarations stay added and removed: the outline and the Changes tree show one removed row and one added row, each on its side, with no rename candidate row and no `renamedFrom` note. The user sees two separate changes, exactly as without this feature.
- The relationship is only in `ComparisonResult.relationships` until the [refactorings view](06-refactorings-view.md) displays it. It does not hide a change (relationships/02 R20).
- A low-confidence tie is not a [rename candidate](../../GLOSSARY.md#rename-candidate). In the container tree a medium or high rename is, as relationships/02 R28 says; the refactorings view has its own marks in relationships/06 R8.

### Combined changes

A pair gets every kind that applies: rename when the matcher paired it as a rename candidate, move when the containers or order differ, kind change when the kinds differ, and the change's content and signature fields are set as today (R9).

- **Rename and move:** rule M5 pairs only inside matched containers, so a rename with a container change is not produced by this feature. A rename whose order among siblings changed is, and it carries `RENAME` and `MOVE`.
- **Kind change and move:** rule M4b pairs a declaration that changed kind and also changed order among its siblings; it carries `KIND_CHANGE` and `MOVE`. M4b does not pair a declaration that moved to another container.
- **Confidence:** a relationship with several kinds takes the lowest of its kinds' confidence (relationships/02 R26). A move and a kind change are high, so a renamed and reordered declaration takes the rename's.

**How a high-confidence rename is shown.** Decided 2026-11-04: a high-confidence rename keeps the rename candidate mark, exactly like a medium one (relationships/02 R28), and the outline does not change. No other label exists in this feature. [relationships/07](07-relationship-validation.md) is amended to match: high confidence is about the relationship's confidence, not about losing the candidate mark.

### Thresholds and reasons

- **Rename floor 0.7** (`RENAME_THRESHOLD`): today's value, and [declarations/03](../declarations/03-rename-candidates.md) R2 now delegates the score to this feature. The [2026-11-02 baseline](../../VALIDATION.md#2026-11-02-relationship-benchmark-baseline) has no wrong rename pair at it. It stays fixed so a policy change cannot change which declarations pair (R19; relationships/02 R9, R13).
- **Overload floor 0.5** (`OVERLOAD_THRESHOLD`): today's value for a group whose names are equal. The rename weights and the 0.7 floor would drop pairs that the 2026-11-02 baseline labels correct (R8, R17).
- **Margin 0.1** (`MARGIN`): today's value, which the validation found to prevent wrong pairs between near duplicates.
- **Name tie-break 0.5 and 0.2**: today's values. Keeping them preserves the near-ties the current greedy pass accepts; the [review notes](../../STRUCTURAL-RELATIONSHIPS.md#review-comments) require this to be a stated choice.
- **Same-body renormalisation**: without it the remaining evidence could reach only 0.5, below the floor, so no duplicate body could pair at all. The renormalisation lets name, signature and position decide (R7).
- **High threshold, tuned (R20):** relationships/02's `high = 0.85` is a starting value and is replaced by a tuned one. The tuning run uses the 153 tuning cases only; its 271 held-out cases are not read. For each value from 0.70 to 1.00, it classifies every rename the matcher pairs on the tuning split; high-confidence precision is the share of high-confidence pairs that the labels mark correct, with every high pair in the denominator, wrong ones included. It takes the highest value that keeps that precision at least 95% (the target of [relationships/07](07-relationship-validation.md) R2) and keeps at least three quarters of the tuning split's correctly paired renames high; the three-quarter coverage floor is a-priori, chosen so high remains the majority band. If no value meets both, `high` stays 0.85 and the pull request records the table and the failure; precision is never traded for coverage. Once chosen the value is fixed: a failed held-out check in 07 does not re-tune it, and the failure is reported as a release-gate failure. The value replaces `ConfidencePolicy.DEFAULT.high` and the registry default `0.85,0.7`, and the pull request records the table.
- **Hungarian assignment over greedy:** greedy can take a pair that leaves another declaration without its only option, where the global choice pairs both. The benchmark's `no-new-wrong-pairs` gate and held-out precision decide whether it ships (R15, R16); if it adds no correct pair, the greedy pass stays and only the scoring changes.

### Benchmark treatment of low confidence

Only high and medium relationships are scored as reported relationships. A low-confidence relationship never pairs its declarations: it is either a tie the matcher left unpaired or a pair that a raised `medium` threshold moved below medium. It is counted in the report's confidence table (relationships/02 R33) and is never a true positive, false positive or [forced pair](../../GLOSSARY.md#forced-pair). Without this rule a tie in an ambiguous case would count as a forced pair, and a tie in any other case as a false positive, although nothing was paired. relationships/02's Benchmark section states the same exclusion; this section records how it applies to the low level that relationships/03 adds.

### Failure behaviour

When the time limit is reached during the assignment, the matcher returns the greedy pass's result for the remaining groups, with the same score and floor; the result is still `ANALYZED`. Cancellation stops the match at the next row (R13). A group with more possible pairs than `MAX_SIMILARITY_PAIRS` is not compared (R12).

### Engine version

`DeclarationEngine.VERSION` goes from 7 to 8, because the pairs and the relationships change (R18).

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `DeclarationMatcherTest`: every M1, M2, M4 and M4b fixture, and every unique-key M3 fixture, still pairs the same way |
| R2, R3 | Score tests that change one feature at a time; an order-only and a context-plus-order pair stay unpaired |
| R4, R5 | A fixture where greedy and global assignment differ; a near-tie fixture with unclear names stays unpaired; a near-tie with clear names pairs as today; a below-floor rival leaves the pair unpaired |
| R6 | The near-tie fixture has a low-confidence relationship for each tied option, none sets `renamedFrom`, and both declarations stay added and removed; a tie whose score is above `high` is still low |
| R7 | Duplicate-body fixtures in Java, Kotlin, Scala 2 and Scala 3: content weight 0, renormalised weights, a pair only when name or signature is clear |
| R8 | Overload fixtures in the four languages: the 0.5 floor and the parameter-types-plus-content score are unchanged; an overload tie stays uncertain with candidates and has no relationship |
| R9 | A rename with an order change, and a kind change with an order change |
| R10 | A policy test that moves a rename from high to medium, and one from medium to low |
| R11 | A tie fixture where only the old and new order decides; the margin still wins over order |
| R12 | A generated group with more than 10 000 possible pairs: no pair from that group; M3 declarations follow the unique-key pass and M4, M5 declarations stay added and removed |
| R13 | A cancellation test during the assignment |
| R14 | `LimitsBenchmarkTest` |
| R15, R16 | `RelationshipBenchmarkTest` with the recorded baseline, run in the pull request; a low-confidence relationship does not count as a pair |
| R17 | `GoldenFixturesTest`; changed rows listed in the pull request |
| R18 | Engine version test |
| R19 | The same pair set under a raised and a lowered `medium`, and under a raised `high` |
| R20 | The tuning run over the tuning split only; the held-out run uses the fixed value; a failed held-out check leaves the value unchanged |
