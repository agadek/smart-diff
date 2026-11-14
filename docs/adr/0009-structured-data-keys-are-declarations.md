---
status: proposed
---

# Structured data: keys are declarations of the declaration engine

JSON, YAML, TOML, properties and XML files have keys and values, not classes and methods. The [format coverage plan](../FORMAT-COVERAGE.md#4-structured-data-group-c) brings them into SMART Diff, because reordered keys, reformatting and renamed keys are the usual noise in configuration reviews. They could get their own comparison engine ([ADR 0004](0004-pluggable-comparison-engines.md)) or reuse the declaration engine with keys as [declarations](../GLOSSARY.md#declaration).

## Decision

- Each format gets a provider of the declaration engine, like a language. A key, a tag or a section is a declaration, its nested keys are its members, and its value is its content.
- New kinds: `KEY` (JSON members, YAML entries, TOML keys, properties), `TAG` (XML and HTML) and `SECTION` (TOML tables, YAML documents, Markdown headings).
- Arrays are content of their key, except arrays of objects that share an identifying key (`name`, `id`, `key`), whose items become declarations named by it. Items are never matched by position.
- A reorder of keys whose order carries no meaning is reported as moved, the existing label, and never hidden by default.
- Whitespace follows each format's policy; YAML stays `SIGNIFICANT` until a tested indentation rule exists ([ADR 0003](0003-conservative-classification.md)).

## Considered Options

- **A separate data engine (rejected for now):** it could compare values by type (numbers, booleans) and treat arrays as sets, but it would need its own matcher, classifier, relationship pass and tests, and the outline, Changes tree and merge outline would get a second engine's results with no new label to show.
- **Key paths as flat rows (rejected):** a list of `a.b.c` paths is easy to diff but loses the outline's nesting, and the merge join works on trees.
- **Keys as declarations (chosen):** the matcher, rename candidates, moves, repeated keys, the filter, the Changes tree and the merge outline work unchanged; each format needs only a provider and its fixtures.

## Consequences

- The glossary's *declaration* covers keys, tags and sections; its definition gains one sentence when D0 lands.
- Value-aware rules, such as `1.0` equal to `1` or set semantics for arrays, are out of scope; they would be equivalence claims that ADR 0003 rules out without a dedicated, tested rule.
- Extraction and inlining detection do not apply to data files, since keys have no bodies.
- Very large data files (lock files, generated JSON) are stopped by the [analysis limits](../GLOSSARY.md#analysis-limits) as any other file is.
