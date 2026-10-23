# Extraction Detection Specification

Status: implemented, 2026-10-22; the validation results are in [VALIDATION.md](VALIDATION.md). Third item after the first release, following the [Changes tree summary](ROADMAP.md#multi-file-summary-in-the-changes-tree) and [Scala](ROADMAP.md#scala). It expands the roadmap's [extraction detection](ROADMAP.md#extraction-detection) entry. Section numbers (§) refer to the [technical specification](TECHNICAL-SPECIFICATION.md), and terms follow the [glossary](GLOSSARY.md).

## 1. Problem

Extract Method is one of the most common refactorings, and the declaration engine reports it as two unrelated changes: the declaration the code came from is *changed*, and the new method is *added*. A reviewer has to find the extracted code in both places and compare it by eye to see that it was carried over unchanged. The `noisy-change` Java fixture has two such cases:

| Old side | New side | Outline today |
| --- | --- | --- |
| The item loop inside `summary(Order)` | `describeItems(Order,StringBuilder)` | `summary` changed, `describeItems` added |
| The whole body of `shippingCost(Order)` | `costFor(Order)` in a new class `ShippingRates` | `shippingCost` changed, `ShippingRates` and its members added |

The validation on real commits found the same pattern in square/moshi, where the body of `AdapterGenerator.generateFromJsonFun` moved into a new `generateFromJsonRegular` ([VALIDATION.md](VALIDATION.md#noisy-extractions)).

## 2. Goals and non-goals

Goals:

- Link an added method or function to the code it was extracted from, when that code was removed from a changed declaration in the same file.
- Let the reviewer see both ends of an extraction at once: selecting the added declaration frames the original code on the old side, and the source declaration shows where its code went.
- Stay conservative ([ADR 0003](adr/0003-conservative-classification.md)): a link is extra information on an added declaration, never a match. It does not change any classification, hide any change, or replace the native diff.
- Work the same way for every language the declaration engine supports, from information every provider can give.

Non-goals for this item:

- Extraction across files (a helper moved to another file). The engine compares one file at a time (§1); this needs the multi-file view first.
- Extracting into a local function, a lambda, or a variable. They are not declarations, so the change stays inside the changed declaration.
- Inline Method, the reverse refactoring. It uses the same detector with the sides swapped and is listed under [Later](#11-later).
- Proving that the extracted code behaves the same. "Extracted" means the tokens match, as "formatting-only" does.

## 3. Behaviour

An **extraction** is an added declaration whose body is, for the most part, code that a changed declaration on the old side had and its new version no longer has. That changed declaration is the extraction's **source**.

For the `noisy-change` fixture the outline then reads:

```text
class OrderService  formatting-only
  method summary(Order)  changed, code extracted to describeItems(Order,StringBuilder)
  method describeItems(Order,StringBuilder)  added, extracted from summary(Order)
  method shippingCost(Order)  changed, code extracted to ShippingRates.costFor(Order)
class ShippingRates  added
  constructor ShippingRates()  added
  method costFor(Order)  added, extracted from OrderService.shippingCost(Order)
```

- **Added declaration:** stays *added* and gets the note `extracted from <source>`. Selecting it frames the declaration on the new side, as today, and the extracted code on the old side (instead of "not present"), so both ends of the extraction are visible at once.
- **Source:** stays *changed* and gets the note `code extracted to <declaration>`. Selecting it frames it on both sides as today, and also highlights the extracted code on the old side, so the reviewer can tell the extracted lines from the lines that really changed.
- **Names in notes** use the outline's labels (§10), qualified by the container only when it differs from the other end's container.
- **Several links:** an added declaration with code from two sources (duplicated code merged into one helper) lists both: `extracted from a(), b()`. A source with code extracted to several declarations lists them all. After three names the note ends with `+N more`.
- **Summary line and Changes tree:** an extraction counts as *extracted* instead of *added*: `2 changed · 1 extracted · 1 added`. The change type filter treats it as *added*, so hiding added changes hides it too.
- **SMART Diff viewer:** no change. The source's extracted code is part of the source's change, so it fades only when the filter hides changed declarations.

## 4. Detection

Detection runs inside the declaration engine, after matching (§7) and classification (§8), as a separate step `ExtractionDetector` in `engine.declarations.analysis`. It never changes what matching and classification decided.

### 4.1 Candidates

- **Targets:** added declarations of kind `METHOD` or `FUNCTION` that have a body (§5.1) of at least `MIN_TOKENS` significant tokens. Members of an added class are targets too, which covers extracting into a new class. Constructors, fields and properties are not targets in this item ([open question X2](#10-open-questions)).
- **Sources:** matched declarations whose content is `CHANGED`, of any kind that owns code: methods, functions, constructors, initializers, fields and properties (an initializer or getter can lose code too), including rename candidates and signature changes. Declarations with content `UNANALYZED` (a parse error) are not sources.
- Declarations that are not added (matched, rename candidates, uncertain) are never targets. If rule M5 has already paired an extracted method with a removed one as a rename candidate, the rename candidate stands.

### 4.2 Significant tokens

Code is compared as sequences of significant tokens: `(type, text)` pairs of the declaration's own tokens, without whitespace and comments, whatever the language's whitespace policy. Reformatting the extracted code, which the `noisy-change` fixture does, does not stop detection; renaming a variable inside it breaks the match at that token.

### 4.3 Removed code of a source

For each source, align its old and new significant tokens (Myers' diff on the token keys) and mark the old tokens the alignment deletes. These are the source's **removed tokens**. Code that the new version still has is therefore never a candidate, so copying code into a helper while keeping it in place is not an extraction, and nor is code taken from an unchanged declaration.

### 4.4 Matching a target to a source

For each target and each source:

1. Tile the target's body tokens with the source's old tokens by greedy string tiling: repeatedly take the longest common run of tokens not yet tiled on either side, down to `MIN_TILE` tokens. The common runs are found through an index of the source's runs of `MIN_TILE` tokens, not by comparing every pair of tokens (§4.6). Tiling, rather than one longest run as in the prototype, lets the match survive a few changed tokens, such as `order.items()` becoming a parameter `items`.
2. The **covered share** is the share of the target's body tokens inside tiles. It must be at least `MIN_SHARE`.
3. The **removed share** is the share of tiled source tokens that are removed tokens (§4.3). It must be at least `MIN_REMOVED`.
4. The tiles on the source side must lie in one stretch of code: the span from the first to the last tile holds at most twice as many tokens as the tiles. The **origin** is the old-side range of that span.

A target links to every source that passes, ordered by covered share, then by old-side source order. Different targets may link to the same source code; that is code copied into two helpers and both are shown.

### 4.5 Thresholds

| Constant | Value | Why |
| --- | --- | --- |
| `MIN_TOKENS` | 10 | Shorter bodies, such as a single call or a getter, match by chance |
| `MIN_TILE` | 5 | A run shorter than a simple statement matches common idioms by chance |
| `MIN_SHARE` | 0.65 | Most of the helper must be the extracted code, a little may be new. The prototype's 0.75 missed real extractions whose helper adds a parameter or a return (tuned on the corpus, [VALIDATION.md](VALIDATION.md)) |
| `MIN_REMOVED` | 0.75 | The source must have lost the code, not kept it |
| `MAX_WORK` | 2 × 10^7 token comparisons | Keeps detection within a small part of the 5 s limit (§9.3) |

The values were tuned on the validation corpus (§8). They are constants, not settings.

### 4.6 Cost

Comparing every token of a target with every token of a source costs the product of their token counts, which for a method of a few hundred lines moved whole into a helper is already more than `MAX_WORK`. Tiling therefore indexes the source's runs of `MIN_TILE` tokens and extends only the runs the target shares, keeping at most 64 places per run against repetitive code; its work grows with the token counts and the shared runs. Before tiling, a pair is skipped when the bags of adjacent token pairs of the target body and the source's removed tokens share less than half of the target's pairs (the Dice measure the matcher already uses, §7). If the work for a file, counted as token comparisons in the alignments and the tiling, exceeds `MAX_WORK`, detection is skipped for the file and the result is as today; this is not a failure and is not reported. Detection checks cancellation between pairs.

## 5. Model and result

### 5.1 Declaration model

`Declaration` gains two fields, filled by every provider that supports detection:

- `ownTokenStarts: List<Int>`: the start offset of each own token, so a run of tokens maps back to a source range. Empty means the provider has no offsets and its declarations are neither targets nor sources.
- `body: TextRange?`: for a method, function or constructor, the code of its body without the braces of a block body; for an expression body (`fun f() = …`, Scala `def f = …`), the expression. Null without a body (abstract and interface methods).

Both are optional, so a provider without them simply gets no extraction links.

### 5.2 Result

`ChangeNode` gains two fields; adding fields is allowed by §5 and existing engines leave them empty:

```kotlin
data class ChangeNode(
    // …existing fields…
    val extractedFrom: List<Origin> = emptyList(),   // only for ADDED: where its code came from
    val extractedTo: List<Subject> = emptyList(),    // only for MATCHED: where code removed from it went
)

/** Code on the old side that an added subject was extracted from. */
data class Origin(
    val subject: Subject,
    val declarationRange: TextRange,   // old-side range of the source declaration, which identifies its node
    val range: TextRange,              // old-side range of the extracted code, inside declarationRange
)
```

The source node also lists each extracted code range in its `details` as `RangePair(old = range, new = null)`, so selecting it highlights that code (§10). `declarationRange` is needed because a subject is not unique in a file (two classes can each have a `total()`).

### 5.3 Contract

The engine contract (§3.1) gains no new rule. The contract suite checks that every `Origin.range` and `declarationRange` is on the old side and within bounds, that `range` lies inside `declarationRange`, and that `declarationRange` is the old range of a reported `MATCHED` node. Nothing-hidden (rule 6) is unaffected: links only add ranges.

## 6. Languages

The detector is language-neutral. A provider supports it by filling `ownTokenStarts` and `body` (§5.1); the shared `PsiDeclarationProvider` computes token offsets for every PSI provider, so each language only reports where its bodies are.

### 6.1 Java

- `body`: the inside of `PsiMethod.body`'s braces, for methods and constructors.
- Typical cases: Extract Method in the same class, into a new class (static utility or collaborator), and into a nested or inner class. All three are covered, because the target is an added method wherever it is in the file.
- Extracted code often gains a qualifier (`this.repository` → `repository`) or a parameter replaces a field access. Tiling (§4.4) absorbs a few such tokens.

### 6.2 Kotlin

- `body`: the inside of a block body's braces, or a whole expression body, for named functions and secondary constructors.
- Kotlin's line-break whitespace policy does not apply to detection (§4.2), so code moved from a block into an expression body still matches.
- Extracting into an **extension function** turns `order.items` into `items` on the receiver. Each receiver access is a broken token, so a helper that uses the receiver on most lines can fall below `MIN_SHARE`; the validation (§8) measures how often.
- Extracting into a **local function** is not detected (§2). Extracting into a **property** with a getter or initializer is open question X2.
- Top-level functions, functions in objects and companion objects are targets like any other function.

### 6.3 Scala

Scala follows once its provider is merged ([roadmap](ROADMAP.md#scala)); nothing in the detector changes.

- `body`: for `def`, the inside of a block body's braces or the whole expression body. In Scala 3 an indented body without braces is the region after `=` up to the end of the definition.
- Targets are `def`s, including those in objects, traits, givens and `extension` blocks. Like Kotlin extensions, an `extension` method loses its receiver qualifier.
- Code extracted into an anonymous `given` has a synthesized name; the note uses whatever name the provider gives it (technical spec Q7).
- The Scala fixtures (§7) add one extraction case per dialect, including a Scala 3 braceless body.

## 7. Outline and Changes tree

- `OutlineItem` gains a `note` shown after the change labels in grey, as in the prototype. For an extraction it is `extracted from …`; for a source, `code extracted to …`. The note is part of the row's label and accessible name (§10).
- Selecting an extraction scrolls the old side to the first `Origin.range` and frames it, instead of showing "not present". Enter still moves the caret into the new side.
- Selecting a source highlights its extracted code through `details`, as it already does for any detail.
- The summary line counts extractions as `extracted` between `changed` and `added`, in the outline root and in the Changes tree summary (§9.4).
- Golden fixtures (§12) render the link after the labels, with the origin's lines:

  ```text
  method describeItems(Order,StringBuilder)  added  extracted from summary(Order) old 39-41  new 65-71
  ```

## 8. Validation

Before the feature ships, run `CorpusValidationTest` (§12) on the same corpus as [VALIDATION.md](VALIDATION.md) with two new flags:

- `extraction`: every file with a link, for review. Each link is checked by hand as right or wrong.
- `extraction-near-miss`: a changed declaration and an added method in the same file whose covered share is from 0.5 up to `MIN_SHARE`, or whose removed share falls short, for tuning the thresholds.

Done when:

- Both `noisy-change` extractions and the moshi `AdapterGenerator` case are linked.
- No reviewed link is wrong. A wrong link is worse than none, since the reviewer would trust it.
- The median analysis time is unchanged and the slowest file takes at most 20% longer than without detection.

The results and the chosen thresholds are added to VALIDATION.md.

## 9. Tests

- **Analysis unit tests** (no IDE, trees built by hand as in `Trees.kt`): an extraction from a changed method; reformatted extracted code; a few changed tokens inside the extracted code (tiling); code copied while kept in the source is not linked; code from an unchanged declaration is not linked; a short body is not linked; a body only partly made of removed code is not linked; tiles scattered across the source are not linked; two sources for one target; one source for two targets; an extraction into a member of an added class; a rename candidate is not turned into an extraction; detection skipped above `MAX_WORK`; cancellation.
- **Provider tests:** `body` and `ownTokenStarts` for Java methods and constructors, Kotlin block and expression bodies, secondary constructors, and declarations without a body; later for Scala 2 and 3.
- **Golden fixtures:** `noisy-change` (Java) shows both links. New fixtures `extract-method` for Java and Kotlin cover extraction into the same class, a new class, and (Kotlin) an extension function and an expression body.
- **Contract suite:** the origin checks in §5.3.
- **`OutlineModelTest`:** notes, the `extracted` count, and the old-side range of a selected extraction.

## 10. Open questions

- **X1 Thresholds.** The values in §4.5 come from the uncompiled prototype and two fixtures. The validation (§8) decides them.
- **X2 Property and field targets.** Extracting an expression into a Kotlin property or a Java field initializer is common, but the extracted code is usually short. Include them once the validation shows how often they would pass `MIN_TOKENS`.
- **X3 Order with rename candidates.** Detection runs after rule M5 (§4.1). If the validation shows M5 pairing extracted methods with unrelated removed ones, run detection on M5's candidates first.

## 11. Later

- **Inline detection:** the reverse, a removed method whose body now sits inside a changed declaration, labelled `inlined into …`. The same detector with the sides swapped.
- **Cross-file extraction,** once the multi-file view can compare more than one file at a time.
- **Extract and modify:** showing the remaining token differences between the origin and the extracted body, through `details`, once fine-grained differencing exists ([roadmap](ROADMAP.md#fine-grained-differencing)).

## 12. Implementation plan

The prototype (kept outside the repository, never compiled) has the model fields, a single-run detector, the outline note and eight analysis tests. It predates the 0.1.1 noise pass, so it must be rebased onto rename candidates, `displaySignature` and `receiver`.

1. Model and providers: `ownTokenStarts` and `body` (§5.1) for Java and Kotlin, with provider tests.
2. Detector: removed tokens, tiling and the thresholds (§4), with the analysis unit tests.
3. Result and contract: `extractedFrom`, `extractedTo`, `Origin` and the source `details` (§5.2, §5.3).
4. Outline and Changes tree: notes, selection, the `extracted` count, golden fixtures (§7).
5. Validation: run the corpus, tune the thresholds, record the results (§8).
6. Scala: `body` for the Scala provider and its fixtures, once the provider is merged (§6.3).
