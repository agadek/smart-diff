# Specification Improvement Proposals

Status: proposals from the specification review, 2026-10-30. These proposals do not change product behavior until accepted and incorporated into the normative specifications or roadmap.

## 1. Establish a canonical source of truth

Add a concise current-scope section to the product specification covering shipped capabilities, language support, optional dependencies, and known limitations. Link detailed behavior to the technical specification and future work to the roadmap. Update the README from the same scope statement.

**Acceptance criteria:** Java, Kotlin, Scala 2, and Scala 3 support status is unambiguous; Changes tree summaries and extraction detection are identified as shipped; proposed features are not presented as current behavior.

## 2. Specify fallback UX

Document a state matrix for unsupported language, parse errors, partial analysis, unavailable engine, timeout, size/declaration limits, and unexpected engine failure. For each, define whether the outline is absent or shown with a status, what explanation the user sees, and what native fallback remains available.

**Acceptance criteria:** A user can tell when structural analysis did not run and why, without losing access to the ordinary diff. If a state has no actionable recovery, the spec says so explicitly.

## 3. Specify Changes tree coverage and scale

Document which VCS change types receive summaries, what happens to added/deleted files, and how limits apply to large change lists. Specify whether entries after the current 500-file cap remain ordinary rows, and whether the cap is configurable or an implementation detail.

**Acceptance criteria:** The behavior for modified, moved, added, deleted, unsupported, unanalyzable, and over-limit files is explicit and consistent between the product and technical specifications.

## 4. Consolidate classification semantics

Add a user-facing classification table to the product specification for changed, added, removed, moved, formatting-only, uncertain, unanalyzed, rename candidate, and extracted changes. Include visibility/filtering behavior and how summary counts treat each class. Leave numeric thresholds and matching algorithms in the technical specification or feature-specific proposal.

**Acceptance criteria:** A reader can understand the label and its effect in the outline and Changes tree without reconstructing behavior from multiple documents.

## 5. Add usability validation

Define a small, repeatable comparison of structural review and ordinary diff review. Choose representative multi-hunk changes, a task completion measure, and a minimum improvement or usability threshold. Record the method and results in a dedicated validation section.

**Acceptance criteria:** The success criterion is measurable and repeatable, with enough detail for a later release to compare results against the baseline.

## Suggested order

1. Reconcile the scope and supported-language statements.
2. Specify fallback behavior and Changes tree boundaries.
3. Consolidate classification semantics.
4. Define and run usability validation.

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Link STRUCTURAL-RELATIONSHIPS.md and RELATIONSHIP-BENCHMARK.md as separate proposal tracks.
- Add an owner/decision status to each proposal.
- In §3, decide whether the 500-file cap is an implementation detail or a configurable setting.
- Fold accepted proposals into the normative specifications, then mark the review note historical.

> **Model author:** Claude Opus 5.5 · 2026-10-30. These are review notes; the normative text above is unchanged.

- Proposal 2 should start from the existing status messages in TECHNICAL-SPECIFICATION.md §10 and focus on the unsupported-language case and the Changes tree, rather than a full new state matrix.
- Proposal 3: the change types and the 500-file cap are already in TECHNICAL-SPECIFICATION.md §9.4; the work is copying them to the product specification and deciding whether they are intended.
- Proposal 4's classification list leaves out "kind changed" (0.1.1) and "signature changed". Add both.
- Proposal 4 depends on STRUCTURAL-RELATIONSHIPS.md Q2: if relationships replace `renamedFrom`, `extractedFrom` and `extractedTo`, the user-facing table changes. Write the two together, or do proposal 4 after the model ADR.

> **Model author:** OpenAI Codex (GPT-6) · 2026-10-30. Review comments; these do not change proposal status.

- Separate documentation corrections from product decisions. Aligning stale Scala/release status is editorial; adding an explanatory unsupported-language state or changing large-list behavior may change UX and should be decided explicitly.
- Add a proposed priority and dependency for each item. In particular, settle the relationship model before finalizing classification labels, and decide unsupported/Changes-tree behavior before usability validation.
