---
status: accepted
---

# Changed runs come from the declaration engine

The [roadmap](../ROADMAP.md#fine-grained-differencing) asked for fine-grained differencing as a second comparison engine that highlights changed expressions inside declarations, compared with Difftastic and GumTree as baselines. [ADR 0001](0001-declaration-level-first-release.md) left this out of the first release and said it could be added later behind the same model. This record decides where it lives.

## Decision

- The declaration engine compares the tokens of each changed declaration and reports the [changed runs](../GLOSSARY.md#changed-run) as one more field of `ChangeNode`. It is not a second engine.
- The comparison is a project-owned token diff over the tokens the engine already has, normalized by the language's whitespace policy. It does not match syntax-tree nodes.
- Only the SMART Diff viewer and the outline's selection read the runs. Classification, labels, the summary line, the Changes tree and the merge outline do not change, and the native viewers keep their own highlights.
- Difftastic and GumTree are baselines in the validation only, run from the developer's machine; the plugin does not ship or call them.

The feature is specified in [declarations/10](../specs/declarations/10-fine-grained-differencing.md).

## Considered Options

- **A second comparison engine:** the roadmap's first idea. The engine dropdown picks one engine per language, so a reviewer would choose between the declaration outline and the highlights, and the engine would repeat the matching to know which declarations to compare.
- **Difftastic or GumTree inside the plugin:** syntax-aware and tested elsewhere, but Difftastic is a native binary per operating system and GumTree brings its own parsers and an LGPL licence; neither uses the IDE's PSI, so their tokens would not line up with the declarations without a second parse.
- **Token runs from the declaration engine (chosen):** reuses the lexer tokens, offsets and whitespace policies that classification already trusts, is deterministic, and falls back to the native word highlights wherever it has no answer.

## Consequences

- The product scope's exclusion of expression-level differencing becomes an exclusion of syntax-tree matching inside a declaration.
- `DeclarationEngine.VERSION` goes up, and the engine contract checks that every run lies inside its node.
- A run can be larger than the edit a reviewer would name, never smaller; whether to widen runs to whole expressions is left to the validation.
