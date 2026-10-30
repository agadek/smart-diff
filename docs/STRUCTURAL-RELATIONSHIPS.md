# Structural Relationships and Refactoring Awareness

Status: proposal, 2026-10-30. This is a program of work, not one release item. It expands the roadmap's [rename grouping across usages](ROADMAP.md#rename-grouping-across-usages), [moves inside declarations](ROADMAP.md#moves-inside-declarations), [fine-grained differencing](ROADMAP.md#fine-grained-differencing), [extraction detection](ROADMAP.md#extraction-detection) and [three-way merge](ROADMAP.md#structural-outline-for-three-way-merges) entries. The benchmark is specified in [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md); terms follow the [glossary](GLOSSARY.md).

Related: [product specification](SPECIFICATION.md), [technical specification](TECHNICAL-SPECIFICATION.md), [ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md), [ADR 0003](adr/0003-conservative-classification.md), [ADR 0004](adr/0004-pluggable-comparison-engines.md), [ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md), [extraction detection](EXTRACTION-DETECTION.md), [three-way merge](THREE-WAY-MERGE.md).

## 1. Problem

SMART Diff answers “which declarations changed?” well. It does not yet consistently answer “which changes form one refactoring?” A reviewer still has to reconstruct that a rename, a move, a signature change and a body edit are one logical change, or that a helper was extracted from a method and its call sites moved with it.

The current engine already has useful pieces:

- rules M1 to M5 match declarations by kind, path, name, signature and content similarity;
- rename candidates, kind changes, moved declarations and extraction links are reported;
- ambiguous matches are kept visible as uncertain;
- every result retains source ranges, and the nothing-hidden contract prevents suppression.

The gaps are structural, not cosmetic:

- the result model combines a pairing, its change classification and its links in one `ChangeNode`;
- matching is global over a file but pairing is greedy, not an optimal assignment;
- there are no confidence scores or evidence, so a reviewer cannot inspect why a pair was made;
- matching is per file, with no reference or cross-file analysis;
- the outline groups by container and change type, not by logical refactoring;
- the benchmark suite checks expected output and flags suspicious results, but does not measure precision, recall or incorrect-match rate against labelled ground truth.

## 2. Goals

1. Detect relationships between declarations across two versions of one file: rename, move, kind change, extraction and inlining, including combined relationships such as rename plus body change.
2. Keep matching separate from change classification: a relationship says what two subjects are to each other; presence, content, moved, signature changed and kind changed stay change attributes.
3. Support one-to-many and many-to-one relationships where they are real, especially extraction from several sources and extraction to several targets.
4. Attach confidence and inspectable evidence to every relationship, without turning confidence into a claim of behavioural equivalence.
5. Leave ambiguous candidates unpaired and visible instead of forcing a relationship because bodies are similar.
6. Measure matching quality on a labelled benchmark before and after each matcher change.
7. Add a viewer presentation that groups related changes, exposes evidence on request and preserves the native textual diff as the authoritative fallback.
8. Extend to reference-aware and cross-file work only behind an explicit API and scope decision.

## 3. Non-goals

- Proving behavioural equivalence, or reporting that a change is semantically safe.
- Replacing IntelliJ's textual diff, or adding an LLM-based comparison engine.
- Automatic rewriting, merge resolution or patch application.
- Hiding source because a relationship is confident; uncertain and unanalysed source stays visible.
- Cross-file moves, commit-level refactoring detection or variable renames in the first phase. These need a multi-file or reference-aware design, not a matcher tweak.
- Statement-level moves and local-variable analysis until the declaration model is extended. They are currently outside the declaration engine (ADR 0001).
- Every supported language at once. New capabilities are added language by language with fixtures.
- Making the internal `comparisonEngine` extension point a stable third-party API.

## 4. What exists today

| Area | Current state | Gap |
| --- | --- | --- |
| Declaration matching | `DeclarationMatcher` rules M1 to M5; keys use kind, path, name and signature; similarity uses token bigrams | Pairing is greedy with a 0.1 margin, not a global assignment |
| Ambiguity | M4 reports `UNCERTAIN`; close similarity rivals are not paired | No evidence or confidence; ambiguity is a presence state, not a first-class relationship |
| Renames | M5 pairs removed and added declarations with similar content; Java constructors follow renamed classes | Rename is linked to one declaration only; no call-site grouping; small/empty constructors can be missed |
| Moves | `Match.containerChanged` and LCS order detection | Only whole declarations; no statement moves; moved is a boolean, not a relationship |
| Kind changes | M4b pairs type-family and value-member kind changes | Scope is deliberately conservative |
| Extraction | `ExtractionDetector` links added methods/functions to removed source code; many links are already represented | Only Java, Kotlin and Scala added methods/functions; no generic inlining; links are not grouped as a refactoring |
| Classification | `ContentClassifier`; `ChangeNode` carries presence, content, moved, signature changed, rename and extraction links | Relationship and change attributes are combined in one model |
| Contract | Contract suite checks determinism, ranges, nothing hidden, cancellation, malformed input and extraction origins | No relationship or confidence invariants yet |
| Benchmark | Golden fixtures, contract corpus, corpus validation and limits benchmark | No labelled precision/recall or incorrect-match baseline |
| Viewer | Container tree, formatting group, uncertain group, change-type filter, native-diff toggle | No logical-refactoring group, evidence display or related-reference navigation |

## 5. Terms and model

### 5.1 Relationship

A **relationship** links an old-side subject to a new-side subject, or to several subjects, without deciding whether either side changed. It is additional information, never a replacement for the change tree.

An illustrative, engine-neutral model:

```kotlin
data class Relationship(
    val kind: Set<RelationshipKind>,       // combined relationships are allowed
    val old: RelationshipEndpoint?,        // null for a purely added subject
    val new: RelationshipEndpoint?,        // null for a purely removed subject
    val confidence: Confidence,
    val evidence: List<Evidence>,
    val group: RefactoringGroupId? = null, // one logical refactoring across several declarations
)

data class RelationshipEndpoint(val subject: Subject, val range: TextRange)

// Illustrative shapes; Q1 and Q2 decide the final confidence and group identity.
enum class Confidence { HIGH, MEDIUM, LOW }
typealias RefactoringGroupId = String

// Name, content, structure, position or reference evidence; see §5.2.
sealed interface Evidence

enum class RelationshipKind {
    RENAME, MOVE, KIND_CHANGE, EXTRACT, INLINE,
}
```

The final model is decided in the model ADR (§14, Q2). The important rule is that a relationship is **not** a `Presence` and does not replace `MATCHED`, `ADDED`, `REMOVED` or `UNCERTAIN`.

A relationship may be:
- **matched**: both endpoints exist, with any combination of rename, move or kind change;
- **extracted**: the new endpoint is added and its code came from the old endpoint;
- **inlined**: the old endpoint is removed and its code now lies inside the new endpoint;
- **grouped**: several relationships have the same `RefactoringGroupId`, such as a rename plus its changed call sites.

Content and signature changes stay `ChangeNode` attributes; they are not relationship kinds.

### 5.2 Confidence and evidence

**Confidence** is the strength of the evidence for a relationship. It is not a probability that the two programs behave the same, and it never changes a change into “safe” or hides it.

- The engine may compute a numeric score internally.
- The API exposes either a calibrated score or an ordinal (`HIGH`, `MEDIUM`, `LOW`). The choice is an open question (§14, Q1).
- The viewer shows a word and the evidence, not a bare percentage unless the benchmark has calibrated the score.
- A low-confidence relationship is a candidate, not a match. It must not be used to suppress an uncertain or added/removed node.

**Evidence** is inspectable data that supports a relationship, for example:

| Evidence | Meaning |
| --- | --- |
| Name | old and new names and their similarity |
| Content | shared and total adjacent-token pairs, or the declaration body comparison |
| Structure | old and new container paths, kinds and signatures |
| Position | old and new source order or nearest siblings |
| Reference | a resolved symbol use and the ranges it connects |

Evidence ranges are offsets into the request texts, as for every other range in the result.

### 5.3 Combined changes

A declaration may be renamed, moved, change kind, change signature and change content at once. The model must represent the combination without inventing one “refactoring kind” that loses information.

The existing `ChangeNode` attributes remain the source of truth for classification; `Relationship` adds the pairing and its evidence. A combined change is therefore:
- one relationship with several kinds, plus
- the existing change attributes (`moved`, `signatureChanged`, `content`, `kindChangedFrom`, `renamedFrom` where still applicable).

### 5.4 One-to-many and many-to-one

A relationship is a link, not necessarily a one-to-one match:

- one added target can have several extraction origins;
- one old source can have several extraction targets;
- an inlining relationship is the reverse link;
- a rename group can contain one declaration relationship and many reference relationships.

Every endpoint still appears in the source tree exactly once. Links add information; they never remove a node or hide a range.

## 6. Matching

### 6.1 Candidate generation

Keep the current keys and rules as the baseline:

- M1: kind, path, name, signature;
- M2: kind, name, signature, across paths;
- M3: kind, path, name, with signature change;
- M4: repeated keys remain uncertain;
- M4b: kind-family change;
- M5: same-kind rename candidate by content similarity.

Add optional features behind a benchmarked score:

- name similarity, including character-pair similarity and case handling;
- content similarity over token bigrams or another structural fingerprint;
- parameter-type similarity;
- container, path and nearest-sibling context;
- relative order;
- references, only in the reference-aware phase;
- source range proximity as a weak signal, never as the only signal.

### 6.2 Global assignment

The current matcher is global over the file, but `Similarity.pairs` is greedy: it takes the best option first and refuses a pair when a close rival exists. The proposal is to evaluate a maximum-weight bipartite matching (or a stable matching variant) over the candidate graph.

Requirements:

- deterministic results; ties go to old-side source order, then new-side source order;
- a minimum score and a minimum margin, so a pair is never forced;
- a bounded candidate graph, with the current `MAX_SIMILARITY_PAIRS` style limit and cancellation checks;
- the greedy result remains the baseline and the fallback when a group is too large;
- no improvement is accepted unless it is measured against the benchmark.

### 6.3 Ambiguity and duplicate bodies

Duplicate and near-duplicate bodies are the main source of wrong pairs. The matcher must not use content alone when:

- several declarations in one container have the same body;
- overloads share a name and differ only in parameter types;
- a removed declaration and an added declaration are equally close to two candidates;
- a rename group would need a reference that is not available.

In those cases:

- keep every candidate visible;
- mark the relationship low-confidence or uncertain;
- prefer the candidate supported by name, signature, position and, later, references;
- if no candidate clearly wins, leave the declarations paired with nothing rather than choosing one.

### 6.4 Combined changes

Matching must produce a relationship even when the body also changed, the declaration moved, or the signature changed. The current rules do this for M2, M3, M4b and M5; the relationship model must represent it explicitly and the benchmark must include such cases.

### 6.5 Extraction and inlining

Extraction already exists; this program generalises it:

- extraction stays a link from an added declaration to removed code in a changed declaration;
- inlining is the reverse: a removed declaration whose code now appears inside a changed declaration;
- one source may feed several targets and one target may draw from several sources;
- a refactoring group can contain the source change, the target addition and their call-site changes;
- every linked range remains source evidence; a link never hides the underlying added/removed/changed nodes.

## 7. Reference-aware rename grouping

### 7.1 Context requirement

The current engine receives two texts and a language id. ADR 0002 explicitly says providers must not resolve references or types, because diff sides are built from text with no classpath. Reference-aware rename grouping therefore cannot be added inside the existing PSI provider without changing that decision.

Two viable designs:

1. **Project-aware engine or provider capability**: the engine receives the project, file path and enough file context to resolve symbols; it still returns the same result model.
2. **Separate reference analysis pass**: a second component consumes a result plus project context, finds resolved references, and adds reference relationships to the same result.

The first is simpler for call sites; the second keeps the per-file engine pure. The choice is an open question (§14, Q3) and needs an ADR update or a new ADR.

### 7.2 Conservative grouping

When reference information is available:

- group a renamed declaration with the references that resolve to it;
- include only references the analyser can resolve with high confidence;
- keep unresolved or textual-only call-site changes as ordinary changes;
- never hide a call-site change because it belongs to a group;
- expose the resolved symbol and range as evidence;
- fall back to structural evidence when resolution is unavailable.

### 7.3 Languages

Reference support is language-specific and added one language at a time, after fixtures exist. PSI-only, text-built parsing remains the fallback path for every language. Scala's PSI is internal API; verifier warnings are reviewed as for the Scala provider.

## 8. Cross-file and multi-file scope

The per-file engine is unchanged in the first phases. Cross-file moves and commit-level refactoring detection need:

- a multi-file comparison request, or a commit-level layer that calls the per-file engine;
- path-aware endpoint identity;
- grouping and navigation across files;
- cost, cancellation and read-action rules for many files;
- a product-scope decision, because the current product specification lists commit-level cross-file refactoring detection as a non-goal.

This proposal does not schedule cross-file work. It only defines the model so that cross-file relationships can be added later without redefining a “rename” as a file-local-only concept.

## 9. Result model and contract

### 9.1 Additive model

Prefer additive fields with defaults, as the technical specification already allows:

- `ComparisonResult.relationships: List<Relationship> = emptyList()`;
- `ComparisonResult.refactoringGroups: List<RefactoringGroup> = emptyList()` or a group id on each relationship;
- `ChangeNode` keeps its current fields and adds no relationship state.

Existing engines continue to return an empty relationship list. The outline and Changes tree ignore relationships until the viewer phase.

### 9.2 Contract rules

The engine contract gains checks:

- every endpoint range is within the request text and on the correct side;
- `old == null` means the old side is absent, `new == null` means the new side is absent;
- at least one endpoint exists;
- confidence is within its declared range and high-confidence relationships satisfy the engine's documented minimum evidence;
- every evidence range is within bounds;
- a relationship endpoint is not hidden; it names a subject that appears in the result tree, or it is an explicitly unreported navigation node;
- one-to-many links are symmetric where required: an `EXTRACT` source and target agree on each other;
- relationship order is deterministic;
- nothing-hidden is unaffected: relationships add ranges, they never cover a difference that no reported change covers.

### 9.3 Engine version

`ComparisonEngine.version` must identify the engine and every parser that can change the result. The current declaration engine names its own version and the platform build; the Scala plugin parser is not part of the platform build. Add the relevant parser or provider version so results are identity-comparable across parser updates.

## 10. Viewer

Keep the existing outline as the default view. Add relationships as an additional presentation, not a replacement:

- **Refactorings view or section**: groups related declaration and reference changes, for example `rename total → sum`, `extracted describeItems`, `moved parse`.
- **Container tree remains available**: the current tree is still the way to see all declarations and change attributes.
- **Evidence on demand**: a row can show its name/content/structure/reference evidence without cluttering the default label.
- **Navigation**: selecting a relationship frames both endpoints and every reference range on the relevant side; `not present` and `not tracked` remain explicit.
- **Filtering**: the existing change-type filter stays; add relationship-type filtering only when there is a relationship model to filter.
- **No hiding**: a relationship is never a reason to hide an uncertain, unanalysed or changed node.
- **Changes tree**: the file summary can count refactorings only after the model and benchmark are stable; the current per-file summary and declaration rows stay.
- **Fallback**: the native textual diff remains the authoritative source view and the fallback for unsupported, malformed or oversized input.

## 11. Benchmark and release gates

The benchmark is separate: [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md). Before a matcher change is accepted it must report:

- precision, recall and incorrect-match rate per relationship type;
- ambiguous/uncertain rate;
- classification accuracy;
- analysis latency and slowest file;
- source coverage and nothing-hidden violations;
- fallback rate for malformed and oversized input;
- per-language results.

The current golden fixtures, contract corpus, corpus validation and limits benchmark remain part of the suite; they are not replaced by the new benchmark.

## 12. Phases

| Phase | Work | Done when |
| --- | --- | --- |
| 0 | Audit the matcher/result model and write the model ADR (§14, Q2) and the reference-context decision (§14, Q3) | The ADRs are accepted and the current model/contract are documented |
| 1 | Build the labelled benchmark and record the baseline | [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md) reports baseline precision/recall/latency/coverage |
| 2 | Add the relationship, evidence and confidence model additively | Contract tests pass; existing engines return empty relationships; no viewer change |
| 3 | Improve matching and ambiguity handling, including global assignment if the benchmark justifies it | Measured improvement over baseline, no unacceptable precision loss, deterministic and within limits |
| 4 | Add reference-aware rename grouping behind the chosen context design | Labelled intra-file reference cases pass; unreliable references remain ordinary changes |
| 5 | Generalise extraction and add inlining relationships | One-to-many and many-to-one fixtures pass; every endpoint remains visible |
| 6 | Add the refactorings view, evidence display, navigation and relationship filtering | Viewer tests pass; native diff remains the fallback |
| 7 | Cross-file/multi-file work, only if the product scope is extended | A separate API and benchmark exist; not part of phases 0 to 6 |
| 8 | Validation and release gate | Benchmark, corpus validation, Plugin Verifier and nothing-hidden checks are clean |

Each phase is a separate pull request and runs the normal build, tests and verifier. Phases 2 and 3 touch the engine contract and all engines; they do not start until phase 1 has a baseline.

## 13. Acceptance criteria

The visible acceptance criteria should be measurable after phase 1:

- **Rename and move quality**: precision, recall and incorrect-match rate on labelled fixtures, with thresholds fixed after the baseline. “High-confidence” means the documented confidence level plus the required evidence, not a subjective label.
- **Ambiguity**: duplicate-body, overload and tied-candidate fixtures produce no forced pairs; uncertain or low-confidence candidates stay visible.
- **Combined changes**: a fixture with rename + body change + move + signature change is represented as one relationship plus the existing change attributes.
- **Extraction and inlining**: one-to-many and many-to-one fixtures link correctly and keep every added/removed/changed node visible.
- **Reference grouping**: labelled cases show grouped reference changes only when the symbol resolves reliably; unresolved references remain ordinary changes.
- **Malformed and unsupported input**: the native diff remains usable; the engine returns a fallback/partial result rather than throwing.
- **Performance**: median and slowest-file latency stay within the limits of the technical specification, or the benchmark explains a deliberate change.
- **Source coverage**: every analysed result passes the nothing-hidden check; coverage is reported, not assumed.
- **Regression**: existing golden fixtures and the contract suite remain green; the corpus validation has no new wrong links or hidden changes.

## 14. Open questions

- **Q1 Confidence representation.** Is confidence an ordinal (`HIGH`, `MEDIUM`, `LOW`) or a calibrated numeric score plus an ordinal for display? What threshold is “high”? *Decided 2026-10-30: the API exposes the ordinal `HIGH`, `MEDIUM`, `LOW`; the engine's numeric score is kept in the evidence for debugging and the benchmark. The thresholds and what each level shows (default: `HIGH` pairs as a relationship, `MEDIUM` shows as a candidate as rename candidates do today, `LOW` leaves both sides added/removed with a possible link) are a `ConfidencePolicy` passed in the request like `limits`, set live through a registry key and directly by tests and the benchmark.*
- **Q2 Relationship model placement.** Are relationships top-level in `ComparisonResult`, per `ChangeNode`, or both? Does the existing `ChangeNode` retain `renamedFrom`, `extractedFrom` and `extractedTo`, or do those move into the relationship model? *Decided 2026-10-30: relationships are a top-level list in `ComparisonResult` and the source of truth. `renamedFrom`, `moved`, `kindChangedFrom`, `extractedFrom` and `extractedTo` become values computed from it, then deprecated, so the outline and Changes tree keep working unchanged.*
- **Q3 Reference context.** Does SMART Diff extend the engine request with project/file context, add a provider capability, or run a separate reference analysis pass? This requires an ADR change to ADR 0002. *Decided 2026-10-30: a separate reference analysis pass with project access adds reference relationships after the engine; the engine stays text-only and ADR 0002 is unchanged.*
- **Q4 Cross-file scope.** Is cross-file matching a later engine feature, a commit-level layer, or still a product non-goal? *Decided 2026-10-30: later, as a commit-level layer over per-file results; the per-file engine and its contract stay unchanged. This lifts the product specification's non-goal once that phase is scheduled.*
- **Q5 Language order.** Which language gets reference-aware grouping first: Java, Kotlin or Scala? *Decided 2026-10-30: Java, Kotlin and Scala together in phase 4: a language-neutral pass with one resolver per language. The Kotlin and Scala resolvers get a Plugin Verifier review on both target IDEs before that phase merges.*
- **Q6 Viewer default.** Does the outline open with the container tree, the refactorings view, or a toggle between them? *Decided 2026-10-30: a toolbar toggle switches between the container tree and the refactorings view; the choice is remembered as a setting.*
- **Q7 Benchmark labels.** What is the ground-truth unit: a declaration pair, a relationship, a file, or a refactoring group? How are label disagreements resolved?
- **Q8 Performance budget.** Should the analysis-time target stay at the current limits, or may a relationship pass add a bounded extra cost?
- **Q9 “Semantic” wording.** The proposal should say “structural relationship detection” where it means matching, and reserve “semantic” for language-aware structure. It must not imply runtime equivalence. *Decided 2026-10-30: the files are named STRUCTURAL-RELATIONSHIPS.md and RELATIONSHIP-BENCHMARK.md.*
- **Q10 API stability.** The extension point is internal today. The relationship model can still change, but every change updates the contract suite and every engine.

## 15. Risks and dependencies

- **Overmatching.** Global assignment and confidence ranking can increase false pairs. Conservative margins must survive the change; the benchmark decides.
- **Model churn.** Relationships, confidence and evidence touch the API, contract suite, all engines, the outline and the Changes tree. Ship the model additively and without UI pressure.
- **Reference resolution.** Text-built PSI has no project classpath; resolving references needs read actions and a new context decision. Do not assume it works for transient VCS content.
- **Performance.** Optimal assignment, reference resolution and many-to-many links add work. Keep the size limits, cancellation checks, determinism and greedy fallback.
- **Semantic claims.** A confident relationship is not a behavioural claim. ADR 0003 remains in force.
- **Scope creep.** Cross-file moves, variable renames and statement reordering are separate capabilities. Do not schedule them inside the per-file matcher phase.
- **Benchmark cost.** Labelled fixtures and real-commit review take time; starting without a baseline would make the matcher changes unfalsifiable.
- **Language support.** Reference-aware analysis is language-specific; Scala PSI is internal API and needs verifier review.

## 16. Related documents

- [RELATIONSHIP-BENCHMARK.md](RELATIONSHIP-BENCHMARK.md)
- [Product specification](SPECIFICATION.md)
- [Technical specification](TECHNICAL-SPECIFICATION.md)
- [Roadmap](ROADMAP.md)
- [Extraction detection](EXTRACTION-DETECTION.md)
- [Three-way merge](THREE-WAY-MERGE.md)
- [Glossary](GLOSSARY.md)
- [ADR 0001: declaration-level first release](adr/0001-declaration-level-first-release.md)
- [ADR 0002: parser-neutral model with PSI first](adr/0002-parser-neutral-model-with-psi-first.md)
- [ADR 0003: conservative classification](adr/0003-conservative-classification.md)
- [ADR 0004: pluggable comparison engines](adr/0004-pluggable-comparison-engines.md)

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Phase 1 cannot label relationships before a taxonomy exists. Add the minimal relationship taxonomy and label schema to Phase 0/1, then build the full model in Phase 2.
- Add a current-engine adapter section explaining how existing ChangeNode rename/extraction fields map to benchmark labels before the new model exists.
- §6.4 says a signature-only change must produce a “relationship”; that is a pairing/change attribute. Say “pairing” or “match”.
- Confidence, Evidence and RefactoringGroupId are illustrative; state explicitly whether confidence is ordinal or numeric and whether groups are top-level or per node.
- Define deterministic ordering for Set<RelationshipKind> and evidence lists.
- State what happens to relationships when the result is PARTIAL, TOO_LARGE or FAILED.
- Make the ADR 0002 conflict an explicit “requires amendment” item, not only a design option.
- Add a concrete viewer acceptance test for the refactorings view and evidence display.

> **Model author:** Claude Opus 5.5 · 2026-10-30. These are review notes; the normative text above is unchanged.

- §5.1 makes `MOVE` and `KIND_CHANGE` relationship kinds, while §5.3 keeps `moved` and `kindChangedFrom` as `ChangeNode` attributes. That is two sources of truth: drop those kinds, or add a contract rule that they always agree. The same applies to `RENAME` and `renamedFrom`.
- §6.2 says ties go to old-side source order; §6.3 says nothing is paired when no candidate clearly wins. State that the margin check comes first and the order rule only orders the output.
- §5.4 says every endpoint appears in the tree exactly once; §9.2 allows "an explicitly unreported navigation node", which is not defined. Pick one.
- `RelationshipEndpoint` holds a `Subject`, which is a declaration or a region today. The call-site endpoints of §7 need a new subject kind; say so in §5.
- Say how today's M5 rename candidates (`MATCHED` with `renamedFrom` and a "rename candidate" label) map to confidence, and whether a `LOW` relationship still sets `MATCHED`.
- §4 mentions only the 0.1 margin. `DeclarationMatcher` also has a name tie-break (`TIEBREAK_MIN` 0.5, `TIEBREAK_MARGIN` 0.2); the global assignment must keep it or replace it explicitly.
- Q9 asks to reserve "semantic", yet this file and RELATIONSHIP-BENCHMARK.md use it in their names and titles. Rename them (for example STRUCTURAL-RELATIONSHIPS.md) before links spread. Done 2026-10-30.
- The terms relationship, confidence, evidence, rename candidate and inlined are not in GLOSSARY.md, although this file says it follows the glossary.
- The header says this program expands the three-way merge roadmap item, but no section says how relationships appear in the merge join. Add one line or drop the claim.

> **Model author:** OpenAI Codex (GPT-6) · 2026-10-30. Review comments; these do not change proposal decisions.

- §5.3 risks two sources of truth: relationship kinds include `RENAME`, `MOVE` and `KIND_CHANGE`, while ChangeNode retains `renamedFrom`, `moved` and `kindChangedFrom`. Define precedence or make the relationship model canonical before designing migration.
- §5.4 says each endpoint appears in the source tree exactly once, but reference relationships in §7 may point to many usage ranges and those usages are not declarations in today’s tree. Define reference endpoints and navigation targets explicitly.
- Phase 0 should freeze the minimum label taxonomy before Phase 1 benchmark labeling; otherwise the baseline cannot consistently encode relations the current model does not represent. The adapter comment in the other review notes is a good candidate for this bridge.
