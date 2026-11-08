# Rename grouping: details

Status: accepted, 2026-11-05; step 1 (Java) implemented, 2026-11-07; Kotlin K2, Scala and the 440-case gate are step 2

Feature: [Rename grouping](04-rename-grouping.md)

## Design

### Where it plugs in

The engine stays text-only ([ADR 0002](../../adr/0002-parser-neutral-model-with-psi-first.md)). A separate `ReferencePass` runs after it in `OutlineController`, on a pooled thread, in the existing cancellable read action:

1. The engine's result is shown at once.
2. When the diff's file belongs to the open project, `ReferencePass.run(project, file, request, result, cancellation)` returns an outcome with a copy of the result and the reference evidence it found.
3. The outline replaces its model with the new result, keeping the selection.

The controller shows the engine result on the event dispatch thread before it starts the pass, so a resolve never delays the first result (R2). Only then does it schedule the pass on the pooled thread.

`OutlineDiffExtension` already has the `DocumentContent` of each side. It passes the new side's `highlightFile` (or the old side's when the new side has none) to `OutlineController` as a `VirtualFile`, next to the file name. The pass runs only when that file is not null and `ProjectFileIndex.isInContent(file)` is true; a file under the project but outside its source roots still qualifies. A clipboard diff or a file from a library has no such file and keeps the engine's result (R3).

R3 gates the outline's entry point, not the resolver. The benchmark has no diff file, so it calls the second entry point instead:

- `ReferencePass.run(project, file, request, result, cancellation)` is the outline path and applies R3.
- `ReferencePass.runForBenchmark(project, request, result, cancellation)` uses the same resolvers and light copies but has no file and no `ProjectFileIndex` check. `RelationshipBenchmark` calls it after `compareSafely`, so R18 runs on the full corpus without a project file. `ReferencePassTest` checks that both entry points give equal evidence for the same fixture and that the benchmark entry point works without a file.

Both entry points return a `ReferencePass.Outcome` with `result`, `version` and a status of `GROUPED`, `NOT_GROUPED` or `TIMED_OUT`; an external cancellation throws `ProcessCanceledException` instead of returning a stale outcome. The version is `ReferencePass.VERSION`.

The pass runs in the two-sided diff outline only (R19):

- **Changes tree:** no pass. `ChangesSummaryService` keeps computing the engine's result and caches it by the file paths, each side's version, the engine id and the confidence policy. Its summary and rows therefore show the engine's labels only, with no `N usages` note and no `usages renamed`. Running the pass for up to 500 files per tree would multiply the cost and there is no viewer in the tree to navigate usages; the file's diff gets the grouped result when it is opened. The cache key does not change because no new input reaches it.
- **Merge outline and three-sided diffs:** no pass. `MergeComparison` runs the engine base → left and base → right and `MergeJoin` compares the two results; there is no single old/new pair to resolve against. The merge outline and the merge window keep the engine's result, exactly as `MergeOutlineController` does today.
- **Caching:** `OutlineController` has no result cache. A document edit, an engine change or a refresh runs the engine and the pass again. A filter change reuses the shown result through `shownRequest`/`state` and so keeps the grouped result without resolving again. The pass result lives only in the controller's state and is discarded with the viewer. If a cache is added later, its key must include the reference-pass version and the confidence policy.
- **Version:** `DeclarationEngine.VERSION` does not change, so [relationships/02](02-relationship-model.md) R24 is unchanged: the engine returns the same result with or without the pass, and its version still names only the parsers and matcher that produced it. `ReferencePass.VERSION` is separate, starting at `1`, and is raised when a resolver or the grouping rule changes. The benchmark report records both `engineVersion` and `referencePassVersion`, and the validation report records the pass version next to the engine version. Two results from the same engine can therefore differ by pass version.

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
- The pass adds only reference evidence. It adds no `ChangeNode` field and writes no `ChangeNode.details`: [STRUCTURAL §9.1](../../STRUCTURAL-RELATIONSHIPS.md#91-additive-model) keeps `ChangeNode` as it is, and the engine's own detail spans stay untouched (R8).
- `OutlineModel` maps each reference to the innermost matched node that contains its old range on the old side and its new range on the new side. The rename row's `OutlineItem.details` gets every reference range of the relationship, and a declaration row that contains grouped usages also gets the ones inside it, for navigation only; `OutlineNavigator` already highlights `OutlineItem.details`, so selecting the rename frames every grouped usage on both sides (R11). `ChangeNode.details` still holds only what the engine reported.
- The rename row gets the note `12 usages` from the number of references on its relationship. `N usages` is a note, not a separate change count; `usages renamed` is a label with its own summary count (see [declarations/06](../declarations/06-change-labels.md)).

### Labels and detection

`usages renamed` is computed from the relationship references and the node tree, not from `ChangeNode.details`:

- A reference belongs to a node when its `oldRange` lies inside the node's old range and its `newRange` lies inside the node's new range, and that node is the innermost matched declaration containing both. A nested declaration takes its own usages; the container does not count them again.
- `RenameNameMapping` is the shared name-mapping helper in `engine.api`. Given the old text, the new text and the grouped `Reference` evidence, it replaces the text at each reference's old range with the text at that same reference's new range, and leaves every other character unchanged. A reference range exists only for a resolved usage, so a name inside a string or comment is never mapped. `ReferencePass` and `OutlineModel` both use it, so they cannot disagree about what a rename-only change is.
- A matched node is labelled `usages renamed` when it is not itself any rename's new endpoint, has at least one grouped reference, and has no change other than those references. To decide the last part, `OutlineModel` applies `RenameNameMapping` to the node's old text using every grouped reference in the node, and compares the result with the node's new text under the language's whitespace policy. A node with grouped usages from two renames maps each reference by its own relationship, not by one relationship chosen for the node. The comparison starts at the node's first non-whitespace token; leading whitespace and the node's `details` do not decide it.
- A node with another change keeps `changed` or its other labels, and its grouped usages still appear in the row and on the rename row. A rename's new endpoint itself always keeps `rename candidate`; it never becomes `usages renamed`.
- A node with at least one grouped usage is never hidden: `OutlineModel.isHidden` returns false for it even when its other labels are of a hidden type, the hidden count leaves it in, and `HiddenLines` reads the same predicate so the native diff keeps its lines. The `usages renamed` label has no `ChangeType` of its own (R8, R14 of [declarations/06](../declarations/06-change-labels.md)).

The engine contract checks the new evidence through the same result:

- every reference and endpoint range lies inside its side's text (relationships/02 R17, extended to reference ranges);
- a reference's `symbol` equals one of the relationship's new endpoints' subjects;
- `Reference` evidence appears only on a `RENAME` relationship;
- the fixed order holds, including references ordered by old and then new range start (R19);
- the endpoints still name nodes in the result tree: R16 is unchanged, because a usage adds no endpoint.

`ReferencePassTest` runs the same relationship checks on the pass's result, and `RelationshipModelTest` checks the new evidence type. The pass is not an engine, so it is not added to `ComparisonEngineContract` as an engine; the contract's checks are reused on its result.

### Resolution

Both sides are light copies of the request texts, made with `PsiFileFactory.createFileFromText` in the language and dialect the engine used. The pass asks the declaration provider's existing `dialect(project, texts, fileName)` for that dialect, so a Scala 2 or Scala 3 diff resolves in the same dialect the engine parsed. Nothing is written to disk and the copies are not added to a module or an index. This is enough for a usage that refers to a declaration in the same file, which is the only kind R13 allows. A usage is found with `PsiFile.findReferenceAt(offset)` or by walking `getReferences()` and is resolved with `PsiReference.resolve()`.

**Step 1 covers Java.** The Kotlin K2 and Scala rows below, and their verifier checks, are step 2.

| Language | API | Cost | Verifier |
| --- | --- | --- | --- |
| Java | `PsiJavaFile`, `PsiReferenceExpression`, `PsiJavaCodeReferenceElement`, `PsiReference.resolve()` | Low: public platform PSI, same as the provider | No internal or experimental API; the report is clean |
| Kotlin | `KtFile`, `KtReferenceExpression.references` (a `KtReference`), `PsiReference.resolve()` | Medium: K2 and the latest PSI | No Analysis API is called, so no `KaSession`/`KaExperimentalApi` usage; the Kotlin PSI is the one the provider already uses |
| Scala | `ScalaFile`, `ScReferenceExpression`, `ScReference.resolve()` | Medium-high: internal plugin PSI, two dialects, plugin-version changes | The verifier reports the Scala PSI it can see, such as `PsiNamedElementWithCustomPresentation`; it resolves the optional Scala dependency for both target IDEs, so the resolver's classes are in the report |

- **Kotlin K2 only:** the resolver uses the Kotlin PSI's reference implementation, not the Kotlin Analysis API, and the tests run in K2 as `build.gradle.kts` already sets `idea.kotlin.plugin.use.k2=true`. A spike on 2025.1.3 resolved a same-file function call from a `PsiFileFactory` file in K2; the latest release uses the same PSI path. If a later Kotlin plugin changes that PSI, the PSI resolve is still the only entry point; the fallback is no grouping, never a wrong group. K1 is not a target.
- **Scala:** the same spike resolved a same-file `def` from a `PsiFileFactory` file with the pinned Scala plugin. The plugin's PSI is not public API, so the resolver must be version-checked against the Scala plugin of each target IDE. The build pins `scalaPluginVersion` (2025.1.32) for compilation and tests. Plugin Verifier also installs Scala: `plugin.xml` declares `org.intellij.scala` as an optional dependency for `smartdiff-scala.xml`, and the verifier resolves that dependency for each target. The 2026-11-05 `verifyPlugin` run installed `org.intellij.scala:2025.1.32` on IC 2025.1.3 and `org.intellij.scala:2026.3.26` on IU 2026.3, as its `dependencies.txt` and report show. No extra `pluginVerification` block is needed; the resolved version is recorded by the verifier, and the benchmark report still records the version the tests run with. If a target cannot resolve a compatible Scala plugin, R15 is not met; the step is not skipped. The resolver must catch `ProcessCanceledException` and any resolver failure, and treat that usage as unresolved.
- **One reference walk per side:** the pass asks the resolver for every rename target on a side in one call; the Java resolver walks the file's references once and resolves each reference once for all targets, then groups the cached results per rename. A platform cancellation while resolving is rethrown so `cancellableReadAction` retries the read instead of returning a partial group.
- **Resolution stays in the copy:** `resolve()` must return a declaration whose containing file is the light copy that was created for that side. A target outside the copy, such as a JDK method, a library method or a declaration in another file, is treated as unresolved and stays an ordinary change (R6, R13).
- **Dumb mode:** the pass skips when `DumbService.isDumb(project)` and does not wait for smart mode, because waiting would block the outline. Same-file resolution may work without the index, but skipping is the conservative rule and avoids `IndexNotReadyException`.
- **Files outside source roots:** the light copies do not need a source root or module; the spike's files were in a test project with no source root and resolved. If a language's resolver still returns no declaration there, the usage is ordinary; R5 is not weakened. This is also why `runForBenchmark` needs no project file: the benchmark gives the same texts to the same light copies.
- **Old side:** the old text is not placed in the project or in a module. It is only a second light copy, and the pass resolves the old usage against the old copy's declaration, then the new usage against the new copy's declaration. Both must resolve to the two endpoints for the usage to group (R5). A usage that resolves to another declaration, or to none, stays ordinary (R6).
- **Only the compared file:** a usage in another file is not in the light copy and is never visited (R13).
- **All three languages stay (step 2):** all three resolvers resolve a same-file usage from a light copy. Scala needs the extra verifier setup above; no language leaves the second step for lack of a resolver. Step 1 ships only the Java resolver.
- **Scala 2 whitespace policy (step 2):** the Scala 2 resolver uses `LINE_BREAKS_SIGNIFICANT`, while the Scala 3 resolver uses `INDENTATION_SIGNIFICANT`. Until the resolver's policy reaches the outline, the outline uses the stricter Scala 3 policy for every Scala diff, so it cannot label a Scala 2 declaration `usages renamed` only because indentation differs.

### Grouping rule

A usage is grouped when all of these hold:

- the rename relationship's confidence is `HIGH` under the request's `ConfidencePolicy`; the pass does not re-score it;
- the old range resolves to the old endpoint's declaration and the new range resolves to the new endpoint's declaration, and both resolutions stay in their light copies;
- the old and new usages are paired as described below;
- the usage is the rename's only change: `RenameNameMapping` maps this reference's old range to its new-side text in the smallest expression containing the usage, or in the enclosing statement when the language has no expression node, and the two sides are equal under the language's whitespace policy. The helper touches only that reference range, so an old name inside a string or comment is left alone. Leading whitespace outside that expression is ignored.

**Pairing.** The pass pairs usages before it tests them:

- **Enclosing declaration.** A usage belongs to the innermost matched node that contains it on its side. The old usage's node and the new usage's node must be the same matched node, identified by its old and new range, so a call in one method is never paired with a call in another. A usage in the file region has no enclosing declaration and is not paired.
- **Order.** Within that node, old usages are sorted by old range start and new usages by new range start, then paired by ordinal: the first old usage with the first new usage, the second with the second, and so on. This is the normalized position; it does not depend on the renamed name's length, indentation or other whitespace.
- **Moved and duplicate calls.** Extra usages on either side stay unpaired and ordinary. Duplicate calls pair in source order, which is harmless when their expressions are equal. A call that moved is paired only when it keeps its ordinal and its expression is unchanged; otherwise it stays ordinary, because the move is another change. If a pair's expressions differ after the name mapping, that pair is not grouped (R7); the other pairs in the node are still tested.
- **No other resolution.** A usage whose target is outside the light copy, or that resolves to another declaration or another overload, stays ordinary.

A constructor reference resolves to a constructor; when its containing class is the relationship's endpoint, the pass uses the class as the symbol, so `new Order()` follows a renamed `Order`. A constructor whose class did not rename is not grouped.

Everything else stays an ordinary change. A declaration whose every changed span is a grouped usage is labelled `usages renamed` as described above; a declaration with another change keeps `changed`, and its grouped usages still appear in the row and on the rename row. A declaration whose usages are all unpaired or impure keeps its ordinary change.

### Confidence

R4 groups only high-confidence renames. The policy is the one the request already carries:

- `smartdiff.relationships.confidence`, declared in `plugin.xml` with the default `0.82,0.7` as `high,medium`; `SmartDiffSettings.confidence` reads it when `OutlineController`, `ChangesSummaryService` and `MergeOutlineController` build a request.
- The engine has already classified the relationship. The pass tests `relationship.confidence == Confidence.HIGH`; it never calls `ConfidencePolicy.classify` on the score again.
- Raising `high` groups fewer renames. Lowering `high` (it must stay at or above `medium`) groups more medium renames, because a medium relationship becomes high on the next comparison. Raising `medium` can move a pair between 0.7 and the new medium to low, and a low relationship never groups. Lowering `medium` below 0.7 forms no new pairs, because the matcher's pairing floor stays 0.7. No policy change changes which declarations are paired (relationships/03 R19).
- A registry change takes effect on the next comparison or the next Changes tree summary; there is no registry listener that refreshes an open outline. The Changes tree cache key includes the policy string, so a summary is recomputed under the new policy. A malformed value keeps the default and logs one warning (relationships/02 R30).
- The latest full-corpus run is the [2026-11-07 rename grouping step 1 benchmark](../../VALIDATION.md#2026-11-07-rename-grouping-step-1-benchmark). It runs 428 cases: the existing 424-case corpus plus the four Java usage-grouping fixtures. It reports 93 rename relationships: Java 23 high and 5 medium, Kotlin 18 and 3, Scala 2 17 and 6, Scala 3 15 and 6, which is 73 high, 20 medium and 0 low. Precision is 1.000 and all 19 reported usages match their labels. The run is a step-1 smoke run for the usage gate, not the R18 first gate. The previous 2026-11-05 engine-9 run on the 424-case corpus reported 89 rename relationships (69 high, 20 medium and 0 low) with precision 1.000. Step 2 adds the other twelve usage-grouping fixtures to make the 440-case corpus, where the 16 usage-grouping cases add one high rename each: 105 renames, 85 high (81.0%), 20 medium and 0 low. The 2026-11-04 tuning run at `high = 0.82` keeps 43 of its 57 correct renames high (coverage 0.754) with precision 1.000; three quarters was the chosen floor. About one in four tuning renames therefore stays ungrouped by design, which is conservative and keeps P3; the maintainer can lower `high` to group more, at the cost of grouping less certain renames.

### Threading and limits

- The pass runs on the pooled thread that already runs `compareSafely`, after the engine returns, never on the UI thread. It runs inside `cancellableReadAction(cancellation) { ... }`, the helper the declaration provider already uses: off the event dispatch thread it runs `ProgressManager.runInReadActionWithWriteActionPriority`, which gives way to a write action by canceling and retrying the read, and checks `cancellation` after a canceled read; on the event dispatch thread or inside an existing read it runs a plain read action. It holds no PSI after the read action: the result stores only subjects, ranges and counts.
- `OutlineController` passes a pass-specific `Cancellation` built from its `Job`: it reads the job's `canceled` flag, the active platform progress indicator (so a read action canceled by a write action is retried) and the pass's own 1 s deadline. The engine's 5 s deadline is not reused, so a pass that starts after a slow engine still gets its full budget. `run` checks `cancellation.checkCanceled()` before and after every resolve; an external cancellation throws `ProcessCanceledException` through `run`, `OutlineController.compare` catches it, sees no timeout and returns null, so the stale comparison is discarded and no note is shown (R16).
- While the engine runs, the outline shows `Loading` as today. When the engine result arrives, the outline shows it immediately, with a grey `Resolving usages…` note while the pass runs. The note is a label, not a modal progress bar, and the rows stay selectable. When the pass finishes, the controller replaces the model with the grouped result and keeps the selection (R2).
- The pass gets `ReferencePass.BUDGET_MILLIS = 1_000` per file, measured from the start of the pass. It checks its deadline and `cancellation.checkCanceled()` before each resolve and after each one; a resolve that returns after the deadline discards the whole usage addition, returns status `TIMED_OUT` with the engine result, and shows no partial group (R17). The engine's 5 s limit, 1 MB size limit and 5 000-declaration limit are unchanged; the pass is a second, bounded step after the engine, not part of the engine's time. The engine result is already on screen, so the first useful result still waits at most the engine limit and grouping adds at most 1 s.
- If the pass times out or cannot resolve because the project is dumb, the engine result stays and the note changes to a grey `usages not grouped` for that file. A pass that simply finds no usage returns `NOT_GROUPED` without a note. If the user cancels (an edit, hiding the outline or disposing the viewer), the pass stops and no note is added, because the whole comparison is stale (R16). It never shows `Analysis failed`, because the engine succeeded; a failed or timed-out engine runs no pass.
- A `PARTIAL` result can run the pass only for relationships between analyzed declarations; an unresolved or parse-error range stays ordinary. `UNSUPPORTED`, `TOO_LARGE` and `FAILED` results run no pass.
- A resolver failure on one usage skips that usage and logs at debug level; it does not discard the other usages. A failure of the pass itself, such as an unexpected exception, discards all usage evidence and keeps the engine result.

### Benchmark

**Step 2 is the full usage gate.** R18 needs usage labels; step 1 reviews the Java renames and adds four Java usage-grouping fixtures, and step 2 reviews every rename in every language and adds the other twelve fixtures.

The existing corpus has 424 cases: 304 checked-in cases and 120 real commits (30 per language), with 89 rename relationships (69 high, 20 medium, 0 low). The step-2 gate adds the 16 usage-grouping fixture folders to `relationships-corpus-full.txt`: 440 cases (320 checked-in and 120 real) and 105 renames (85 high, 20 medium, 0 low), because each usage-grouping fixture contains one high rename. Step 1 runs 428 cases (the existing 424 plus four Java fixtures) and is a smoke run for this gate. Step 2 reviews every rename in all 120 real-commit labels and in every checked-in label that lists a rename, and includes all 16 usage-grouping cases.

The label gains a required `usages` list on every rename relationship:

```yaml
expected:
  relationships:
    - kinds: [RENAME]
      old: { path: "class Order", kind: METHOD, name: total, signature: "()" }
      new: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
      usages:
        - old: "118..123"
          new: "116..121"
```

The row shows `method total → sum()  rename candidate, 1 usage` (or `12 usages`), the same label as [declarations/06](../declarations/06-change-labels.md) R4.

- A usage label is a `start..end` range pair on the two sides, the same range syntax as an endpoint range. The relationship's endpoints identify the renamed declaration, so the usage needs no separate symbol identity.
- `usages: []` means the label was reviewed and the rename has no usages. After the first step, an absent list is only allowed in a draft; in a gate run it is a `usage-unlabelled` rename and fails completeness.
- A reported `Evidence.Reference` is matched to the label with the same relationship endpoints and the same old and new ranges. A reported reference on a `usages`-labelled rename with no matching pair is a wrongly grouped usage. A listed usage with no reported reference is a missed usage, listed but not gated by default. A reported reference on a rename with no `usages` is counted as `usage-unlabelled` and not scored, so an incomplete corpus stays visible.
- **Step 2:** the case matrix gains `usage-grouping`, with at least four cases per language (Java, Kotlin, Scala 2, Scala 3). Step 1 adds the four Java cases; the other twelve are step 2. That is 16 fixture cases and a minimum of four, not the three of the older categories:
  1. a rename with one usage;
  2. a rename with twelve usages, the use case's case;
  3. a rename with a usage that resolves to another overload and a usage that does not resolve, both expected ordinary;
  4. a rename with a usage that also changed for another reason, expected ordinary (R7).
- **Step 2:** the first gate run is the full 440-case corpus: all 320 checked-in cases (304 existing plus the 16 usage-grouping fixtures) and all 120 real commits, every rename usage-reviewed. Step 1 is a 428-case smoke run and cannot be used as the usage baseline. The 16 usage-grouping cases are part of the gate, not an optional extra; without them a run is a smoke run (R9, R18). Running the benchmark without a file is possible because `RelationshipBenchmark` calls `ReferencePass.runForBenchmark`, so R3 does not block it. The report gives usage counts per corpus and split, even when the count is zero, and records `ReferencePass.VERSION` as `referencePassVersion` next to `engineVersion`.
- `no-new-wrong-pairs` treats a wrongly grouped usage as a wrong pair. The [2026-11-02 baseline](../../VALIDATION.md#2026-11-02-relationship-benchmark-baseline) has no usage grouping, so its usage wrong set is empty and any wrong usage in a new run is new and fails. A gate run that still has a `usage-unlabelled` rename fails completeness. The `precision` and `tolerance` gates compare usage precision and recall only when the baseline has usage counts; the first usage baseline is recorded from this full-corpus run in a dated [VALIDATION.md](../../VALIDATION.md) section after this feature, and the 2026-11-02 baseline is not replaced.
- A run below four usage cases in a language is a smoke run and cannot be a baseline (relationships/01 R30 to R32). The report lists every wrong usage, every missed usage and every `usage-unlabelled` rename; a draft label lists the engine's references with `reviewed: false` until a reviewer checks them.

### Thresholds and reasons

- At most 1 s per file beyond the engine: the outline already shows the engine result, and a second wait longer than a second would make the update feel like a second analysis. Fixed in the first step; the benchmark's timings can justify a later change.
- High confidence only: a usage group on a wrong rename would mislabel many lines at once. The existing corpus has 69 of 89 renames high (77.5%), and the gate corpus has 85 of 105 (81.0%), so the conservative choice still covers most renames.
- Same-file only: resolving a usage in another file would need a classpath, an index and cross-file labels; the product scope keeps cross-file relationships out (R13).

### Failure behaviour

When resolution fails, times out or is cancelled, the outline keeps the engine's result. An index that is not ready (dumb mode) skips the pass. A usage that does not resolve, resolves to another declaration or has another change stays an ordinary change. The pass never turns a failed engine into a success or a successful engine into a failure.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `ReferencePassTest`: the pass runs after the engine and adds `Reference` evidence to a high rename; `OutlineIntegrationTest`: the engine result is shown, then the grouped result |
| R2 | `OutlineIntegrationTest`: the engine result is visible while the pass runs, and the replacement keeps the selection |
| R3 | `ReferencePassTest`: a result with no project file or a file outside the project keeps the engine result; `OutlineIntegrationTest`: a clipboard diff has no usages; `RelationshipBenchmarkTest`: the benchmark entry point runs without a file |
| R4 | `ReferencePassTest`: a high rename groups, a medium or low rename does not; a raised `high` stops grouping on the next pass |
| R5 | `ReferencePassTest`: a usage resolving on both sides groups, and a constructor reference maps to its renamed class; `ReferenceResolutionTest`: Java in step 1, and the same fixture in Kotlin K2, Scala 2 and Scala 3 in step 2 |
| R6 | `ReferencePassTest`: an unresolved usage, a usage of another declaration, a usage of another overload and a usage that resolves outside the light copy stay ordinary |
| R7 | `ReferencePassTest`: a usage with another change is not grouped; its declaration keeps `changed`; a pure usage in the same declaration still groups |
| R8 | `OutlineModelTest`: a grouped usage stays a counted change and is never hidden, including a `changed` declaration whose other labels are hidden; `ReferencePassTest`: `ChangeNode.details` is unchanged and the reference is only evidence; `OutlineIntegrationTest`: the filter does not hide a `usages renamed` or grouped-usage row |
| R9 | `OutlineModelTest`: a rename with 12 grouped usages shows `method total → sum(int)  rename candidate, 12 usages`; a rename with one shows `1 usage` |
| R10 | `OutlineModelTest`: a declaration whose only changed spans are grouped usages shows `usages renamed` and counts as `usages renamed`, not `changed`; a declaration with grouped usages from two renames maps both and still shows `usages renamed`; a string or comment containing an old name is unchanged and does not make the label; a declaration with another change keeps `changed`; leading whitespace and other `details` do not change the label; a rename endpoint stays `rename candidate` |
| R11 | `OutlineModelTest`: the rename row's `OutlineItem.details` are every grouped usage on both sides, while `ChangeNode.details` stay as the engine set them; `OutlineIntegrationTest`: selecting the rename frames them |
| R12 | `RelationshipModelTest`: `Reference` carries the symbol and both ranges and sorts after `Position`; `ComparisonEngineContractTest` via `RelationshipChecks`: ranges inside the text, symbol equal to a new endpoint, only on a `RENAME`; `ReferencePassTest`: no endpoint is added and no `ChangeNode.details` is written |
| R13 | `ReferencePassTest`: a usage in another file is not grouped and is not visited; a target outside the light copy stays ordinary |
| R14 | `ReferenceResolutionTest`: the usage fixture resolves in Java in step 1, and in Kotlin K2, Scala 2 and Scala 3 in step 2; a language that cannot resolve keeps the engine result |
| R15 | Step 2 manual: `./gradlew verifyPlugin` on 2025.1 and the latest release; the verifier resolves and installs `org.intellij.scala` for each target (2025.1.32 on 2025.1.3 and 2026.3.26 on 2026.3 in the 2026-11-05 run), and the Kotlin and Scala internal and experimental API usages are reviewed and accepted. `ReferenceResolutionTest` covers the Scala fixture |
| R16 | `ReferencePassTest`: `run` takes a `Cancellation`, stops before the next resolve when it is canceled and throws `ProcessCanceledException`; a resolver spy shows no further resolve after the check; `OutlineIntegrationTest`: an edit during the pass discards its result and shows the next comparison |
| R17 | `ReferencePassTest`: a pass over 1 s returns the engine result with status `TIMED_OUT` and no grouped usages; `OutlineIntegrationTest`: the engine result stays and `usages not grouped` is shown; `LimitsBenchmarkTest`: the pass keeps a large file within the analysis limits |
| R18 | Step 2 gate: `RelationshipBenchmarkTest`: the gate corpus has 440 cases (320 checked-in plus 120 real) and 105 renames (85 high, 20 medium, 0 low), including the 16 usage-grouping fixtures; step 1 is the 428-case smoke run over the existing corpus plus the four Java fixtures; every rename relationship in the 120 real-commit labels has a `usages` list; a usage label matches a `Reference`; a reference on a `usages`-labelled rename with no matching pair fails `no-new-wrong-pairs`; a label with `usages: []` passes with no references; a `usage-unlabelled` rename fails completeness; `referencePassVersion` is in the report; four usage cases per language meet the minimum |
| R19 | `ChangesSummaryTest`: the Changes tree summary and rows contain no `usages renamed` or usage note; `MergeOutlineIntegrationTest`: the merge outline has no grouped usages; `OutlineIntegrationTest`: only the two-sided outline runs the pass |
