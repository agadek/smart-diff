# Technical Specification: SMART Diff first release

Status: draft, 2026-10-21. Refines the [product specification](SPECIFICATION.md). Terms follow the [glossary](GLOSSARY.md). Decisions are recorded in [ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md), [ADR 0003](adr/0003-conservative-classification.md) and [ADR 0004](adr/0004-pluggable-comparison-engines.md).

IntelliJ Platform API names below are the expected entry points; items marked **(verify)** must be confirmed in the prototype against IntelliJ Platform 2025.1.

## 1. Scope

In scope:

- Two-sided text comparisons: `ContentDiffRequest` with exactly two `DocumentContent`s, from local changes, VCS history, and "Compare with…" actions.
- One comparison engine, the **declaration engine**, for Java and Kotlin in IntelliJ IDEA (Community and Ultimate), 2025.1+.
- The engine abstraction, registry, selection logic and dropdown, ready for further engines.

Out of scope for the first release:

- Three-way merge (planned, see [roadmap](ROADMAP.md#structural-outline-for-three-way-merges)), directory diffs, binary content, and patch previews without full file content.
- Comparisons where the old side and new side are different languages.
- A change-list-level overview across files (open question Q3).
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

Packages (under the plugin's root package):

| Package | Depends on IntelliJ? | Responsibility |
| --- | --- | --- |
| `engine.api` | No | `ComparisonEngine`, request and result types, cancellation |
| `engine.registry` | Yes | Extension point, engine lookup and selection (§4) |
| `engine.declarations.model` | No | Declaration tree and tokens (internal to the declaration engine) |
| `engine.declarations.analysis` | No | Matching (§7) and classification (§8) |
| `engine.declarations.psi` | Yes | Declaration engine factory and PSI providers (§6) |
| `ui` | Yes | Diff integration, outline, engine dropdown, navigation, settings |

Dependency rules, enforced by a build-time architecture test:

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

class ComparisonRequest(
    val oldText: CharSequence,             // immutable snapshots
    val newText: CharSequence,
    val languageId: String,                // IntelliJ Language.getID(), e.g. "JAVA", "kotlin"
    val fileName: String?,                 // hint only
    val limits: Limits,
)

class Limits(val maxChars: Int, val maxDeclarations: Int, val timeoutMillis: Long)

fun interface Cancellation { fun checkCanceled() }  // throws when canceled
```

Rules every engine must follow:

1. `compare` is called on a background thread, never inside a read action. An engine that needs PSI takes its own cancellable read actions; an engine that runs an external process must not hold a read lock while waiting.
2. `compare` is pure with respect to its inputs: same request and engine version → equal result. No global state between calls.
3. `compare` calls `checkCanceled()` regularly and returns promptly after cancellation.
4. Failures are reported as a result with status `FAILED` and a reason; exceptions other than cancellation are caught by the host and treated the same way.
5. All ranges in the result are offsets into the request's texts.
6. **Nothing hidden:** every character that differs between the sides (per a character-level diff of the two texts) lies inside the range of some reported change, the reported file region, or an unanalyzed range.
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
                  beanClass="com.intellij.openapi.extensions.KeyedLazyInstanceEP"
                  dynamic="true"/>
</extensionPoints>

<extensions defaultExtensionNs="com.example.smartdiff">
  <comparisonEngine key="smartdiff.declarations" order="first"
                    implementationClass="…engine.declarations.psi.DeclarationEngineFactory"/>
</extensions>
```

The bean class and attribute names are illustrative **(verify the best-fitting bean class)**. The extension point is dynamic, so SMART Diff's own add-on engines can be installed or removed without restarting. The extension point is internal (Q5): it is not documented or supported for other plugins, so the engine interface and result model can change without a compatibility policy.

## 4. Engine selection

1. Candidates: registered engines, in extension order, whose `supports(languageId)` is true.
2. If there are none → status `UNSUPPORTED`, native diff only.
3. Selected engine: the user's default engine for this language id if it is a candidate; otherwise the first candidate. Defaults are stored per language id; a language with no stored default uses the first candidate.
4. If the stored default id is not registered (for example its plugin was removed), it stays stored but is ignored until it comes back.
5. The dropdown (§10) lists the candidates and is shown only when there are two or more. Choosing an engine reruns the comparison with it and stores its id as the default for that language only.

## 5. Engine-neutral result

```kotlin
class ComparisonResult(
    val engineId: EngineId,
    val engineVersion: String,
    val status: Status,                    // ANALYZED, PARTIAL, UNSUPPORTED, TOO_LARGE, FAILED
    val fileRegion: ChangeNode?,           // source outside all subjects; null if unchanged
    val changes: List<ChangeNode>,
    val unanalyzed: List<RangePair>,       // source the engine could not classify
    val fallbackReason: String?,
)

class ChangeNode(
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

class Candidate(val subject: Subject, val range: TextRange)
class RangePair(val old: TextRange?, val new: TextRange?)
data class TextRange(val start: Int, val end: Int)           // [start, end)
```

`DeclarationKind` is part of the API so engines share labels: `CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, OBJECT, CONSTRUCTOR, METHOD, FUNCTION, FIELD, PROPERTY, ENUM_CONSTANT, TYPE_ALIAS, INITIALIZER`.

A `ChangeNode` is *reported* if it is not `MATCHED`, or is moved, or its content is not `UNCHANGED`, or any child is reported. Engines may include unreported nodes for navigation; the outline hides them.

Adding fields to the result is allowed; renaming or removing them requires updating every engine and the contract tests.

## 6. Declaration engine: providers (PSI)

### 6.1 Structure

The declaration engine turns each side into a `DeclarationTree` through a per-language provider, then runs matching (§7) and classification (§8) and converts the outcome into the engine-neutral result (§5).

```kotlin
interface DeclarationProvider {
    fun supports(languageId: String): Boolean
    fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree
}
```

Providers are registered on an engine-internal extension point `declarationProvider`, so language providers can live in optional plugin descriptors (Kotlin support depends on the bundled Kotlin plugin). The engine's `supports` returns true when any provider supports the language.

Internal model (not visible outside the engine):

```kotlin
class Declaration(
    val kind: DeclarationKind,
    val name: String,                    // as written; "" for INITIALIZER
    val signature: String?,              // normalized parameter types; null for non-callables
    val range: TextRange,                // whole declaration incl. doc comment and annotations
    val children: List<Declaration>,     // nested declarations, in source order
    val ownTokens: List<Token>,          // tokens in range minus children's ranges
    val hasParseError: Boolean,          // a parse error lies in range but outside children
)

class FileRegion(val ranges: List<TextRange>, val tokens: List<Token>, val hasParseError: Boolean)

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

- PSI: inside a cancellable read action, `PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)`; `project` is the one passed to the factory, or the default project when there is none **(verify that the default project parses Java and Kotlin correctly)**.
- Offsets from the PSI equal offsets into the request text.
- Providers must not resolve references or types (no classpath; see ADR 0002).

### 6.3 Tokens

Tokens come from the language's lexer: `LanguageParserDefinitions.INSTANCE.forLanguage(language).createLexer(project)`. Token types are classified with the same `ParserDefinition`: `getWhitespaceTokens()` → `WHITESPACE`, `getCommentTokens()` → `COMMENT`, everything else → `OTHER`. One lexer pass over the whole file is sliced by range; a token that crosses a range boundary belongs to the range containing its start.

### 6.4 Java provider

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `PsiClass` (`isInterface`, `isEnum`, `isRecord`, `isAnnotationType`) | INTERFACE / ENUM / RECORD / ANNOTATION_TYPE / CLASS | `name` | — |
| `PsiMethod` (`isConstructor`) | CONSTRUCTOR / METHOD | `name` | parameter types |
| `PsiEnumConstant` | ENUM_CONSTANT | `name` | — |
| `PsiField` (not an enum constant) | FIELD | `name` | — |
| `PsiClassInitializer` | INITIALIZER | `""` (`static` → `"static"`) | — |

Not declarations: anonymous and local classes, lambdas, local variables, record components (part of the record), annotation methods' default values (part of the method).

Fields declared together (`int a, b;`) are separate declarations; the shared type tokens belong to the first field.

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

The primary constructor and its `val`/`var` parameters are part of the class's own tokens. Requires a dependency on the bundled Kotlin plugin, declared as an optional dependency with its own descriptor **(verify K2 mode PSI compatibility)**.

### 6.6 Signature normalization

The signature is the list of parameter type texts as written, joined with `,`, after removing all whitespace and comments, e.g. `foo(final  Map<String, List<Integer>> m, int... xs)` → `Map<String,List<Integer>>,int...`. Annotations, modifiers and parameter names are excluded. Type names are not resolved, so `List` and `java.util.List` differ; that is reported as a signature change (rule M3), not hidden.

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
| M2 | kind, name, signature | MATCHED, moved (container differs) |
| M3 | kind, path, name | MATCHED, signature changed (content is CHANGED) |
| M4 | kind, path, name, occurring on both sides but not once each | UNCERTAIN, with all same-key declarations of the other side as candidates |

Remaining old-side declarations are REMOVED and new-side ones ADDED.

Kinds must be equal in every rule, so a class turned into an interface is REMOVED + ADDED. Container kind/name changes affect the path, so the members of a renamed class match by M2 (moved) while the class itself is REMOVED + ADDED (see Q2).

**Order within a container.** For each matched container pair (and the top level), take the matched children in source order on both sides and compute the longest common subsequence of matches. Matched children outside the subsequence are marked moved. Ties are broken by the earliest old-side offset.

**Result tree.** Changes are arranged by the new side's containment; REMOVED declarations and those whose counterpart moved away are placed under their old container's counterpart if it exists, otherwise at the top level.

## 8. Declaration engine: classification

For each MATCHED declaration, compare `ownTokens` of the old side and the new side; do the same for the file region.

1. If either side has a parse error in that range → `UNANALYZED`.
2. If the token texts are identical, including whitespace → `UNCHANGED`.
3. Normalize both sequences with the tree's whitespace policy, then compare `(type, text)` pairs:
   - `INSIGNIFICANT`: drop `WHITESPACE` tokens.
   - `LINE_BREAKS_SIGNIFICANT`: replace each `WHITESPACE` token with `⏎` if it contains a line break, otherwise drop it.
   - `SIGNIFICANT`: no normalization (step 3 can never produce formatting-only).
   Equal → `FORMATTING_ONLY`; otherwise → `CHANGED`.
4. If rule M3 matched it (signature changed) → `CHANGED` regardless.

`COMMENT` tokens are compared by exact text, including whitespace inside them. The declaration engine leaves `details` empty; the native diff shows the detail.

## 9. Integration and threading

### 9.1 Attachment (decided by spike S1)

- **Option A, structure panel:** a `DiffExtension` whose `onViewerCreated(viewer, context, request)` attaches the outline to a `TwosideTextDiffViewer` **(verify that a side or bottom panel can be added to the viewer component for all in-scope requests)**.
- **Option B, alternate viewer:** a `FrameDiffTool` registered as `com.intellij.diff.DiffTool` that embeds a native side-by-side viewer and the outline.

Both options use the same outline component and engine registry, so the choice only affects `ui`.

### 9.2 Lifecycle

1. Viewer created → if the request is in scope and engine selection (§4) yields an engine, show the outline in a loading state; otherwise show nothing (Option A) or the unsupported state (Option B).
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

## 10. Structural outline

- Toolbar: the **engine dropdown** (§4), shown only with two or more candidate engines, listing `displayName`s with the selected one checked; and a refresh action.
- Root: a file summary line, e.g. `3 changed · 1 added · 2 removed · 1 moved · 2 formatting-only`.
- Nodes follow the result's `ChangeNode` tree. Only reported nodes and their containers are shown. A container shown only because of its children has no change label.
- Node label: for a declaration subject, kind, name and signature (`method parse(String,int)`); for a region subject, its description. Followed by labels for presence, moved, content and "signature changed".
- Groups: formatting-only changes are collapsed into a "Formatting-only" group per container (setting: show inline instead). Uncertain nodes are listed under an "Uncertain" group at the top level, each with its candidates as children.
- The file region appears as a node "File region" when reported. Unanalyzed ranges appear under an "Unanalyzed" group.
- Status messages replace the tree for `UNSUPPORTED`, `TOO_LARGE` and `FAILED`, with the reason. For `PARTIAL`, a banner says some source was not analyzed.
- Selecting a node scrolls the old side editor to `oldRange` and the new side editor to `newRange` and highlights both ranges, plus `details` if present; an absent side shows "not present" in the panel. Enter/double-click moves the caret into the new side editor (old side if removed).
- Colors use the IDE's diff color keys (inserted, deleted, modified); labels are text, not color only. Every node has an accessible name equal to its label.

## 11. Settings

Application-level, under *Settings → Tools → SMART Diff*:

| Setting | Default |
| --- | --- |
| Show structural outline automatically | Off (Option A: panel collapsed; Option B: native viewer stays default) |
| Default comparison engine, per language | One row per language that has at least one engine, each with a choice of the engines that support it; unset means the first registered engine (`smartdiff.declarations` in the first release); also set from the dropdown |
| Formatting-only changes | Collapsed group / inline |
| Size, declaration and time limits | §9.3 |

## 12. Testing

- **Engine contract suite:** an abstract test class in `engine.api`'s test fixtures that every engine's tests extend, run over a shared corpus of text pairs. It checks: identical texts → no reported changes; empty and one-side-empty texts; determinism (two runs → equal results); all ranges within bounds and on the right side; **nothing hidden** (§3.1 rule 6); prompt return after cancellation; `FAILED` rather than an exception for malformed input.
- **Abstraction test:** a test-only engine registered through the extension point (for example one that reports each changed line as a `Region`) passes the contract suite, appears in the dropdown next to the declaration engine, and can be selected; this verifies that the UI has no dependency on the declaration engine.
- **Architecture test:** fails the build if `ui` imports any `engine.<name>` package or engines import each other (§2).
- **Declaration engine, `analysis` unit tests (no IDE):** declaration trees built by hand; one test per matching rule, the LCS ordering, each whitespace policy, and every status.
- **Declaration engine, provider tests:** `BasePlatformTestCase` (Java via the light Java fixture); parse fixture text and compare the produced tree with an expected outline.
- **Golden fixtures:** `src/test/testData/<language>/<case>/{old,new}.<ext>` plus `expected-<engine id>.txt`, a text rendering of the outline per engine. Each language must cover: added/removed/changed method, overloads, signature change, reorder, move between classes, class rename, formatting-only, comment-only change, string literal change, parse error on one side, empty and identical files, and (Kotlin) a line-break-only change.
- **Integration test:** opening a `SimpleDiffRequest` produces an outline and selecting a node scrolls both editors.

## 13. Prototype spikes

| Spike | Question | Done when |
| --- | --- | --- |
| S1 | Option A or B (§9.1)? | Both attach to local-change and VCS-history diffs in Java and Kotlin, or one is shown not to |
| S2 | Do PSI files from text (default project, K2 Kotlin) give correct declarations and offsets? | Provider tests pass for both languages on VCS-revision content |
| S3 | Are the limits in §9.3 realistic? | Analysis time measured on the 20 largest Java/Kotlin files in the IntelliJ Community repository |

## 14. Open questions

- **Q1 Default visibility.** Should the outline open automatically once it has proven reliable, or stay opt-in?
- **Q2 Renamed containers.** When a class is renamed, its members currently show as moved into an added class, with the old class removed. Should the first release pair the classes as a "rename candidate" (uncertain), or keep the literal result?
- **Q3 Multi-file overview.** Is a change-list-level summary of changed declarations needed in the first release?
- **Q4 Plugin ID.** The skeleton still uses `com.example.smartdiff`; the final ID is needed before extension point names (§3.2) are fixed.
- **Q5 Third-party engines (resolved 2026-10-21).** The `comparisonEngine` extension point stays internal: only SMART Diff and its own optional descriptors register engines, and the engine interface and result model may change freely. Revisit after a second production engine has used the result model without breaking changes.
- **Q6 Engine choice scope (resolved 2026-10-21).** The default engine is chosen per language (§4, §11).
