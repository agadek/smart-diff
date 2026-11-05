# Rename grouping: details

Status: accepted, 2026-11-05

Feature: [Rename grouping](04-rename-grouping.md)

## Design

### Where it plugs in

The engine stays text-only ([ADR 0002](../../adr/0002-parser-neutral-model-with-psi-first.md)). A separate `ReferencePass` runs after it in `OutlineController`, on a pooled thread, in the existing cancellable read action:

1. The engine's result is shown at once.
2. When the diff's file belongs to the open project, `ReferencePass.run(project, file, request, result)` returns a copy of the result with reference evidence and usage ranges added.
3. The outline replaces its model with the new result, keeping the selection.

`OutlineDiffExtension` already has the `DocumentContent` of each side. It passes the new side's `highlightFile` (or the old side's when the new side has none) to `OutlineController` as a `VirtualFile`, next to the file name. The pass runs only when that file is not null and `ProjectFileIndex.isInContent(file)` is true; a file under the project but outside its source roots still qualifies. A clipboard diff or a file from a library has no such file and keeps the engine's result (R3).

The pass runs in the two-sided diff outline only (R19):

- **Changes tree:** no pass. `ChangesSummaryService` keeps computing the engine's result and caches it by the file paths, each side's version, the engine id and the confidence policy. Its summary and rows therefore show the engine's labels only, with no `N usages` note and no `usages renamed`. Running the pass for up to 500 files per tree would multiply the cost and there is no viewer in the tree to navigate usages; the file's diff gets the grouped result when it is opened. The cache key does not change because no new input reaches it.
- **Merge outline and three-sided diffs:** no pass. `MergeComparison` runs the engine base → left and base → right and `MergeJoin` compares the two results; there is no single old/new pair to resolve against. The merge outline and the merge window keep the engine's result, exactly as `MergeOutlineController` does today.
- **Caching:** `OutlineController` has no result cache. A document edit, an engine change or a refresh runs the engine and the pass again. A filter change reuses the shown result through `shownRequest`/`state` and so keeps the grouped result without resolving again. The pass result lives only in the controller's state and is discarded with the viewer. If a cache is added later, its key must include the reference-pass version and the confidence policy.
- **Version:** `DeclarationEngine.VERSION` does not change, so [relationships/02](02-relationship-model.md) R24 is unchanged: the engine returns the same result with or without the pass, and its version still names only the parsers and matcher that produced it. `ReferencePass.VERSION` is separate, starting at `1`, and is raised when a resolver or the grouping rule changes. The benchmark and validation reports record it next to the engine version; two outline results from the same engine can differ by pass version.

### Data

A usage is added to the rename relationship as reference evidence and never as an endpoint:

```kotlin
sealed interface Evidence {
    // Name, Content, Structure, Position and Score as in relationships/02.
    data class Reference(
        val symbol: Subject.Declaration,
        val oldRange: TextRange,
        val newRange: TextRange,
    ) : Evidence
}
```

- `symbol` is the new-side declaration the usage resolves to. It is the rename relationship's new endpoint's subject, so the evidence names the exact declaration, not just a string. `oldRange` is the usage on the old side and `newRange` the usage on the new side; both are offsets into the compared texts.
- The relationship's endpoints stay the renamed declarations. A usage is not a declaration and has no node in the result tree, so making it a `RelationshipEndpoint` would break R16 of [relationships/02](02-relationship-model.md). Reference evidence is the only addition; R2 and R16 of that feature stay as they are.
- Reference evidence is allowed only on a relationship that has the `RENAME` kind. It is ordered after `Position` and before `Score`, and several references are ordered by old range start, then new range start, so two runs give equal lists (relationships/02 R19).
- The pass also adds each usage's `RangePair(oldRange, newRange)` to the enclosing declaration's `ChangeNode.details`, so the usage is a reported span of that node and `OutlineNavigator` can frame it on both sides. The node keeps its presence and its other change attributes; the usage never replaces or hides it (R8).
- `OutlineModel` reads the references from the result. It labels a matched declaration `usages renamed` when its reported details are exactly the grouped usage pairs and it has no other change; otherwise the declaration keeps `changed` and the usages stay part of that change. The rename row gets the note `12 usages`. `N usages` is a note, not a separate change count; `usages renamed` is a label with its own summary count (see [declarations/06](../declarations/06-change-labels.md)).

The engine contract checks the new evidence through the same result:

- every reference and endpoint range lies inside its side's text (relationships/02 R17, extended to reference ranges);
- a reference's `symbol` equals one of the relationship's new endpoints' subjects;
- `Reference` evidence appears only on a `RENAME` relationship;
- the fixed order holds, including references ordered by old and then new range start (R19);
- the endpoints still name nodes in the result tree: R16 is unchanged, because a usage adds no endpoint.

`ReferencePassTest` runs the same relationship checks on the pass's result, and `RelationshipModelTest` checks the new evidence type. The pass is not an engine, so it is not added to `ComparisonEngineContract` as an engine; the contract's checks are reused on its result.

### Resolution

Both sides are light copies of the request texts, made with `PsiFileFactory.createFileFromText` in the language and dialect the engine used. Nothing is written to disk and the copies are not added to a module or an index. This is enough for a usage that refers to a declaration in the same file, which is the only kind R13 allows. A usage is found with `PsiFile.findReferenceAt(offset)` or by walking `getReferences()` and is resolved with `PsiReference.resolve()`.

| Language | API | Cost | Verifier |
| --- | --- | --- | --- |
| Java | `PsiJavaFile`, `PsiReferenceExpression`, `PsiJavaCodeReferenceElement`, `PsiReference.resolve()` | Low: public platform PSI, same as the provider | No internal or experimental API; the report is clean |
| Kotlin | `KtFile`, `KtReferenceExpression.references` (a `KtReference`), `PsiReference.resolve()` | Medium: test both plugin modes and the latest PSI | No Analysis API is called, so no `KaSession`/`KaExperimentalApi` usage and no K1/K2 adapter; the Kotlin PSI is the one the provider already uses |
| Scala | `ScalaFile`, `ScReferenceExpression`, `ScReference.resolve()` | Medium-high: internal plugin PSI, two dialects, plugin-version changes | The verifier reports the Scala PSI it can see, such as `PsiNamedElementWithCustomPresentation`; the resolver's own classes are reviewed in a run with a Scala plugin installed for each target IDE, as the Scala provider needs |

- **Kotlin modes:** the resolver uses the Kotlin PSI's reference implementation, not the Kotlin Analysis API. A spike on 2025.1.3 resolved a same-file function call from a `PsiFileFactory` file in both K1 and K2 mode, so no mode switch is needed. On a latest release that no longer offers K1, the same PSI path runs in K2. The 2025.1.3 spike is the recorded check; the latest release uses the same public PSI and is covered by the resolver's R14 test matrix when the resolver is built. If a later Kotlin plugin changes that PSI, the PSI resolve is still the only entry point; the fallback is no grouping, never a wrong group.
- **Scala:** the same spike resolved a same-file `def` from a `PsiFileFactory` file with the pinned Scala plugin. The plugin's PSI is not public API, so the resolver must be version-checked against the Scala plugin of each target IDE. The resolver must catch `ProcessCanceledException` and any resolver failure, and treat that usage as unresolved.
- **Dumb mode:** the pass skips when `DumbService.isDumb(project)` and does not wait for smart mode, because waiting would block the outline. Same-file resolution may work without the index, but skipping is the conservative rule and avoids `IndexNotReadyException`.
- **Files outside source roots:** the light copies do not need a source root or module; the spike's files were in a test project with no source root and resolved. If a language's resolver still returns no declaration there, the usage is ordinary; R5 is not weakened.
- **Old side:** the old text is not placed in the project or in a module. It is only a second light copy, and the pass resolves the old usage against the old copy's declaration, then the new usage against the new copy's declaration. Both must resolve for the usage to group (R5). A usage that resolves to another declaration, or to none, stays ordinary (R6).
- **Only the compared file:** a usage in another file is not in the light copy and is never visited (R13).
- **No language is dropped for resolution:** all three resolvers resolve a same-file usage from a light copy. Scala is the one that needs the extra verifier setup; if the maintainer does not want a Scala plugin in `pluginVerification`, Scala should leave the first step rather than skip R15, because its non-public PSI would otherwise be unreviewed.

### Grouping rule

A usage is grouped when all of these hold:

- the rename relationship's confidence is `HIGH` under the request's `ConfidencePolicy`; the pass does not re-score it;
- the old range resolves to the old endpoint's declaration and the new range resolves to the new endpoint's declaration;
- the usage is the rename's only change: after removing the two usage ranges and mapping the old declaration's name to the new one as `DeclarationAnalysis` already does, the old and new texts of the enclosing declaration are equal under the language's whitespace policy.

A constructor reference resolves to a constructor; when its containing class is the relationship's endpoint, the pass uses the class as the symbol, so `new Order()` follows a renamed `Order`. A constructor whose class did not rename is not grouped.

Everything else stays an ordinary change. A usage in the file region has no enclosing declaration to label, so it is not grouped and the file region keeps its ordinary change. A declaration whose every changed span is such a usage is labelled `usages renamed`; a declaration with one other change keeps `changed`, and its usages still appear in its details and on the rename row.

### Confidence

R4 groups only high-confidence renames. The policy is the one the request already carries:

- `smartdiff.relationships.confidence`, declared in `plugin.xml` with the default `0.82,0.7` as `high,medium`; `SmartDiffSettings.confidence` reads it when `OutlineController`, `ChangesSummaryService` and `MergeOutlineController` build a request.
- The engine has already classified the relationship. The pass tests `relationship.confidence == Confidence.HIGH`; it never calls `ConfidencePolicy.classify` on the score again.
- Raising `high` groups fewer renames. Lowering `high` (it must stay at or above `medium`) groups more medium renames, because a medium relationship becomes high on the next comparison. Raising `medium` can move a pair between 0.7 and the new medium to low, and a low relationship never groups. Lowering `medium` below 0.7 forms no new pairs, because the matcher's pairing floor stays 0.7. No policy change changes which declarations are paired (relationships/03 R19).
- A registry change takes effect on the next comparison or the next Changes tree summary; there is no registry listener that refreshes an open outline. The Changes tree cache key includes the policy string, so a summary is recomputed under the new policy. A malformed value keeps the default and logs one warning (relationships/02 R30).
- The 0.3.0 matcher, as recorded in the 2026-11-04 relationship matching run, is the latest recorded benchmark run. It reports 89 rename relationships: 69 high (77.5%), 20 medium and 0 low. By language: Java 19 high and 5 medium of 24; Kotlin 18 and 3 of 21; Scala 2 17 and 6 of 23; Scala 3 15 and 6 of 21. Precision is 1.000 overall, so none of the high renames is a wrong pair on that corpus. The tuning split at `high = 0.82` keeps 43 of its 57 correct renames high (coverage 0.754) with precision 1.000; three quarters was the chosen floor. About one in four renames therefore stays ungrouped by design, which is conservative and keeps P3; the maintainer can lower `high` to group more, at the cost of grouping less certain renames.

### Threading and limits

- The pass runs on the pooled thread that already runs `compareSafely`, after the engine returns, never on the UI thread. It runs in `cancellableReadAction`, which uses `ProgressManager.runInReadActionWithWriteActionPriority`, so a write action cancels and restarts it and typing in the new side never waits for a resolve. It holds no PSI after the read action: the result stores only subjects, ranges and counts.
- While the engine runs, the outline shows `Loading` as today. When the engine result arrives, the outline shows it immediately, with a grey `Resolving usages…` note while the pass runs. The note is a label, not a modal progress bar, and the rows stay selectable. When the pass finishes, the controller replaces the model with the grouped result and keeps the selection (R2).
- The pass gets `ReferencePass.BUDGET_MILLIS = 1_000` per file, measured from the start of the pass. It checks the deadline and `cancellation.checkCanceled()` before each resolve and after each one; a resolve that returns after the deadline causes the whole usage addition for that result to be discarded, so a partial group is never shown (R17). The engine's 5 s limit, 1 MB size limit and 5 000-declaration limit are unchanged; the pass is a second, bounded step after the engine, not part of the engine's time. The engine result is already on screen, so the first useful result still waits at most the engine limit and grouping adds at most 1 s.
- If the pass times out or cannot resolve because the project is dumb, the engine result stays and the note changes to a grey `usages not grouped` for that file. If the user cancels (an edit, hiding the outline or disposing the viewer), the pass stops and no note is added, because the whole comparison is stale (R16). It never shows `Analysis failed`, because the engine succeeded; a failed or timed-out engine runs no pass.
- A `PARTIAL` result can run the pass only for relationships between analyzed declarations; an unresolved or parse-error range stays ordinary. `UNSUPPORTED`, `TOO_LARGE` and `FAILED` results run no pass.
- A resolver failure on one usage skips that usage and logs at debug level; it does not discard the other usages. A failure of the pass itself, such as an unexpected exception, discards all usage evidence and keeps the engine result.

### Benchmark

R18 needs usage labels, and they do not exist yet. This is the largest part of the work, larger than the pass itself.

The label gains an optional `usages` list on a relationship:

```yaml
expected:
  relationships:
    - kinds: [RENAME]
      old: { path: "class Order", kind: METHOD, name: total, signature: "()" }
      new: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
      usages:
        - old: { start: 118, end: 123 }
          new: { start: 116, end: 121 }
```

- A usage label is a range pair on the two sides. The relationship's endpoints identify the renamed declaration, so the usage needs no separate symbol identity. `usages: []` means the label was reviewed and the rename has no usages; an absent `usages` means the label has not been reviewed for usages.
- A reported `Evidence.Reference` is matched to the label with the same relationship endpoints and the same old and new ranges. A reported reference on a relationship with `usages` but no matching pair is a wrongly grouped usage. A reported reference on a relationship with no `usages` is counted as `usage-unlabelled` and not scored, so an incomplete corpus is visible rather than silently failing.
- The case matrix gains `usage-grouping`, with at least four cases per language (Java, Kotlin, Scala 2, Scala 3), four times the existing minimum of three per category:
  1. a rename with one usage;
  2. a rename with twelve usages, the use case's case;
  3. a rename with a usage that resolves to another overload and a usage that does not resolve, both expected ordinary;
  4. a rename with a usage that also changed for another reason, expected ordinary.
- The first gate run uses the 16 new cases and the checked-in rename cases once their labels have `usages`; the real-commit labels are the second, larger pass. Until a real-commit label has `usages`, the report lists it as `usage-unlabelled` and does not score it. The report gives usage counts per corpus and split, even when the count is zero.
- `no-new-wrong-pairs` treats a wrongly grouped usage as a wrong pair. The [2026-11-02 baseline](../../VALIDATION.md#2026-11-02-relationship-benchmark-baseline) has no usage grouping, so its usage wrong set is empty and any wrong usage in a new run is new and fails. The `precision` and `tolerance` gates compare usage precision and recall only when the baseline has usage counts; the first usage baseline is recorded in a dated [VALIDATION.md](../../VALIDATION.md) section after this feature, and the 2026-11-02 baseline is not replaced.
- A run below four usage cases in a language is a smoke run and cannot be a baseline (relationships/01 R30 to R32). The report lists every wrong usage and every usage-unlabelled rename; a draft label lists the engine's references with `reviewed: false` until a reviewer checks them.

### Thresholds and reasons

- At most 1 s per file beyond the engine: the outline already shows the engine result, and a second wait longer than a second would make the update feel like a second analysis. Fixed in the first step; the benchmark's timings can justify a later change.
- High confidence only: a usage group on a wrong rename would mislabel many lines at once, and about three quarters of renames are high on the current corpus, so the conservative choice still covers the common case.
- Same-file only: resolving a usage in another file would need a classpath, an index and cross-file labels; the product scope keeps cross-file relationships out (R13).

### Failure behaviour

When resolution fails, times out or is cancelled, the outline keeps the engine's result. An index that is not ready (dumb mode) skips the pass. A usage that does not resolve, resolves to another declaration or has another change stays an ordinary change. The pass never turns a failed engine into a success or a successful engine into a failure.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `ReferencePassTest`: the pass runs after the engine and adds `Reference` evidence to a high rename; `OutlineIntegrationTest`: the engine result is shown, then the grouped result |
| R2 | `OutlineIntegrationTest`: the engine result is visible while the pass runs, and the replacement keeps the selection |
| R3 | `ReferencePassTest`: a result with no project file or a file outside the project keeps the engine result; `OutlineIntegrationTest`: a clipboard diff has no usages |
| R4 | `ReferencePassTest`: a high rename groups, a medium or low rename does not; a raised `high` stops grouping on the next pass |
| R5 | `ReferencePassTest`: a usage resolving on both sides groups, and a constructor reference maps to its renamed class; `ReferenceResolutionTest`: the same fixture in Java, Kotlin K1, Kotlin K2, Scala 2 and Scala 3 |
| R6 | `ReferencePassTest`: an unresolved usage, a usage of another declaration and a usage of another overload stay ordinary |
| R7 | `ReferencePassTest`: a usage with another change is not grouped; its declaration keeps `changed` |
| R8 | `OutlineModelTest`: a grouped usage stays a counted change and is never hidden; `ReferencePassTest`: the usage pair is in the node's details; `OutlineIntegrationTest`: the filter does not hide a `usages renamed` row |
| R9 | `OutlineModelTest`: a rename with 12 grouped usages shows `method total → sum(int)  rename candidate, 12 usages` |
| R10 | `OutlineModelTest`: a declaration whose only changed spans are grouped usages shows `usages renamed` and counts as `usages renamed`, not `changed`; a declaration with another change keeps `changed` |
| R11 | `OutlineModelTest`: the rename row's usage ranges are every grouped usage on both sides; `OutlineIntegrationTest`: selecting the rename frames them |
| R12 | `RelationshipModelTest`: `Reference` carries the symbol and both ranges and sorts after `Position`; `ComparisonEngineContractTest` via `RelationshipChecks`: ranges inside the text, symbol equal to a new endpoint, only on a `RENAME`; `ReferencePassTest`: no endpoint is added |
| R13 | `ReferencePassTest`: a usage in another file is not grouped and is not visited |
| R14 | `ReferenceResolutionTest`: the usage fixture resolves in Java, Kotlin K1, Kotlin K2, Scala 2 and Scala 3; a mode that cannot resolve keeps the engine result |
| R15 | Manual: `./gradlew verifyPlugin` on 2025.1 and the latest release; the Kotlin report's internal and experimental API usages are reviewed and accepted, and the Scala report is produced with a Scala plugin installed for each target IDE. `ReferenceResolutionTest` covers the Scala fixture |
| R16 | `ReferencePassTest`: cancellation stops before the next usage; `OutlineIntegrationTest`: an edit during the pass discards its result and shows the next comparison |
| R17 | `ReferencePassTest`: a pass over 1 s returns the engine result with no grouped usages; `OutlineIntegrationTest`: the engine result stays and `usages not grouped` is shown; `LimitsBenchmarkTest`: the pass keeps a large file within the analysis limits |
| R18 | `RelationshipBenchmarkTest`: a usage label matches a `Reference`; a reference on a `usages`-labelled rename with no matching pair fails `no-new-wrong-pairs`; a label with `usages: []` passes with no references; four usage cases per language meet the minimum; a reference on an unlabelled rename is listed and not scored |
| R19 | `ChangesSummaryTest`: the Changes tree summary and rows contain no `usages renamed` or usage note; `MergeOutlineIntegrationTest`: the merge outline has no grouped usages; `OutlineIntegrationTest`: only the two-sided outline runs the pass |

## Open questions

- **A usage that also changed for another reason.** Proposed: do not group it (R7). It stays an ordinary change under its declaration with `changed`; the rename row's `N usages` counts only grouped usages, and selecting the rename does not frame it. The [refactorings view](06-refactorings-view.md) can link it later. Grouping it would count a second change as part of the rename and make the group claim more than the rename. The maintainer is asked in the pull request whether to keep this answer or show it inside the group with an `also changed` mark.
