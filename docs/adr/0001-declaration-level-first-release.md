---
status: accepted
---

# Declaration-level analysis for the first release

The first release tells reviewers which declarations changed and leaves line and expression detail to the native diff. It does not diff syntax trees node by node. The product's core questions, such as "which methods changed?" and "where is this one on the other side?", can be answered by matching declarations by identity, which is deterministic and easy to explain. Fine-grained tree matching is where published AST-diff tools produce most of their wrong mappings, and it would bring either a large matcher or a native engine (Difftastic, GumTree) into the first release.

## Considered Options

- **Fine-grained syntax-node diff (own matcher or Difftastic/GumTree):** shows expression-level edits, but needs months of tuning or native packaging per OS, and its mapping errors are hard to explain to a reviewer.
- **Declaration-level diff with the native diff as the detail view (chosen):** smaller, deterministic, and keeps the familiar line diff for detail.

## Consequences

- Inside a changed declaration, the reviewer reads the native line/word diff; SMART Diff does not highlight changed expressions.
- Fine-grained differencing can be added later behind the same model without changing what a declaration change means.

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Add a note that fine-grained differencing, statement moves and the semantic relationship program remain later work; this ADR still defines the first-release boundary.
