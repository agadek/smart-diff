# Technical Specification: SMART Diff first release

Status: draft, 2026-10-21. Refines the [product specification](SPECIFICATION.md). Terms follow the [glossary](GLOSSARY.md). Decisions are recorded in [ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md), [ADR 0003](adr/0003-conservative-classification.md), [ADR 0004](adr/0004-pluggable-comparison-engines.md) and [ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md).

IntelliJ Platform API names below are the expected entry points. The items that needed checking against IntelliJ Platform 2025.1 were confirmed or rewritten by spikes S1 and S2 (§13); each is marked **(confirmed by S1)** or **(confirmed by S2)**.

## 1. Scope

In scope:

- Two-sided text comparisons: `ContentDiffRequest` with exactly two `DocumentContent`s, from local changes, VCS history, and "Compare with…" actions.
- One comparison engine, the **declaration engine**, for Java and Kotlin in IntelliJ IDEA (Community and Ultimate, and the unified IntelliJ IDEA from 2025.3), 2025.1 and newer with no upper bound. CI runs Plugin Verifier on 2025.1 and the latest release (§12).
- The engine abstraction, registry, selection logic and dropdown, ready for further engines.

Out of scope for the first release:

- Three-way merge (planned, see [roadmap](ROADMAP.md#structural-outline-for-three-way-merges)), directory diffs, binary content, and patch previews without full file content.
- Comparisons where the old side and new side are different languages.
- A change-list-level overview across files (Q3, decided: per-file only; the Changes tree summary comes next on the [roadmap](ROADMAP.md#multi-file-summary-in-the-changes-tree)).
- Engines other than the declaration engine (except a test-only engine used to verify the abstraction).

## 2. Architecture

```
            IntelliJ diff (native side-by-side viewer)
                           │
                 ┌─────────▼──────────┐
                 │  ui                │  outline, engine dropdown, navigation,
                 │                    │  lifecycle; knows only engine/api
                 └─────────┬──────────┘
                           │ ComparisonRequest (old text, new text, language id)
                 ┌─────────▼──────────┐
                 │  engine/api        │  ComparisonEngine, ComparisonResult,
                 │  + registry        │  EngineRegistry (extension point)
                 └─────────┬──────────┘
          ┌────────────────┼──────────────────────┐
┌─────────▼──────────┐  ┌──▼───────────────┐  ┌───▼──────────────┐
│ engine/declarations│  │ (future)         │  │ (future)         │
│  psi providers →   │  │ Tree-sitter      │  │ Difftastic CLI   │
│  declaration model │  │ engine           │  │ engine           │
│  → matching →      │  └──────────────────┘  └──────────────────┘
│  classification    │
└────────────────────┘
```

Packages (under the plugin's root package, `io.github.agadek.smartdiff`):

| Package | Depends on IntelliJ? | Responsibility |
| --- | --- | --- |
| `engine.api` | No | `ComparisonEngine`, request and result types, cancellation |
| `engine.registry` | Yes | Extension point, engine lookup and selection (§4) |
| `engine.declarations.model` | No | Declaration tree and tokens (internal to the declaration engine) |
| `engine.declarations.analysis` | No | Matching (§7) and classification (§8) |
| `engine.declarations.psi` | Yes | Declaration engine factory and PSI providers (§6) |
| `ui` | Yes | Diff integration, outline, engine dropdown, navigation, settings |

Dependency rules, enforced by an architecture test (§12):

- `ui` may depend on `engine.api` and `engine.registry` only, never on any `engine.<name>` package.
- `engine.api` depends on nothing in the plugin.
- No engine package depends on another engine package.

`engine.api`, `engine.declarations.model` and `engine.declarations.analysis` must compile and be unit-tested without the IntelliJ test framework.

## 3. Engine interface

### 3.1 Contract

```kotlin
interface ComparisonEngine {
    val id: EngineId                       // stable; persisted in settings
    val displayName: String                // shown in the dropdown
    val version: String                    // engine + parser version; part of the result

    fun supports(languageId: String): Boolean
    fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult
}

@JvmInline value class EngineId(val value: String)  // e.g. "smartdiff.declarations"

data class ComparisonRequest(
    val oldText: CharSequence,             // immutable snapshots
    val newText: CharSequence,
    val languageId: String,                // IntelliJ Language.getID(), e.g. "JAVA", "kotlin"
    val fileName: String?,                 // hint only
    val limits: Limits,
)

data class Limits(val maxChars: Int, val maxDeclarations: Int, val timeoutMillis: Long)  // Limits.DEFAULT: §9.3

fun interface Cancellation { fun checkCanceled() }  // throws java.util.concurrent.CancellationException when canceled
```

Rules every engine must follow:

1. `compare` is called on a background thread, never inside a read action. An engine that needs PSI takes its own cancellable read actions; an engine that runs an external process must not hold a read lock while waiting.
2. `compare` is pure with respect to its inputs: same request and engine version → equal result. No global state between calls.
3. `compare` calls `checkCanceled()` regularly and returns promptly after cancellation.
4. Failures are reported as a result with status `FAILED` and a reason; exceptions other than cancellation are caught by the host and treated the same way. The host calls engines through `compareSafely`, which also applies the size limit (§9.3) before calling the engine.
5. All ranges in the result are offsets into the request's texts.
6. **Nothing hidden:** every character that differs between the sides (per a character-level diff of the two texts) lies inside the range of some reported change, the reported file region, or an unanalyzed range. A node's own ranges count only if it is a change itself, not a container shown only because of its children; its `details` always count. The contract suite (§12) accepts any character-level alignment, so the check does not depend on one diff algorithm's choices.
7. No source content leaves the IDE process unless an engine documents otherwise and is disabled by default (no first-release engine does).

### 3.2 Engine factory and registration

Engines that need IntelliJ services are created per project by a factory, so the engine interface itself stays IntelliJ-free:

```kotlin
interface ComparisonEngineFactory {          // extension point implementation
    fun create(project: Project?): ComparisonEngine
}
```

```xml
<extensionPoints>
  <extensionPoint name="comparisonEngine"
                  interface="io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory"
                  dynamic="true"/>
</extensionPoints>

<extensions defaultExtensionNs="io.github.agadek.smartdiff">
  <comparisonEngine order="first"
                    implementation="io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngineFactory"/>
</extensions>
```

The extension point is interface-based: each extension is a `ComparisonEngineFactory`, and the engine id comes from the engine it creates, so it is written in one place. A keyed bean such as `KeyedLazyInstanceEP` would duplicate the id in `plugin.xml` and serves lookup by key, which the registry does not need: selection (§4) walks all engines in extension order. `order` is available on every extension. The extension point is dynamic, so SMART Diff's own add-on engines can be installed or removed without restarting. The extension point is internal (Q5): it is not documented or supported for other plugins, so the engine interface and result model can change without a compatibility policy.

## 4. Engine selection

1. Candidates: registered engines, in extension order, whose `supports(languageId)` is true.
2. If there are none → status `UNSUPPORTED`, native diff only.
3. Selected engine: the user's default engine for this language id if it is a candidate; otherwise the first candidate. Defaults are stored per language id; a language with no stored default uses the first candidate.
4. If the stored default id is not registered (for example its plugin was removed), it stays stored but is ignored until it comes back.
5. The dropdown (§10) lists the candidates and is shown only when there are two or more. Choosing an engine reruns the comparison with it and stores its id as the default for that language only.

`EngineRegistry` is a project service that creates each factory's engine on first use, passing `null` for the default project, and recreates them when the extension point changes. If two engines have the same id, the later one is skipped with a warning. `EngineSelection.of` applies the rules above without the IntelliJ Platform. Defaults are stored by the application service `EngineDefaults` in `smartdiff.xml`.

## 5. Engine-neutral result

```kotlin
data class ComparisonResult(
    val engineId: EngineId,
    val engineVersion: String,
    val status: Status,                    // ANALYZED, PARTIAL, UNSUPPORTED, TOO_LARGE, FAILED
    val fileRegion: ChangeNode?,           // source outside all subjects; null if unchanged
    val changes: List<ChangeNode>,
    val unanalyzed: List<RangePair>,       // source the engine could not classify
    val fallbackReason: String?,
)

data class ChangeNode(
    val subject: Subject,
    val oldRange: TextRange?,              // null when absent on that side
    val newRange: TextRange?,
    val presence: Presence,                // MATCHED, ADDED, REMOVED, UNCERTAIN
    val moved: Boolean,                    // only for MATCHED
    val signatureChanged: Boolean,         // only for MATCHED
    val content: Content,                  // UNCHANGED, FORMATTING_ONLY, CHANGED, UNANALYZED
    val candidates: List<Candidate>,       // only for UNCERTAIN
    val details: List<RangePair>,          // optional fine-grained changed spans; empty if the engine has none
    val children: List<ChangeNode>,
)

sealed interface Subject {
    data class Declaration(val kind: DeclarationKind, val name: String, val signature: String?) : Subject
    data class Region(val description: String) : Subject   // for engines without declarations
}

data class Candidate(val subject: Subject, val range: TextRange)    // range on the side where the node is absent
data class RangePair(val old: TextRange?, val new: TextRange?)      // at least one side
data class TextRange(val start: Int, val end: Int)           // [start, end)
```

`DeclarationKind` is part of the API so engines share labels: `CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, OBJECT, CONSTRUCTOR, METHOD, FUNCTION, FIELD, PROPERTY, ENUM_CONSTANT, TYPE_ALIAS, INITIALIZER`.

A `ChangeNode` is *reported* if it is not `MATCHED`, or is moved, or its content is not `UNCHANGED`, or any child is reported. Engines may include unreported nodes for navigation; the outline hides them.

The result types are data classes, so results compare by value (the contract suite's determinism check relies on it). Adding fields to the result is allowed; renaming or removing them requires updating every engine and the contract tests.

## 6. Declaration engine: providers (PSI)

### 6.1 Structure

The declaration engine turns each side into a `DeclarationTree` through a per-language provider, then runs matching (§7) and classification (§8) and converts the outcome into the engine-neutral result (§5).

```kotlin
interface DeclarationProvider {
    fun supports(languageId: String): Boolean
    fun parse(project: Project, text: CharSequence, fileName: String?): PsiFile   // §6.2
    fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree
}
```

Both are called inside the engine's read action, one per side. The read action gives way to write actions: on a pooled thread it runs through `ProgressManager.runInReadActionWithWriteActionPriority` and starts over after a write, so typing in the new side never waits for a parse. Matching and classification run after it, on the model only, so no PSI outlives the read action. The engine version is the engine's own version followed by the platform build, since a parser change can change results.

The shared base `PsiDeclarationProvider` builds the tree: a language provider only says which PSI elements are declarations, with their kind, name, signature and member elements. An element it does not describe, such as a class whose name was not parsed, is not a declaration; its source and parse errors belong to its container.

Providers are registered on an engine-internal extension point `declarationProvider`, so language providers can live in optional plugin descriptors. Both languages are optional dependencies, so the plugin still loads in IDEs without them: the Java provider registers in `META-INF/smartdiff-java.xml` (depends on `com.intellij.java`) and the Kotlin provider in `META-INF/smartdiff-kotlin.xml` (depends on `org.jetbrains.kotlin`). The `supportsKotlinPluginMode` declaration must be in `plugin.xml`: in K2 mode the Kotlin plugin reads it from the main descriptor only and otherwise skips the optional one. The engine's `supports` returns true when any provider supports the language.

Internal model (not visible outside the engine):

```kotlin
class Declaration(
    val kind: DeclarationKind,
    val name: String,                    // as written; "" for INITIALIZER
    val signature: String?,              // normalized parameter types; null for non-callables
    val range: TextRange,                // whole declaration incl. doc comment and annotations
    val children: List<Declaration>,     // nested declarations, in source order
    val ownTokens: List<Token>,          // leading whitespace, then tokens in range minus children's extents
    val hasParseError: Boolean,          // a parse error lies in range but outside children
    val leadingWhitespace: TextRange?,   // whitespace right before range that belongs to it
)

class FileRegion(val ranges: List<TextRange>, val tokens: List<Token>, val hasParseError: Boolean)
```

A declaration's *leading whitespace* is the run of whitespace tokens between it and its previous sibling (or its container's start, or the previous top-level declaration); its *extent* is its range with that whitespace. The leading whitespace is the declaration's own source, not its container's, so adding, removing or moving a declaration takes the blank line before it along and leaves its container unchanged. A container's own tokens and the file region skip the children's extents. A node that is a change itself lists its leading whitespace on each side in `details`, so that whitespace is covered (§3.1 rule 6); `range` stays the declaration itself, for navigation.

```kotlin
class DeclarationTree(
    val topLevel: List<Declaration>,
    val fileRegion: FileRegion,          // everything not covered by a top-level declaration
    val whitespacePolicy: WhitespacePolicy,
)

data class Token(val type: String, val text: String, val kind: TokenKind)
enum class TokenKind { WHITESPACE, COMMENT, OTHER }

enum class WhitespacePolicy {
    INSIGNIFICANT,           // Java: drop whitespace tokens
    LINE_BREAKS_SIGNIFICANT, // Kotlin: keep "has line break" vs "no line break"
    SIGNIFICANT,             // Python, YAML: never claim formatting-only
}
```

### 6.2 Parsing transient text

- PSI: inside a cancellable read action, `PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)`; `project` is the one passed to the factory, or the default project when there is none **(confirmed by S2)**.
- Java files are parsed at the highest language level the platform knows, set with `PsiUtil.FILE_LANGUAGE_LEVEL_KEY` before the tree is built. Without it the level comes from the default project's settings, which the user can change. S2 saw `JDK_24` on 2025.1 either way, with records, sealed types, record patterns and text blocks parsed without errors.
- Offsets from the PSI equal offsets into the request text **(confirmed by S2)**: for every declaration, the PSI element's text equals the request text sliced by its range. A declaration's range includes its doc comment and annotations in both languages.
- The request texts are document snapshots, which are always LF-separated: a VCS revision with CRLF line ends arrives as an LF document, and the content only records the original separator. Ranges therefore match the diff editors.
- Providers must not resolve references or types (no classpath; see ADR 0002).

### 6.3 Tokens

Tokens come from the language's lexer: `LanguageParserDefinitions.INSTANCE.forLanguage(language).createLexer(project)`. For Java this lexes at the default project's language level rather than the forced one (the Java lexer classes that take a level are deprecated from 2026.2); tokens are only compared with each other, so a lexing difference cannot hide a change. Token types are classified with the same `ParserDefinition`: `getWhitespaceTokens()` → `WHITESPACE`, `getCommentTokens()` → `COMMENT`, everything else → `OTHER`. One lexer pass over the whole file is sliced by range; a token that crosses a range boundary belongs to the range containing its start.

### 6.4 Java provider

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `PsiClass` (`isInterface`, `isEnum`, `isRecord`, `isAnnotationType`) | INTERFACE / ENUM / RECORD / ANNOTATION_TYPE / CLASS | `name` | — |
| `PsiMethod` (`isConstructor`) | CONSTRUCTOR / METHOD | `name` | parameter types |
| `PsiEnumConstant` | ENUM_CONSTANT | `name` | — |
| `PsiField` (not an enum constant) | FIELD | `name` | — |
| `PsiClassInitializer` | INITIALIZER | `""` (`static` → `"static"`) | — |

Not declarations: anonymous and local classes, lambdas, local variables, record components (part of the record), annotation methods' default values (part of the method).

Fields declared together (`int a, b;`) are separate declarations; the shared type tokens belong to the first field. In an implicitly declared class (a file of top-level methods and fields, JEP 512), the members are top-level declarations.

### 6.5 Kotlin provider

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `KtClass` (`isInterface`, `isEnum`, `isAnnotation`) | INTERFACE / ENUM / ANNOTATION_TYPE / CLASS | `name` | — |
| `KtObjectDeclaration` (not object literal) | OBJECT | `name`, or `"Companion"` for an unnamed companion | — |
| `KtNamedFunction` (not local) | FUNCTION | `name` | receiver type + parameter types |
| `KtSecondaryConstructor` | CONSTRUCTOR | `""` | parameter types |
| `KtProperty` (not local) | PROPERTY | `name` | receiver type, if any |
| `KtEnumEntry` | ENUM_CONSTANT | `name` | — |
| `KtTypeAlias` | TYPE_ALIAS | `name` | — |
| `KtAnonymousInitializer` | INITIALIZER | `""` | — |

The primary constructor and its `val`/`var` parameters are part of the class's own tokens. Requires the bundled Kotlin plugin, declared as an optional dependency with its own descriptor. `plugin.xml` declares `supportsKotlinPluginMode` for both K1 and K2, because the provider uses only PSI and no analysis API. In K2 mode, PSI files from text in the default project give the declarations and offsets this table expects **(confirmed by S2)**; tests run the Kotlin plugin in K2 mode. The PSI already names an unnamed companion object `"Companion"`, and it also contains local declarations (properties inside function bodies and lambdas), which the provider skips.

### 6.6 Signature normalization

The signature is the list of parameter type texts as written, joined with `,`, after removing all whitespace and comments, e.g. `foo(final  Map<String, List<Integer>> m, int... xs)` → `Map<String,List<Integer>>,int...`. Annotations, modifiers and parameter names are excluded; Java C-style array brackets after the name stay (`int values[]` → `int[]`), and so do Kotlin modifiers that are part of a type (`suspend () -> Unit` → `suspend()->Unit`). A Kotlin function with a receiver puts the receiver type and a `.` first: `fun List<T>.second(x: Int, vararg ys: String)` → `List<T>.Int,String`, and `fun Cart.isFull()` → `Cart.`; a property's signature is its receiver type alone. A script's statements are part of the file region. Type names are not resolved, so `List` and `java.util.List` differ; that is reported as a signature change (rule M3), not hidden.

### 6.7 Parse errors

- An error element inside a declaration's own range sets `hasParseError`; the declaration is still matched by identity if its name was parsed, but its content is `UNANALYZED`.
- An error in the file region sets the file region to `UNANALYZED`.
- If either side has errors and no declarations were found while the file is non-blank, the result is `FAILED` with the native diff shown.
- A result with any `UNANALYZED` content has status `PARTIAL`.

## 7. Declaration engine: matching

Inputs: the old and new `DeclarationTree`. Matching is global over all declarations in the file, so a declaration can be matched across containers. Each rule runs over the declarations still unmatched after the previous rule. Within a rule, declarations are processed in source order of the old side, which makes the result deterministic.

Keys (the *path* is the chain of container `(kind, name)` pairs from the top level):

| Rule | Key | Result when the key occurs exactly once on each side |
| --- | --- | --- |
| M1 | kind, path, name, signature | MATCHED |
| M2 | kind, name, signature | MATCHED; moved if the containers are not a match of each other |
| M3 | kind, path, name | MATCHED, signature changed (content is CHANGED) |
| M4 | kind, path, name, occurring on both sides but not once each | UNCERTAIN, with all same-key declarations of the other side as candidates |

Remaining old-side declarations are REMOVED and new-side ones ADDED.

Kinds must be equal in every rule, so a class turned into an interface is REMOVED + ADDED. Container kind/name changes affect the path, so the members of a renamed class match by M2 (moved) while the class itself is REMOVED + ADDED (see Q2). Moved by container means the old and new containers are not matched to each other (or only one of them is top level); the members of a class that itself moved by M2 have a different path too, but they stay in the same matched container and are not moved.

**Order within a container.** For each matched container pair (and the top level), take the matched children in source order on both sides and compute the longest common subsequence of matches. Matched children outside the subsequence are marked moved. Ties are broken by the earliest old-side offset.

**Result tree.** Changes are arranged by the new side's containment. Declarations present only on the old side (REMOVED, and old-side UNCERTAIN) are placed under their old container's counterpart if it exists, under their old container's own node if that container is only on the old side too, otherwise at the top level. Among their new siblings they follow the counterpart of their nearest preceding old sibling, so a removed declaration comes before one added in its place. Every declaration of both sides appears exactly once. The file region is one node spanning its ranges on each side (an empty range at offset 0 for a side without one), with the separate ranges in `details`; it is null when its tokens are identical.

The matcher and classifier are `DeclarationMatcher` and `ContentClassifier`; `DeclarationAnalysis` runs them and builds the result, including `TOO_LARGE` (§9.3) and `FAILED` (§6.7).

## 8. Declaration engine: classification

For each MATCHED declaration, compare `ownTokens` of the old side and the new side; do the same for the file region.

The steps are applied in order, and the first that applies decides the content:

1. If either side has a parse error in that range → `UNANALYZED`. This wins over every later step, including a signature change: `signatureChanged` is still reported, but the content is `UNANALYZED`.
2. If rule M3 matched it (signature changed) → `CHANGED`.
3. If the token texts are identical, including whitespace → `UNCHANGED`.
4. Normalize both sequences with the tree's whitespace policy, then compare `(type, text)` pairs:
   - `INSIGNIFICANT`: drop `WHITESPACE` tokens.
   - `LINE_BREAKS_SIGNIFICANT`: replace each `WHITESPACE` token with `⏎` if it contains a line break, otherwise drop it.
   - `SIGNIFICANT`: no normalization (step 4 can never produce formatting-only).
   Equal → `FORMATTING_ONLY`; otherwise → `CHANGED`.

In step 4 a run of adjacent `WHITESPACE` tokens counts as one: own tokens skip the children's extents, so the whitespace before and after a child can become adjacent, and adding or removing a child must not change the count of line breaks. Because each child owns its leading whitespace (§6.1), adding, removing, moving or reordering members leaves their container `UNCHANGED`. Removing the first member is the exception: the next member's leading whitespace changes (the blank line after `{` goes), so that member is `FORMATTING_ONLY`.

`COMMENT` tokens are compared by exact text, including whitespace inside them. The declaration engine puts only leading whitespace in `details` (§6.1); the native diff shows the detail.

## 9. Integration and threading

### 9.1 Attachment (decided by spike S1, [ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md))

A `DiffExtension` whose `onViewerCreated(viewer, context, request)` attaches the outline to a `TwosideTextDiffViewer` **(confirmed by S1)**, for local-change, VCS-history and "Compare with…" diffs in Java and Kotlin:

- The viewer's component is a `DiffPanelBase` whose `BorderLayout` centre holds the editors. The extension moves that centre component into a `OnePixelSplitter` with the outline as the second component.
- If the viewer is not a `TwosideTextDiffViewer`, or its component does not have that shape, the extension does nothing and the native diff shows as usual.
- The outline toggle (§10) is an action in the `Diff.ViewerToolbar` group (`IdeActions.DIFF_VIEWER_TOOLBAR`), which every native viewer adds to its toolbar; it finds its viewer through `DiffDataKeys.DIFF_VIEWER`.

An alternate viewer registered as a `DiffTool` (Option B) was rejected: other `DiffExtension`s would see the wrapper instead of the native viewer, and choosing it reorders the global diff tool list instead of affecting one diff.

### 9.2 Lifecycle

1. Viewer created → if the request is in scope and engine selection (§4) yields an engine, show the outline in a loading state, unless the outline is switched off for this diff (§10) or by the setting (§11); otherwise show nothing.
2. Snapshot both documents (`getImmutableCharSequence()`) with their modification stamps, and call `engine.compare` on a pooled background thread under a progress indicator bound to the viewer's disposable. The host does not take a read action (§3.1 rule 1).
3. On completion, on the UI thread: discard the result if either document's modification stamp changed or the selected engine changed since the request; otherwise render.
4. Document changes (the new side is often editable) and engine changes from the dropdown cancel the running comparison and start a new one, after a 300 ms debounce for document changes.
5. The host enforces `Limits.timeoutMillis` by canceling; a canceled-by-timeout comparison renders as `FAILED` ("timed out").
6. On viewer disposal, cancel the comparison and release all references to texts and results.

### 9.3 Limits

| Limit | Default | When exceeded |
| --- | --- | --- |
| Size of either side | 1 MB | `TOO_LARGE`, native diff only (checked by the host before calling the engine) |
| Declarations per side | 5 000 | `TOO_LARGE` (declaration engine) |
| Analysis time | 5 s | `FAILED`, reason "timed out" |

Limits are configurable in settings and passed to engines in `ComparisonRequest.limits`.

Spike S3 measured them on the 20 largest Java and Kotlin files in IntelliJ Community outside test data (16 Java, 4 Kotlin; 238 KB to 1.6 MB), on an Apple M1 Pro, each compared with itself, with an empty line inserted after every 10th line and with a comment line inserted after every 10th line. The median of five runs was at most 333 ms for any file, including the 1.6 MB one (32 000 lines, about 280 ms with changes everywhere), 142 ms for the largest Kotlin file (1 MB), and under 100 ms for files under 350 KB. The 5 s timeout leaves more than ten times that headroom for slower machines, and the 1 MB size limit, not time, is what stops the largest generated files. The limits stay as they are. Rerun with `-Psmartdiff.s3.corpus=<file listing paths>`.

## 10. Structural outline

- **Outline toggle:** a toolbar button on every in-scope diff switches between the outline and the plain native diff by showing or hiding the outline panel; the editors stay as they are. Its initial state comes from the setting in §11, and it affects only the current diff window: the window keeps the choice (in its `DiffContext`) for the next files it shows, and other windows are unaffected. While the outline is off, no comparison runs.
- Outline toolbar: the **engine dropdown** (§4), shown only with two or more candidate engines, listing `displayName`s with the selected one checked; and a refresh action.
- Root: a file summary line, e.g. `3 changed · 1 added · 2 removed · 1 moved · 2 formatting-only`.
- Nodes follow the result's `ChangeNode` tree. Only reported nodes and their containers are shown. A container shown only because of its children has no change label.
- Node label: for a declaration subject, kind, name and signature (`method parse(String,int)`); for a region subject, its description. Followed by labels for presence, moved, content and "signature changed".
- Groups: formatting-only changes are collapsed into a "Formatting-only" group per container (setting: show inline instead). Uncertain nodes are listed under an "Uncertain" group at the top level, each with its candidates as children.
- The file region appears as a node "File region" when reported. Unanalyzed ranges appear under an "Unanalyzed" group.
- Status messages replace the tree for `UNSUPPORTED`, `TOO_LARGE` and `FAILED`, with the reason. For `PARTIAL`, a banner says some source was not analyzed.
- Selecting a node scrolls the old side editor to `oldRange` and the new side editor to `newRange` and highlights both ranges, plus `details` if present; an absent side shows "not present" in the panel. Synchronized scrolling is paused while both editors scroll, so a moved declaration is visible on both sides; the user's next scroll synchronizes them again. Enter/double-click moves the caret into the new side editor (old side if removed).
- Colors use the IDE's diff color keys (inserted, deleted, modified); labels are text, not color only. Every node has an accessible name equal to its label.

## 11. Settings

Application-level, under *Settings → Tools → SMART Diff*:

| Setting | Default |
| --- | --- |
| Show structural outline automatically | On (decided 2026-10-21). When off, the outline toggle (§10) starts off and the panel is hidden |
| Default comparison engine, per language | One row per language that has at least one engine, each with a choice of the engines that support it; unset means the first registered engine (`smartdiff.declarations` in the first release); also set from the dropdown |
| Formatting-only changes | Collapsed group / inline |
| Size, declaration and time limits | §9.3 |

## 12. Testing

- **Engine contract suite:** `ComparisonEngineContractTest`, an abstract JUnit 4 class in `src/test/kotlin/.../engine/api/contract` that every engine's tests extend, run over a shared corpus of Java, Kotlin and plain-text pairs (`ContractCorpus`). Engines whose tests need the IntelliJ test framework run the same checks through `ComparisonEngineContract`. It checks: identical texts → no reported changes; empty and one-side-empty texts; determinism (two runs → equal results); all ranges within bounds and on the right side; **nothing hidden** (§3.1 rule 6); prompt return after cancellation (the engine checks regularly on a large input, and the exception from `checkCanceled` propagates unchanged whether it is thrown at the first, a middle or the last check); `FAILED` rather than an exception for malformed input.
- **Abstraction test:** a test-only engine registered through the extension point (`LineEngine`, which reports each run of changed lines as a `Region`) passes the contract suite, appears in the dropdown next to the declaration engine, and can be selected; this verifies that the UI has no dependency on the declaration engine.
- **Architecture test:** an [ArchUnit](https://www.archunit.org/) test over the compiled classes that fails the build if any rule in §2 is broken, and if `engine.api`, `engine.declarations.model` or `engine.declarations.analysis` use IntelliJ or Kotlin plugin classes. A fixture of deliberately violating classes checks that every rule can fail.
- **Declaration engine, `analysis` unit tests (no IDE):** declaration trees built by hand; one test per matching rule, the LCS ordering, each whitespace policy, and every status.
- **Declaration engine, provider tests:** `BasePlatformTestCase` (Java via the light Java fixture); parse fixture text and compare the produced tree with an expected outline.
- **Golden fixtures:** `src/test/testData/<language>/<case>/{old,new}.<ext>` plus `expected-<engine id>.txt`, a text rendering of the outline per engine: the status, then one line per reported node and per container of reported nodes, with its labels and the lines it spans on each side. Every fixture is also checked for nothing hidden. `-Psmartdiff.updateGolden=true` rewrites the expected files from the current results, for review before committing. Each language must cover: added/removed/changed method, overloads, signature change, reorder, move between classes, class rename, formatting-only, comment-only change, string literal change, parse error on one side, empty and identical files, and (Kotlin) a line-break-only change. Java also has `noisy-change`, a realistic edit that mixes most of these with reformatting, plus code extracted into a new method and into a new class, as a demo of what the outline is for.
- **Integration test:** `OutlineIntegrationTest` opens `SimpleDiffRequest`s through `DiffManager.createRequestPanel`: the outline appears for Java and Kotlin, selecting a node highlights its range on both sides, the outline toggle hides it and shows the native diff, edits rerun the comparison, and the size limit, time limit and viewer disposal behave as in §9.2. The test-only engine appears in the dropdown there and can be selected. `OutlineModelTest` checks the outline rows and summary (§10) without the IDE.

### 12.1 Build and CI

- Build target: IntelliJ IDEA Community 2025.1.3 (`platformVersion` in `gradle.properties`), `since-build` 251, no `until-build`. Bundled `com.intellij.java` and `org.jetbrains.kotlin` plugins, IntelliJ Platform test framework with the Java test framework, JUnit 4 and ArchUnit.
- GitHub Actions (`.github/workflows/build.yml`) on every push to `main` and every pull request: `buildPlugin check` (build and all tests), and `verifyPlugin` with Plugin Verifier on IntelliJ IDEA Community 2025.1.3 and the latest IntelliJ IDEA release.

## 13. Prototype spikes

This table is the single list of spikes; the product specification and roadmap refer to it.

Spikes run as platform tests in `src/test/kotlin/io/github/agadek/smartdiff/spike`, so CI repeats them on every build; they print their findings as `SPIKE` lines in the build log. They stay until the provider and integration tests of §12 cover the same ground.

| Spike | Question | Done when | Result |
| --- | --- | --- | --- |
| S1 | Option A or B (§9.1)? | Both attach to local-change and VCS-history diffs in Java and Kotlin, or one is shown not to | Done 2026-10-21: Option A, [ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md); `S1DiffIntegrationSpikeTest` |
| S2 | Do PSI files from text (default project, K2 Kotlin) give correct declarations and offsets? | Provider tests pass for both languages on VCS-revision content | Done 2026-10-21: yes, with the Java language level forced (§6.2); `S2TransientPsiSpikeTest` |
| S3 | Are the limits in §9.3 realistic? | Analysis time measured on the 20 largest Java/Kotlin files in the IntelliJ Community repository | Done 2026-10-21: yes, kept as they are (§9.3); `S3LimitsSpikeTest` |
| S4 | After the first release, for [Scala](ROADMAP.md#scala): can the provider choose the Scala 2 or Scala 3 dialect for text-built PSI (from the file's module, `scalaVersion`, or syntax), and does the Scala plugin's PSI give correct declarations and offsets for both? | Provider tests pass for both dialects on VCS-revision content, with a documented fallback when the dialect is unknown | After the first release |

## 14. Open questions

- **Q1 Default visibility (resolved 2026-10-21).** The outline opens by default, with a setting to turn it off and a toolbar button in every diff to switch between the outline and the plain native diff (§10, §11).
- **Q2 Renamed containers (resolved 2026-10-21).** The first release keeps the literal result: the old class removed, the new class added, and its members moved into it. Rename candidates are revisited after validation ([roadmap](ROADMAP.md#rename-candidates)).
- **Q3 Multi-file overview (resolved 2026-10-21).** Not in the first release, which is per-file only. The next step is a per-file summary inside the IDE's existing Changes/Commit tree ([roadmap](ROADMAP.md#multi-file-summary-in-the-changes-tree)).
- **Q4 Plugin ID (resolved 2026-10-21).** The plugin ID, Gradle group and root package are `io.github.agadek.smartdiff`, which also names the extension point namespace (§3.2).
- **Q5 Third-party engines (resolved 2026-10-21).** The `comparisonEngine` extension point stays internal: only SMART Diff and its own optional descriptors register engines, and the engine interface and result model may change freely. Revisit after a second production engine has used the result model without breaking changes.
- **Q6 Engine choice scope (resolved 2026-10-21).** The default engine is chosen per language (§4, §11).
- **Q7 Scala declaration model (open, after the first release).** For the [Scala provider](ROADMAP.md#scala): add `TRAIT`, `GIVEN` and `EXTENSION` to `DeclarationKind`, or map traits to `INTERFACE`; how to name anonymous givens (the compiler-synthesized name or the type as written); whether an extension block is a container or its methods are flattened with the receiver type in their signatures; how pattern definitions such as `val (a, b) = …` produce declarations; and how signatures render several parameter lists and `using`/`implicit` clauses.
