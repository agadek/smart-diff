# Specification Gaps

Status: review notes, 2026-10-30. These are gaps found by comparing the product and technical specifications with the current implementation and roadmap. They are not yet accepted product decisions.

## 1. Reconcile documented scope with shipped behavior

The product specification still describes the viewer integration as undecided and the first-release language list as open. The technical specification scopes the first release to Java and Kotlin, while the README and implementation also include Scala 2 and Scala 3. The roadmap marks the Changes tree summary and extraction detection as implemented, but those capabilities are not integrated into the product specification's current scope.

**Gap:** There is no single current statement of which features and languages are shipped, which are optional-plugin dependent, and which remain proposals.

**Specification work:** Set a canonical shipped-scope section. Align the product spec, technical spec, roadmap, and README with it. Mark older decisions as historical where they no longer describe current scope.

## 2. Define visible fallback states

The product spec asks for a clear unsupported-language state and native diff fallback. In the current implementation, unsupported content or a missing engine leaves the native diff in place without the outline. The Changes tree similarly omits summaries when either revision is missing or the result cannot be analyzed.

**Gap:** The specifications do not define what the user sees for unsupported languages, parse failures, timeouts, oversized inputs, or engine failures, including whether the fallback should explain why structural analysis is absent.

**Specification work:** Define the visible behavior and message for each outcome, where it appears, and whether the user can retry or change settings from that state. Preserve the native diff in every case.

## 3. State Changes tree coverage boundaries

The technical specification says each eligible changed file can receive a summary, but implementation only summarizes files with both revisions and processes at most 500 file nodes in a tree. Added and deleted files therefore have no summary, and large change lists may have unsummarized entries.

**Gap:** These user-visible boundaries are not stated alongside the product behavior, nor is it clear whether they are temporary limits or intended behavior.

**Specification work:** State the exact eligible change types and large-list behavior. Decide whether added and deleted files should receive structural summaries and define a policy for change lists over the current cap.

## 4. Make classification behavior discoverable

The product specification summarizes uncertainty and rename candidates, while detailed matching thresholds and extraction rules are distributed across the technical specification and feature proposals.

**Gap:** Normative behavior is spread across documents, making it difficult to know which rules are stable and how related classifications interact. In particular, extraction links, rename candidates, filters, and summary counts need one clear explanation.

**Specification work:** Keep algorithm thresholds in the technical documents, but define user-visible classification semantics in one canonical section. Describe how uncertain matches and rename candidates appear, which filters affect them, and how extraction affects counts and navigation.

## 5. Turn usability goals into measurable acceptance criteria

The product success criterion says reviewers should identify changed declarations faster than by scanning a raw diff, but does not define the task, participants, baseline, or pass threshold. Corpus validation checks analysis quality, not reviewer outcomes.

**Gap:** There is no repeatable way to determine whether the product solves its primary usability problem.

**Specification work:** Define a small usability protocol with representative review tasks, a baseline workflow, participant profile, and measurable success threshold. Keep usability results separate from corpus classification metrics.

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Link the semantic matching program and benchmark so the two review tracks are not confused.
- In §3, clarify that the technical specification already states the 500-file cap and added/deleted-file behavior; the gap is that the product specification does not.
- Add an owner/decision status to each gap: proposed, accepted, rejected or superseded.
- Fold accepted items into the normative specifications and mark the review note historical or move it under docs/reviews/.

> **Model author:** Claude Opus 5.5 · 2026-10-30. These are review notes; the normative text above is unchanged.

- Gap 3 overstates the problem: TECHNICAL-SPECIFICATION.md §9.4 already says added and deleted files get no summary and that at most 500 files are summarized. The real gap is that the product specification does not say so, and there is no decision on whether these limits are intended or temporary.
- Gap 2 overstates the problem: TECHNICAL-SPECIFICATION.md §10 already defines status messages for `UNSUPPORTED`, `TOO_LARGE` and `FAILED`, and a banner for `PARTIAL`. The real gap is that `OutlineDiffExtension` attaches nothing when no engine supports the language, so `UNSUPPORTED` is never shown, contrary to the product specification's "clear unsupported-language state". The Changes tree is also silent for every file it does not analyze.
- Gap 1 is accurate: TECHNICAL-SPECIFICATION.md §1 still scopes the release to Java and Kotlin while §6.8 specifies the Scala provider, and SPECIFICATION.md still lists two viewer integrations although ADR 0005 decided.
- Say where each accepted gap is closed (an edit to SPECIFICATION.md or TECHNICAL-SPECIFICATION.md) and what happens to this file afterwards.

> **Model author:** OpenAI Codex (GPT-6) · 2026-10-30. Review comments; these do not change gap status.

- Revise Gap 2 and Gap 3 to distinguish “missing from the product spec” from “missing from all specs”: the technical spec already defines result status messages and Changes tree limits. The remaining questions are implementation conformance, product-level discoverability, and whether the limits are intended.
- Add semantic matching and benchmark proposals to the scope of this review, or explicitly state that this file covers only first-release/product-spec gaps.
