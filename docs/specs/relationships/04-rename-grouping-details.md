# Rename grouping: details

Status: proposed, 2026-11-01

Feature: [Rename grouping](04-rename-grouping.md)

## Design

### Where it plugs in

The engine stays text-only ([ADR 0002](../../adr/0002-parser-neutral-model-with-psi-first.md)). A separate `ReferencePass` runs after it in `OutlineController`, on a background thread, in a cancellable read action:

1. The engine's result is shown at once.
2. When the diff's file is in the open project, `ReferencePass.run(project, file, request, result)` returns the same result with usage relationships added.
3. The outline replaces its model with the new result, keeping the selection.

Without a project file (a diff of two clipboard texts, for example) the pass does not run.

### Data

A usage is added to the rename relationship as evidence and as endpoints:

```kotlin
data class Reference(val symbol: String, val oldRange: TextRange, val newRange: TextRange) : Evidence
```

The rename relationship gets a `usages` count in its label, and each usage's enclosing `ChangeNode` keeps its change.

### Resolution

One `ReferenceResolver` per language, registered like the declaration providers:

- Java: PSI references resolved against the project file's PSI for the new side, and against a light copy of the old text placed in the same package for the old side.
- Kotlin: the Kotlin plugin's analysis API on the same two files.
- Scala: the Scala plugin's resolver, in its optional descriptor next to the Scala provider.

A usage is grouped when:

- the rename is high confidence;
- the usage's old range resolves to the old declaration and its new range to the new declaration;
- the only change between the two ranges' surroundings, after removing the name, is none: the token sequences around it are equal.

Everything else stays an ordinary change. A declaration whose every change is a grouped usage is labelled `usages renamed`.

### Thresholds and reasons

- At most 1 s per file beyond the engine: the outline already waits for the engine; a second wait longer than that is noticeable. Proposed; the benchmark's timings will confirm it.
- High confidence only: a usage group on a wrong rename would mislabel many lines at once.

### Failure behaviour

When resolution fails, times out or is cancelled, the outline keeps the engine's result. An index that is not ready (dumb mode) skips the pass.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `OutlineIntegrationTest`: the engine result shows, then the grouped result |
| R3 | A diff with no project file has no usages |
| R4–R7 | `ReferencePassTest` fixtures: a medium rename, an unresolved call, a call to another overload, a call with another change |
| R8 | The nothing-hidden check over every fixture, and a filter test |
| R9–R11 | `OutlineModelTest` labels and navigation ranges |
| R12 | Evidence test |
| R13 | A fixture with a usage in another file |
| R14 | The same fixture in Java, Kotlin, Scala 2 and Scala 3 |
| R15 | Plugin Verifier report review |
| R16, R17 | Cancellation and time limit tests |
| R18 | `RelationshipBenchmarkTest` with usage labels |

## Open questions

- Should a usage changed for another reason as well be shown inside the group, marked as also changed?
