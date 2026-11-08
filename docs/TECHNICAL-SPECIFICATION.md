# Technical Specification

Status: 2026-11-01. Describes how the features in the [feature specifications](specs/README.md) are built. Terms follow the [glossary](GLOSSARY.md). Sections 3 to 11 describe current behavior; historical release-plan context is marked where it remains, such as the spike table in §13 and the release note in Q2. Decisions are recorded in [ADR 0001](adr/0001-declaration-level-first-release.md), [ADR 0002](adr/0002-parser-neutral-model-with-psi-first.md), [ADR 0003](adr/0003-conservative-classification.md), [ADR 0004](adr/0004-pluggable-comparison-engines.md) and [ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md).

IntelliJ Platform API names below are the expected entry points. The items that needed checking against IntelliJ Platform 2025.1 were confirmed or rewritten by spikes S1 and S2 (§13); each is marked **(confirmed by S1)** or **(confirmed by S2)**.

## 1. Scope

In scope:

- Two-sided text comparisons: `ContentDiffRequest` with exactly two `DocumentContent`s, from local changes, VCS history, and "Compare with…" actions.
- One comparison engine, the **declaration engine**, for Java, Kotlin, Scala 2 and Scala 3 (Scala needs the Scala plugin) in IntelliJ IDEA (Community and Ultimate, and the unified IntelliJ IDEA from 2025.3), 2025.1 and newer with no upper bound. CI runs Plugin Verifier on 2025.1 and the latest release (§12).
- The engine abstraction, registry, selection logic and dropdown, ready for further engines.

Out of scope:

- Directory diffs, binary content, and patch previews without full file content.
- Comparisons where the old side and new side are different languages.
- A change-list-level overview across files (Q3, decided: per-file only). The per-file summary in the Changes tree is in §9.4.
- Engines other than the declaration engine (except a test-only engine used to verify the abstraction).

Three-way merges use the same engine twice and are specified in [THREE-WAY-MERGE.md](THREE-WAY-MERGE.md) and [docs/specs/merge/](specs/README.md#merges).

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
| `merge` | No | The join of a merge's base → left and base → right results ([THREE-WAY-MERGE.md §5](THREE-WAY-MERGE.md#5-the-join)) |
| `ui` | Yes | Diff integration, outline, engine dropdown, navigation, settings |

Dependency rules, enforced by an architecture test (§12):

- `ui` may depend on `engine.api` and `engine.registry` only, never on any `engine.<name>` package.
- `engine.api` depends on nothing in the plugin.
- `merge` depends on `engine.api` only.
- No engine package depends on another engine package.

`engine.api`, `engine.declarations.model`, `engine.declarations.analysis` and `merge` must compile and be unit-tested without the IntelliJ test framework.

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
    val relatedTexts: List<CharSequence>,  // hint only: other versions compared with the same old side (merge, §6.2)
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
    val renamedFrom: Subject?,             // only for MATCHED: the old subject of a rename candidate
    val kindChangedFrom: DeclarationKind?, // only for MATCHED: the old kind of a declaration that changed kind (M4b)
    val extractedFrom: List<Origin>,       // only for ADDED: old-side code it was extracted from
    val extractedTo: List<Subject>,        // only for MATCHED: where code removed from it went
)

sealed interface Subject {
    // signature: the parameter list for labels; receiver: an extension's receiver type, shown before the name
    data class Declaration(val kind: DeclarationKind, val name: String, val signature: String?, val receiver: String?) : Subject
    data class Region(val description: String) : Subject   // for engines without declarations
}

data class Candidate(val subject: Subject, val range: TextRange)    // range on the side where the node is absent
data class Origin(val subject: Subject, val declarationRange: TextRange, val range: TextRange)   // old side; range inside declarationRange
data class RangePair(val old: TextRange?, val new: TextRange?)      // at least one side
data class TextRange(val start: Int, val end: Int)           // [start, end)
```

`DeclarationKind` is part of the API so engines share labels: `CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, OBJECT, CONSTRUCTOR, METHOD, FUNCTION, FIELD, PROPERTY, ENUM_CONSTANT, TYPE_ALIAS, INITIALIZER`, and with the Scala provider (§6.8) `TRAIT, GIVEN, EXTENSION`.

A `ChangeNode` is *reported* if it is not `MATCHED`, or is moved, renamed or signature changed, or its content is not `UNCHANGED`, or any child is reported. A *rename candidate* is a `MATCHED` node with `renamedFrom` set: the engine paired two subjects with different names because their content is similar, which may be wrong, so the outline always shows it (§10). An *extraction* is an `ADDED` node with `extractedFrom` set: its code came from the old side of a changed node, named by its old range in `declarationRange`, which lists the extracted code in its `details` ([extraction detection](EXTRACTION-DETECTION.md)). A link adds information and changes no classification. Engines may include unreported nodes for navigation; the outline hides them.

The result types are data classes, so results compare by value (the contract suite's determinism check relies on it). Adding fields to the result is allowed; renaming or removing them requires updating every engine and the contract tests.

## 6. Declaration engine: providers (PSI)

### 6.1 Structure

The declaration engine turns each side into a `DeclarationTree` through a per-language provider, then runs matching (§7) and classification (§8) and converts the outcome into the engine-neutral result (§5).

```kotlin
interface DeclarationProvider {
    fun supports(languageId: String): Boolean
    fun dialect(project: Project, texts: List<CharSequence>, fileName: String?): Language? = null   // §6.2
    fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile   // §6.2
    fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree
}
```

All are called inside the engine's read actions: `dialect` once with both texts and the request's `relatedTexts`, then `parse` and `build` once per side. A language with several dialects (Scala 2 and Scala 3) chooses one from all these texts, so both trees use the same parser, lexer and whitespace policy, and both comparisons of a merge read the base alike; other providers return null and ignore it. The read action gives way to write actions: on a pooled thread it runs through `ProgressManager.runInReadActionWithWriteActionPriority` and starts over after a write, so typing in the new side never waits for a parse. Matching and classification run after it, on the model only, so no PSI outlives the read action. The engine version is the engine's own version followed by the platform build, since a parser change can change results.

The shared base `PsiDeclarationProvider` builds the tree: a language provider only says which PSI elements are declarations, with their kind, name, signature and member elements. An element it does not describe, such as a class whose name was not parsed, is not a declaration; its source and parse errors belong to its container.

Providers are registered on an engine-internal extension point `declarationProvider`, so language providers can live in optional plugin descriptors. Both languages are optional dependencies, so the plugin still loads in IDEs without them: the Java provider registers in `META-INF/smartdiff-java.xml` (depends on `com.intellij.java`) and the Kotlin provider in `META-INF/smartdiff-kotlin.xml` (depends on `org.jetbrains.kotlin`). The `supportsKotlinPluginMode` declaration must be in `plugin.xml`: in K2 mode the Kotlin plugin reads it from the main descriptor only and otherwise skips the optional one. The engine's `supports` returns true when any provider supports the language.

Internal model (not visible outside the engine):

```kotlin
class Declaration(
    val kind: DeclarationKind,
    val name: String,                    // as written; "" for INITIALIZER
    val signature: String?,              // normalized parameter types; null for non-callables
    val displaySignature: String? = signature, // parameter types for labels, with readable spacing (§6.6)
    val receiver: String? = null,        // label receiver of a Kotlin extension; null when there is none
    val range: TextRange,                // whole declaration incl. doc comment and annotations
    val children: List<Declaration>,     // nested declarations, in source order
    val ownTokens: List<Token>,          // leading whitespace, then tokens in range minus children's extents
    val hasParseError: Boolean,          // a parse error lies in range but outside children
    val leadingWhitespace: TextRange?,   // whitespace right before range that belongs to it
    val ownTokenStarts: List<Int>,       // start offset of each own token; empty when the provider has none
    val body: TextRange?,                // a method's, function's or constructor's body; null without one
)

class FileRegion(val ranges: List<TextRange>, val tokens: List<Token>, val hasParseError: Boolean)
```

A declaration's *leading whitespace* is the run of whitespace tokens between it and its previous sibling (or its container's start, or the previous top-level declaration); its *extent* is its range with that whitespace. The leading whitespace is the declaration's own source, not its container's, so adding, removing or moving a declaration takes the blank line before it along and leaves its container unchanged. A container's own tokens and the file region skip the children's extents. A node that is a change itself lists its leading whitespace on each side in `details`, so that whitespace is covered (§3.1 rule 6); `range` stays the declaration itself, for navigation. The run holds the line break that ends the previous sibling's line; classification treats the whole run as layout, so it never makes a declaration changed by itself (§8, declarations/02-change-classification R14).

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
    INDENTATION_SIGNIFICANT, // Scala 3: keep line breaks and the indentation after them
    SIGNIFICANT,             // Python, YAML: never claim formatting-only
}
```

### 6.2 Parsing transient text

- PSI: inside a cancellable read action, `PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)`; `project` is the one passed to the factory, or the default project when there is none **(confirmed by S2)**.
- Java files are parsed at the highest language level the platform knows, set with `PsiUtil.FILE_LANGUAGE_LEVEL_KEY` before the tree is built. Without it the level comes from the default project's settings, which the user can change. S2 saw `JDK_24` on 2025.1 either way, with records, sealed types, record patterns and text blocks parsed without errors.
- Scala files are parsed as `Scala3Language` or `ScalaLanguage` **(S4)**. The Scala plugin hard-wires the parser and lexer to the file's language, and a file from text has no module whose settings could change it; the language id of a request is always `Scala`, since the file type does not depend on the module. The provider parses every text (both sides and the related texts) as Scala 3 and keeps Scala 3 when none has a parse error and any uses Scala 3 syntax; otherwise it also parses them as Scala 2. It takes Scala 2 when Scala 2's error count over all texts is lower, as on code Scala 3 dropped such as `enum` as a name, or equal while no text uses Scala 3 syntax; otherwise Scala 3. Scala 3 syntax is a keyword, soft keyword or arrow only Scala 3 has (`then`, `given`, `extension`, `enum`, `export`, `end`, `derives`, `opaque`, `transparent`, `inline`, `infix`, `open`, `?=>`, `=>>`, quotes and splices), `using` in a parameter clause, a template body or package clause opened with a colon, or a definition outside any object. Syntax Scala 2.12 and 2.13 also accept under `-Xsource:3`, such as `f(using x)` in a call or the `?` wildcard, does not count: cross-built libraries such as cats use it. The Scala 3 parser accepts most Scala 2 code, procedure syntax included, so without the syntax check Scala 2 code such as cats would take Scala 3 and its stricter whitespace policy (corpus validation, [VALIDATION.md](VALIDATION.md)). When neither parses cleanly, the usual parse-error rules apply (§6.7). A Scala 3 tree uses `INDENTATION_SIGNIFICANT`, since optional braces make indentation significant; a Scala 2 tree uses `LINE_BREAKS_SIGNIFICANT`.
- Offsets from the PSI equal offsets into the request text **(confirmed by S2)**: for every declaration, the PSI element's text equals the request text sliced by its range. A declaration's range includes its doc comment and annotations in both languages.
- The request texts are document snapshots, which are always LF-separated: a VCS revision with CRLF line ends arrives as an LF document, and the content only records the original separator. Ranges therefore match the diff editors.
- Providers must not resolve references or types (no classpath; see ADR 0002).

### 6.3 Tokens

Tokens come from the language's lexer: `LanguageParserDefinitions.INSTANCE.forLanguage(language).createLexer(project)`, called with the file's own project. For text-built files that is the project passed to the parser, or the default project when there is none. A Java lexer created this way does not take the forced language level of §6.2 (the Java lexer classes that take a level are deprecated from 2026.2); tokens are only compared with each other, so a lexing difference cannot hide a change. Token types are classified with the same `ParserDefinition`: `getWhitespaceTokens()` → `WHITESPACE`, `getCommentTokens()` → `COMMENT`, everything else → `OTHER`. One lexer pass over the whole file is sliced by range; a token that crosses a range boundary belongs to the range containing its start.

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

The signature is the list of parameter type texts as written, joined with `,`, after removing all whitespace and comments, e.g. `foo(final  Map<String, List<Integer>> m, int... xs)` → `Map<String,List<Integer>>,int...`. Annotations, modifiers and parameter names are excluded; Java C-style array brackets after the name stay (`int values[]` → `int[]`), and so do Kotlin modifiers that are part of a type (`suspend () -> Unit` → `suspend()->Unit`). A Kotlin `vararg` parameter's type ends in `...` as in Java, so `f(x: T)` and `f(vararg x: T)` differ. A Kotlin function with a receiver puts the receiver type and a `.` first: `fun List<T>.second(x: Int, vararg ys: String)` → `List<T>.Int,String...`, and `fun Cart.isFull()` → `Cart.`; a property's signature is its receiver type alone. A script's statements are part of the file region. Type names are not resolved, so `List` and `java.util.List` differ; that is reported as a signature change (rule M3), not hidden.

The signature is a matching key. Labels use a second form of it, the *display signature* (`Declaration.displaySignature`, `Subject.Declaration.signature`): the same parameter types, but where the source separates two words one space stays (`Class<out Annotation>`, `? extends E`), and there is none next to `<>()[]{},.`; a Kotlin `vararg` parameter reads `vararg T`, and a receiver is left out of it and shown before the name instead (`Subject.Declaration.receiver`: `function Date.formatIsoDate()`, `property List<T>.second`). Scala labels space operators and arrows differently (§6.8).

### 6.7 Parse errors

- An error element inside a declaration's own range sets `hasParseError`; the declaration is still matched by identity if its name was parsed, but its content is `UNANALYZED`, unless its own tokens have the same text on both sides and its signature did not change: then it is `UNCHANGED`, since the same source parses the same way on both sides. So a parse error, for example on code the parser does not know, marks only code that changed. The same holds for the file region.
- An error in the file region sets the file region to `UNANALYZED`, unless its tokens are the same on both sides.
- Only errors in code count. Errors the Scaladoc parser reports inside a doc comment, such as a wiki link that closes before it opens (`[[F[A]]]` in prose), are markup, not code; they neither set `hasParseError` nor count when choosing the Scala dialect (§6.2). The comment's tokens are still compared, so a change inside it is still a change.
- If either side has errors and no declarations were found while the file is non-blank, the result is `FAILED` with the native diff shown.
- A result with any `UNANALYZED` content has status `PARTIAL`.

### 6.8 Scala provider

Implemented, when the Scala plugin is installed ([languages/03](specs/languages/03-scala.md)); it resolves Q7. The provider uses the Scala plugin's PSI only, never its type inference, in the dialect chosen by §6.2. Names are the names as written (`ScNamedElement.name()`; `getName()` gives an object's JVM name, `Shapes$`).

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `ScClass` (also case and abstract classes) | CLASS | `name()` | — |
| `ScTrait` | TRAIT | `name()` | — |
| `ScObject` (also companions and package objects) | OBJECT | `name()`; a package object takes its package's name | — |
| `ScEnum` (Scala 3) | ENUM | `name()` | — |
| enum case | ENUM_CONSTANT | `name()` | — |
| `def` (definition or abstract declaration, not local) | FUNCTION | `name()` | parameter clauses |
| `def this(…)` | CONSTRUCTOR | `""` | parameter clauses |
| `val`, `var`, `lazy val` (definition or abstract declaration, not local) | PROPERTY | the bound name; `(a, b)` for a pattern that binds several | — |
| `type` (alias or abstract type member) | TYPE_ALIAS | `name()` | — |
| `given` (Scala 3) | GIVEN | `name()`; for an anonymous given, its type normalized like a signature (`Ordering[Int]`) | `using` clauses; none without them |
| `extension` block (Scala 3) | EXTENSION | the receiver's type normalized like a signature (`String`, `List[T]`) | `using` clauses; none without them |

- **Containers:** classes, traits, objects, enums, givens with a body and extension blocks; an extension block's methods are its `FUNCTION` children, so a change to the receiver shows on one node. Definitions in `package` clauses, braced or not, are top-level declarations; so are Scala 3 top-level definitions.
- **Not declarations:** local definitions inside blocks, anonymous classes (`new T { … }`), statements in a class body, the primary constructor and its `val`/`var` parameters, `derives` clauses and `import`s. Neither is a definition that binds no name, such as `val _ = f()`. Their source belongs to the declaration that contains them, or to the file region.
- **Several on one line:** `case A, B` gives two enum constants, and `val a, b = 0` and `val a, b: Int` two properties each. As for Java fields (§6.4), the first takes the shared tokens before its name (`val a`, `case A`), the last takes everything after its own name (`b = 0`, `b: Int`), and the commas belong to the container, so adding a case also changes the enum's own tokens, as adding a Java enum constant does. A pattern definition `val (a, b) = …` binds through one pattern, so it is one property named `(a, b)`.
- **Anonymous givens** are named by their type, normalized like a signature, without whitespace (`given Ordering[List[ Int ]]` → `Ordering[List[Int]]`), not by the compiler's synthesized name, which is harder to read and has changed between compiler versions. Two anonymous givens of one type in one container repeat a key and follow the matcher's rules for repeated keys. An extension block's name, its receiver type, is normalized the same way (`List[T]`).
- **Signatures:** each parameter clause is normalized as in §6.6 (types only, without whitespace, comments, annotations, modifiers and names), the types joined with `,` and the clauses with `;`. A `using` or `implicit` clause keeps its keyword and one space before its types. So `def f[A](x: A, y: Int)(using Ordering[A])` → `A,Int;using Ordering[A]`. Type parameters and context bounds are left out, as for Java and Kotlin; repeated (`T*`) and by-name (`=> T`, normalized `=>T`) types stay as written. `def f` has no clause and no signature, `def f()` has the empty signature, so changing one into the other is a signature change (rule M3). A given or an extension block without a `using` clause has no signature.
- **Display signatures** keep the same clauses, joined with `;`, but Scala labels have their own spacing, since `<`, `>` and `=` make operators and arrows in Scala rather than brackets: there is no space just inside `(`, `[`, `{`, `)`, `]` and `}` or next to `,` and `.`, and otherwise one space where the source has whitespace. So `(x: => Int)` reads `=> Int`, `ev: Int <:< Int` reads `Int <:< Int`, and `f: (Int, Int) => Int` reads `(Int,Int) => Int`.
- **Kinds and matching:** a class and its companion object share a name but differ in kind, so they never collide, and rule M4b never pairs an object. Changing a `class` into a `trait`, a `case class` into an `enum` or a `def` into a `val` keeps the name, so M4b reports it as one declaration that changed kind (`trait → class RepresentableMonad`, signature changed) when it is the only such pair in its container; its members stay in place. Rename candidates (M5) still need the same kind.
- **Whitespace:** Scala 2 trees use `LINE_BREAKS_SIGNIFICANT`, like Kotlin, since Scala also infers semicolons from line breaks. Scala 3 trees use `INDENTATION_SIGNIFICANT` (§8), since optional braces make indentation part of the meaning. Code without Scala 3 syntax takes Scala 2 (§6.2), so re-indenting it stays formatting-only.

## 7. Declaration engine: matching

Inputs: the old and new `DeclarationTree`. Matching is global over all declarations in the file, so a declaration can be matched across containers. Each rule runs over the declarations still unmatched after the previous rule. Within a rule, declarations are processed in source order of the old side, which makes the result deterministic.

Keys (the *path* is the chain of container `(kind, name)` pairs from the top level):

| Rule | Key | Result when the key occurs exactly once on each side |
| --- | --- | --- |
| M1 | kind, path, name, signature | MATCHED. A key that occurs more than once on a side first pairs its declarations with the same content (tokens other than whitespace, children included), in old-side source order; the rest of the group pairs if it is then once on each side |
| M2 | kind, name, signature | MATCHED; moved if the containers are not a match of each other |
| M3 | kind, path, name | MATCHED, signature changed (content is CHANGED). A key that occurs more than once on a side (overloads that all changed signature) pairs by similarity over the whole group: parameter types and content weigh the same, from 0.5 up; the second unique-key pass then pairs what is left |
| M4 | kind, path, name, occurring on both sides but not once each | UNCERTAIN, with all same-key declarations of the other side as candidates |
| M4b | name and kind family, in a pair of matched containers or both top level, still removed and added, kinds different | MATCHED, signature changed (a kind change changes how the declaration is used), when the key occurs exactly once on each side. Families: types (`CLASS`, `TRAIT`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION_TYPE`; never `OBJECT`, since a class and its companion object share a name) and value members (`METHOD`, `FUNCTION`, `FIELD`, `PROPERTY`: a Scala `def` turned into a `val`, a Kotlin `fun` into a `val`). Labels show both kinds, as a rename candidate shows both names: `trait → class RepresentableMonad` |
| M5 | kind, in a pair of matched containers or both top level, still removed and added | MATCHED as a rename candidate when the names differ, by the weighted score of [relationships/03](specs/relationships/03-relationship-matching.md) from 0.7 up, over the whole candidate group |

Remaining old-side declarations are REMOVED and new-side ones ADDED.

**Similarity and assignment** (M3 for repeated keys, M5). M3 scores a pair as the mean of its parameter-type Dice and its content Dice, and pairs from 0.5 up. M5 scores a pair as content 0.5, name 0.15, parameter types 0.15, nearest matched sibling 0.1 and order 0.1, and pairs from 0.7 up; a declaration with fewer than 6 content tokens besides its name, such as an empty method, has content 0, and an edge that touches a declaration with a duplicate body in its group is scored without content, with the other weights renormalised. Content is the Dice coefficient of the bags of adjacent token pairs (tokens other than whitespace, children included, the declaration's own name masked), so a rename alone leaves two declarations identical; parameter types compare as a bag of types. Within each group the assignment is the largest total score whose chosen pairs are each at least 0.1 ahead of every rival, where a rival is an option to a declaration the assignment leaves unpaired and its raw score counts even below the threshold; each declaration appears once. In M5 the names break a near-tie: the Dice coefficient of their adjacent character pairs, ignoring case, must be at least 0.5 and be at least 0.2 above every rival's, applied after the assignment. Equal totals go to the old-side source order, then the new side. A group with more than 10 000 possible pairs is not compared.

**M5 order.** Container pairs are processed top level first, then every matched container pair in old-side order, and a container paired by M5 is processed after that, so the members of a renamed class that M2 could not pair are compared too. A Java constructor whose names are its classes' names (the class was paired by M5) follows the class: it is matched without a rename of its own, including an empty constructor, which is too small for content similarity. A parameter type that contains an arrow, such as `(Int) -> Unit`, stays one type in the parameter bag, so the arrow never splits a type.

**Similarity margins.** A rival is an option from either endpoint of a chosen pair to a declaration the assignment leaves unpaired. Every such option counts when its raw score is within 0.1 of the pair's score, including an option below the rule's threshold: a below-threshold rival can therefore leave both declarations unpaired. In M5 the names decide such a case: the chosen pair's name similarity must be at least 0.5 and be at least 0.2 above every rival's, applied after the assignment; otherwise that pair is left unpaired. These cases are covered by `DeclarationMatcherTest`.

Kinds must be equal in every rule except M4b, which pairs a class turned into an interface or a trait, and a container paired by M4b is processed by M5 like any matched pair. A path holds a type's kind family rather than its kind, so the members of a class turned into a trait keep their path and match by M1, in place, even when their names repeat in the file. Container name changes affect the path, so the members of a renamed class match by M2; once M5 pairs the class itself, they are in matched containers and not moved (see Q2). Moved by container means the old and new containers are not matched to each other (or only one of them is top level); the members of a class that itself moved by M2 have a different path too, but they stay in the same matched container and are not moved.

**Order within a container.** For each matched container pair (and the top level), take the matched children in source order on both sides and compute the longest common subsequence of matches. Matched children outside the subsequence are marked moved. Ties are broken by the earliest old-side offset.

**Result tree.** Changes are arranged by the new side's containment. Declarations present only on the old side (REMOVED, and old-side UNCERTAIN) are placed under their old container's counterpart if it exists, under their old container's own node if that container is only on the old side too, otherwise at the top level. Among their new siblings they follow the counterpart of their nearest preceding old sibling, so a removed declaration comes before one added in its place. Every declaration of both sides appears exactly once. The file region is one node spanning its ranges on each side (an empty range at offset 0 for a side without one), with the separate ranges in `details`; it is null when its tokens are identical.

The matcher and classifier are `DeclarationMatcher` and `ContentClassifier`; `DeclarationAnalysis` runs them and builds the result, including `TOO_LARGE` (§9.3) and `FAILED` (§6.7).

## 8. Declaration engine: classification

For each MATCHED declaration, compare `ownTokens` of the old side and the new side; do the same for the file region.

For a rename candidate, and a constructor that follows its class's rename, the old side's tokens equal to the old name are compared as the new name, so the rename alone leaves the content `UNCHANGED`; the rename has its own label.

The steps are applied in order, and the first that applies decides the content:

1. If the signatures are the same and the token texts are identical, including whitespace → `UNCHANGED`.
2. If either side has a parse error in that range → `UNANALYZED`. This wins over a signature change: `signatureChanged` is still reported, but the content is `UNANALYZED`.
3. If the signatures differ (rule M3, or a rename candidate whose parameters changed too) → `CHANGED`.
4. Normalize both sequences with the tree's whitespace policy, then compare `(type, text)` pairs:
   - `INSIGNIFICANT`: drop `WHITESPACE` tokens.
   - `LINE_BREAKS_SIGNIFICANT`: replace each `WHITESPACE` token with `⏎` if it contains a line break, otherwise drop it.
   - `INDENTATION_SIGNIFICANT`: replace each `WHITESPACE` token that contains a line break with `⏎` followed by the indentation after its last line break, as written; drop the others. Indentation is compared as written, not relative to the container, so re-indenting a whole block, or a tab for spaces, is `CHANGED`; that is strict but never hides a change of structure. Blank lines and spaces within a line still count as formatting.
   - `SIGNIFICANT`: no normalization (step 4 can never produce formatting-only).
   Equal → `FORMATTING_ONLY`; otherwise → `CHANGED`.

In step 4 a run of adjacent `WHITESPACE` tokens counts as one: own tokens skip the children's extents, so the whitespace before and after a child can become adjacent, and adding or removing a child must not change the count of line breaks; under `INDENTATION_SIGNIFICANT` the run's indentation is the text after its last line break. Because each child owns its leading whitespace (§6.1), adding, removing, moving or reordering members leaves their container `UNCHANGED`. Removing the first member is the exception: the next member's leading whitespace changes (the blank line after `{` goes), so that member is `FORMATTING_ONLY`.

A difference only in the leading whitespace run is `FORMATTING_ONLY`, even when the policy counts line breaks, because the run holds the line break that ends the previous sibling's line (§6.1, declarations/02-change-classification R14). Under `INDENTATION_SIGNIFICANT` the indentation after the run's last line break still counts, so re-indenting a declaration keeps it `CHANGED`.

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

Spike S3 measured them on the 20 largest Java and Kotlin files in IntelliJ Community outside test data (16 Java, 4 Kotlin; 238 KB to 1.6 MB), on an Apple M1 Pro, each compared with itself, with an empty line inserted after every 10th line and with a comment line inserted after every 10th line. The median of five runs was at most 333 ms for any file, including the 1.6 MB one (32 000 lines, about 280 ms with changes everywhere), 142 ms for the largest Kotlin file (1 MB), and under 100 ms for files under 350 KB. The 5 s timeout leaves more than ten times that headroom for slower machines, and the 1 MB size limit, not time, is what stops the largest generated files. The limits stay as they are. Rerun with `-Psmartdiff.limits.corpus=<file listing paths>`, which runs `LimitsBenchmarkTest`.

### 9.4 Changes tree summary

Implemented after 0.1.0 ([roadmap](ROADMAP.md#multi-file-summary-in-the-changes-tree), levels 1 and 2; decided by spike S5, [ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md)). Each changed file in the Local Changes view and the Commit tool window shows its summary line (§10) after its name, in grey and starting in lower case: `Parser.kt  2 changed · 1 added`. The file can be expanded to show its outline under it (level 2). The tree has its own View Options actions and its own change type filter, separate from the outline's ([changes-tree/04](specs/changes-tree/04-hide-and-filter.md)).

- `ChangesSummaryModifier`, a `vcs.changes.changesViewModifier` extension in the optional descriptor `smartdiff-vcs.xml` (loaded with `com.intellij.modules.vcs`), runs while the platform builds the tree. It reads the tree's "Structural Summary" and "Declarations" settings. For each file node of a modified or moved file whose language has an engine (§4), it looks up the summary and, if one is known, replaces the node with a `SummaryChangeNode` for the same change, which renders as the platform's node and, when the summary setting is on, the grey summary or reason. It adds `DeclarationNode` children for the file's filtered rows when the declarations setting is on. With both settings off it leaves the platform's rows as they are.
- **Tree actions:** the View Options menu (the eye icon) has "Structural Summary" and "Declarations" toggles and a "Hide Change Types" submenu. The plugin registers them in `smartdiff-vcs.xml` and adds their group to the platform's public `ChangesView.ViewOptions` group at runtime. Both target IDEs, 2025.1 and 2026.3, expose that group; manual verification found the SMART Diff group there on both (`inViewOptions=true`, `inToolbar=false`). The `ChangesViewToolbar` fallback is defensive only, for a build where the group is absent or rejects plugin actions, and then makes the actions toolbar buttons. The placement is idempotent and uses only public `ActionManager` and `DefaultActionGroup` API. The toggles bind to the same settings as the Settings page (§11). Changing a toggle or the tree's filter publishes `ChangesViewModifier.TOPIC` and rebuilds the tree without comparing a file again; the summary cache is kept.
- Added and deleted files and files without an engine get no summary. That is intended for 0.2.0; giving added and deleted files a summary is later work ([roadmap](ROADMAP.md#summaries-for-added-and-deleted-files)). A supported-language file whose result is `FAILED` or `TOO_LARGE` shows a grey reason in place of the summary: `not analyzed: too large`, `not analyzed: timed out`, `not analyzed: parse errors` or `not analyzed: analysis failed`; such a row has no declaration children. At most 500 file nodes of a tree are considered, in the tree's depth-first order; the rest keep the platform's rows. The limit is intended and not a setting ([changes-tree/03](specs/changes-tree/03-coverage-and-scale.md)).
- `ChangesSummaryService` computes summaries one file at a time on a background thread, with the limits of §9.3, and caches them by both paths, a version of each side (the revision number, or the local file's document or file modification stamp) and the engine id, so an edit or an engine change gives a new summary. Files that leave the tree are forgotten. A timed-out summary stays visible while one retry is queued after a short delay; a second timeout is kept. When summaries arrive, it publishes `ChangesViewModifier.TOPIC` at most once per 300 ms to rebuild the tree. A `FileSummary` keeps the unfiltered rows and the summary text without a hidden suffix, so the modifier filters and recounts from the cache.
- The summary counts every change, as the outline's does, and, when the tree's own filter hides some, ends with how many, such as `· 2 hidden`. When every change is hidden, the summary ends with `· all hidden` and the file has no declaration rows but stays in the tree. The tree's filter is separate from the outline's and starts with nothing hidden.
- **Declarations (level 2):** a file with a summary gets the outline's rows as children (`DeclarationNode`): the same rows, nesting, groups and labels as the file's outline (§10), drawn the same way. The tree's own filter applies to them under the outline's rules: changed includes signature changes, a change is hidden only when all of its labels are hidden, uncertain, rename candidate and unanalyzed changes are never hidden, and a hidden change with shown changes inside it stays as a container without labels. They are computed with the summary, from the same result, and so follow edits the same way. A file starts collapsed and the tree keeps the user's expanded rows across rebuilds; within an expanded file, containers start collapsed too. A file whose every change is hidden has no rows and remains a leaf.
- Declaration rows are helper nodes for the platform (`markAsHelperNode`), so they are not part of the selected or included changes: the file's checkbox and the commit count only the file, and selecting a declaration selects no change. The platform still draws an unchecked, inactive checkbox on each declaration row in the Commit tool window, which has no public hook to hide it.
- **Speed search:** `DeclarationNode.getTextPresentation` is the row's label, such as `method isEmpty()  added`, so the tree's speed search finds a shown declaration row by its label. A row the filter hides, or a tree with the declarations off, has no row to find.
- **File rows:** a summarized file row has children, so the Changes tree would otherwise treat a double-click as an expand/collapse toggle and the platform would skip Enter for the selected non-leaf row. While a tree has declaration rows, `ChangesTreeSetup` saves its toggle click count, sets it to 0 and installs a press listener that consumes the second press on a summary or declaration row before `BasicTreeUI` toggles it; `ChangesListView` overrides `getToggleClickCount()` to 2, so the press listener is the part that takes effect. The setup runs on every Local Changes and Commit tree in the project, not only the first one. It takes the row state from the tree model being built, not from the tree's current model, so the first rebuild that adds declaration rows installs it before the platform applies the new model. A tree that still shows declaration rows keeps the setup when another tree's rebuild has none. For a summarized file row, the double-click handler calls the platform's own double-click handler with the same click at a count of one, so the platform's non-leaf toggle check does not reject the row and its choice of editor preview, diff or source is the same as for a file without a summary; the platform's Enter handler makes the same choice with a request focus of false, and `OpenFileDescriptor.navigate` opens the source when that handler declines. The plugin does not create its own preview: the preview tab is the platform's `TabPreviewDiffVirtualFile`, as for a plain row, and the platform reads the revisions. The tree's double-click handler opens a declaration through `DeclarationNodeAction`; other rows keep their normal double-click expansion. The arrow and Left/Right keys still expand and collapse every row. When summaries are turned off, or no summary has declaration rows, the saved toggle click count is restored and the press listener is inactive. Enter is handled by a replacement for the platform's key listener that keeps its leaf-only guard for ordinary rows and bypasses it for summarized file and declaration rows. When that platform listener cannot be recognised, non-leaf declarations are opened directly because its guard would skip them, while leaf declarations are left to it so a row cannot open twice. Both use the actual selected `ChangesBrowserNode`, not `VcsTreeModelData.selected(...).iterateNodes()`, which filters out the helper declaration nodes ([changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) R9, R14 to R20).
- **Opening a declaration:** double-click or Enter on a declaration row (`DeclarationNodeAction`, a `vcs.changes.changesViewNodeAction` extension) opens the file's diff through the platform's diff action, with the tree's other changes next to it, and puts the row's `OutlinePath` on the request: the titles of the row and the rows above it, groups named by kind. When the diff's outline first shows a result, it selects that row, or the nearest row above it that the outline's filter leaves, which frames it on both sides (§10); the outline is shown for that diff even if it starts off by default. A row with children expands through the tree's expansion control; double-clicking it opens the diff, as for a row without children. The extension point is marked experimental in the platform. The action is looked up reflectively through the classloaders of the Changes view and of the plugin, because 2026.3 moved it to a VCS module the verifier no longer resolves. When no loader can see it, the row opens the file's own diff through a lazy `DiffRequestProducer` and `SimpleDiffRequestChain`: the producer reads both revisions under the diff window's progress indicator, off the UI thread, and the diff opens on the UI thread, without the declaration context, so the file still opens ([changes-tree/02](specs/changes-tree/02-changes-tree-declarations.md) R16).
- **Following the caret:** a project service follows caret moves in a plain editor and in both editors of a two-sided diff of a file in the tree. It returns without touching the tree for any `DIFF_VIEWER` that is not a `TwosideTextDiffViewer` and for a `MERGE_VIEWER`, so three-sided diffs and merge windows are unchanged. It uses the file's cached summary, maps the offset to the innermost changed declaration row on that side (the new-side range for a plain editor and the new side, the old-side range for the old side), and expands and selects that row; when the caret is outside every changed declaration, or the file has no summary, it selects the file row. In the platform's selection-following Changes preview, it selects the file row only: a declaration row is a helper node with no change, so selecting it closes the preview on 2025.1 and empties it on 2026.3. Normal editors and diff tabs keep the declaration mapping. It never moves focus, opens a diff itself or changes inclusion; the platform's own single-click preview may follow the selection as it does for any selection. A stale summary after an edit does not change the tree's selection; if an intermediate rebuild shows only a plain pending row, following selects that file row and keeps retrying, and the row under the caret is selected when the new summary arrives. When the tree's filter hides the caret's declaration, the nearest shown row above it is selected, or the file row when none is shown (R10). Caret moves are debounced by 100 ms, a selection made in the tree cancels a pending follow, and a mapping error is logged once without changing the tree. The service starts on the EDT and reads no revision content on the EDT. The platform has no Select Opened File setting for this tree, so the feature has its own setting in §11 ([changes-tree/05](specs/changes-tree/05-changes-tree-caret-follow.md)).
- Changing a display toggle of the tree, including Show structural summary, or changing the tree's filter only rebuilds the tree and keeps the summary cache. Follow the caret in the Changes tree only starts or stops following and does neither. A setting that affects the analysis, such as an engine, a limit or the confidence policy, and the formatting-only layout, which changes the cached rows, clears the cache and computes the summaries again (§11). `ChangesSummaryService` records the analysis stamp with the entries it guards, and both `summaries()` and `settingsChanged()` clear the cache when the current stamp differs, so the first analysis change after IDE start recomputes even when the settings listener is created after that change. The listener always applies the analysis stamp, and it rebuilds the tree when the tree display or filter revision changed or when `EngineDefaults.revision` or the confidence policy changed; a change that only affects the outline, merge or follow display settings does not rebuild the Changes tree.

## 10. Structural outline

- **Outline toggle:** a toolbar button on every in-scope diff switches between the outline and the plain native diff by showing or hiding the outline panel; the editors stay as they are. The panel header's Hide button (`AllIcons.General.HideToolWindow`) takes the same off path. Its initial state comes from the setting in §11, and it affects only the current diff window: the window keeps the choice (in its `DiffContext`) for the next files it shows, and other windows are unaffected. While the outline is off, no comparison runs. The toggle is first in `Diff.ViewerToolbar`, so a narrow window moves the later actions into the overflow before it, and it has the default shortcut `Ctrl+Alt+Shift+O` (`Cmd+Option+Shift+O` on macOS) in `$default`, listed in Keymap.
- **Minimised outline:** the panel header's Minimise button removes the panel from the splitter and puts a 24 px strip with the outline's icon at `BorderLayout.EAST` of the viewer, so the editors take the panel's width; clicking the strip or pressing Enter or Space while it has focus puts the panel back, and a focus border shows where Tab is. The comparison is not canceled or rerun, and the splitter's proportion is not changed, so the panel returns at its previous width and its rows are already there. The strip's tooltip and accessible name carry the summary line, or the fallback title and reason for unsupported, too large and failed results, or say that the outline is minimised before a result exists.
- **Remembered mode:** the minimised state is stored with the plugin settings (`outlineMinimised`, not shown on the settings page) and the width by the splitter's `smartdiff.outline.proportion` key, so the next diff starts in the last visible mode at the last width. A diff opened from a declaration in the Changes tree shows the full panel, so the selected row is visible.
- **Languages without an engine:** a two-sided text diff whose two contents are in the same language still gets the outline when no engine supports that language. It shows the `NoEngine` message, naming the language by its display name, and the native diff; the outline toggle behaves as for a supported language. Plain text (`TEXT`) with no engine is left alone and shows the native diff only.
- Outline toolbar: the **engine dropdown** (§4), shown only with two or more candidate engines, listing `displayName`s with the selected one checked; a **change type filter**; and a refresh action.
- **Change type filter:** a menu of change types the user can hide from the outline: changed (including signature changes), added, removed, moved and formatting-only. A change is hidden when every one of its labels is of a hidden type, so a method that is moved and changed stays while either is shown; a hidden change with shown changes inside it stays as a container without labels. Uncertain, rename candidate and unanalyzed changes have no type and are never hidden ([ADR 0003](adr/0003-conservative-classification.md)), since a rename candidate may be a wrong pairing. The filter is remembered as a setting (§11) and applies to every outline; changing it rebuilds the current outline without comparing again. The Changes tree has its own filter (§9.4).
- **SMART Diff viewer:** "Side-by-side viewer (SMART Diff)" is offered in the diff's viewer list beside the native viewers, which keep their order, so the native side-by-side viewer stays the default until the user picks this one; the IDE remembers the choice per kind of diff. The viewer has its own 16×16 icon, the native side-by-side mark with a filter funnel and a `_dark` variant, loaded with `IconLoader`; `FilteringDiffTool.getIcon()` overrides the platform's public `FrameDiffTool.getIcon()` from 2026.2, whose viewer switcher shows viewer icons, while 2025.1's text-only switcher has no public icon hook. The platform has no separate description for a diff tool, so the switcher's tooltip is the name. The name stays `Side-by-side viewer (SMART Diff)`; the IDE stores the viewer order by tool class, not by name, so saved choices survive unchanged. It is the native side-by-side viewer with one difference: a native change whose lines on both sides all belong to changes the filter hides is shown as excluded (the IDE's faint style for changes it ignores, with no fill and no gutter actions) and next/previous change skips it. A line belongs to a change through its own extent: its range and `details` minus its reported children's, and for the file region its `details`. Lines of shown changes and unanalyzed ranges are never faded. The marks come back while an edit is being compared again, and whenever the outline is off. The native viewer cannot be changed by a public API after it computes its changes, and replacing it would push it behind the unified viewer in the IDE's saved viewer order.
- Root: a file summary line, e.g. `3 changed · 1 added · 2 removed · 1 moved · 2 formatting-only · 1 rename candidate`. It counts hidden changes too and ends with how many are hidden (`· 2 hidden`); when every change is hidden, the tree says so instead of "No structural changes".
- Nodes follow the result's `ChangeNode` tree. Only reported nodes and their containers are shown. A container shown only because of its children has no change label.
- Node label: for a declaration subject, kind, receiver, name and display signature (`method parse(String,int)`, `function Date.formatIsoDate()`); a rename candidate shows the old name first (`method total → sum(int)`). For a region subject, its description. Followed by labels for presence, "rename candidate", moved, content and "signature changed".
- Groups: formatting-only changes are collapsed into a "Formatting-only" group per container (setting: show inline instead). Uncertain nodes are listed under an "Uncertain" group at the top level, each with its candidates as children.
- The file region appears as a node "File region" when reported. Unanalyzed ranges appear under an "Unanalyzed" group.
- Status messages replace the tree for `UNSUPPORTED`, `TOO_LARGE` and `FAILED`, with the reason. For `PARTIAL`, a banner says some source was not analyzed.
- Selecting a node scrolls the old side editor to `oldRange` and the new side editor to `newRange` and highlights both ranges, plus `details` if present; an absent side shows "not present" in the panel. Synchronized scrolling is paused only while the selected item has ranges on both sides and both frames are in view. While it is paused, a visible-area change in either editor scrolls the other to the same position relative to the pair: by the same offset before the frames, by the same fraction of their heights inside them (so two versions of different lengths stay aligned), and by the same offset after them. The frame positions are read from the selection's highlighters on every scroll, so folding, soft-wrap reflow and inlays do not make the pair drift. The pause ends and normal synchronized scrolling returns when either frame leaves view, the selection changes or clears, either document changes or the viewer is disposed. With Synchronize scrolling off, the outline frames the row but does not hold the editors together. Enter/double-click moves the caret into the new side editor (old side if removed).
- Colors use the IDE's diff color keys (inserted, deleted, modified); labels are text, not color only. Every node has an accessible name equal to its label.

## 11. Settings

Application-level, under *Settings → Tools → SMART Diff*:

| Setting | Default |
| --- | --- |
| Show structural outline automatically | On (decided 2026-10-21). When off, the outline toggle (§10) starts off and the panel is hidden |
| Default comparison engine, per language | One row per language that has at least one engine, each with a choice of the engines that support it; unset means the first registered engine (`smartdiff.declarations` in the first release); also set from the dropdown |
| Show structural summary in the Changes tree | On. Each changed file in Local Changes and the Commit tool window shows its summary (§9.4); also set from the tree's "Structural Summary" toggle |
| Show declarations in the Changes tree | On. Each summarized file can be expanded to show its declaration rows (§9.4); also set from the tree's "Declarations" toggle |
| Follow the caret in the Changes tree | On. The tree selects the declaration under the caret in the editor or a two-sided diff of the file (§9.4); changing it does not clear summaries or rebuild the tree |
| Formatting-only changes | Collapsed group / inline |
| Hide from the outline | None. One checkbox per change type of the outline's filter (§10); also set from the outline toolbar |
| Hide from the Changes tree | None. One checkbox per change type of the tree's own filter (§9.4), separate from the outline's; also set from the tree's View Options menu, or its toolbar if that group is unavailable |
| Size, declaration and time limits | §9.3 |

## 12. Testing

- **Nothing-hidden check:** `NothingHidden` decides rule 6 exactly, as a path through the grid of old and new offsets that may skip covered characters and must match uncovered ones. It walks the old text row by row and keeps the reachable new offsets as sorted intervals, so a file with local changes checks in about linear time; `NothingHiddenTest` compares it with the full table on random cases.
- **Engine contract suite:** `ComparisonEngineContractTest`, an abstract JUnit 4 class in `src/test/kotlin/.../engine/api/contract` that every engine's tests extend, run over a shared corpus of Java, Kotlin and plain-text pairs (`ContractCorpus`). The contract corpus does not include Scala; the Scala provider is covered by its provider tests and golden fixtures. Engines whose tests need the IntelliJ test framework run the same checks through `ComparisonEngineContract`. It checks: identical texts → no reported changes; empty and one-side-empty texts; determinism (two runs → equal results); all ranges within bounds and on the right side; **nothing hidden** (§3.1 rule 6); prompt return after cancellation (the engine checks regularly on a large input, and the exception from `checkCanceled` propagates unchanged whether it is thrown at the first, a middle or the last check); `FAILED` rather than an exception for malformed input.
- **Abstraction test:** a test-only engine registered through the extension point (`LineEngine`, which reports each run of changed lines as a `Region`) passes the contract suite, appears in the dropdown next to the declaration engine, and can be selected; this verifies that the UI has no dependency on the declaration engine.
- **Architecture test:** an [ArchUnit](https://www.archunit.org/) test over the compiled classes that fails the build if any rule in §2 is broken, and if `engine.api`, `engine.declarations.model` or `engine.declarations.analysis` use IntelliJ or Kotlin plugin classes. A fixture of deliberately violating classes checks that every rule can fail.
- **Declaration engine, `analysis` unit tests (no IDE):** declaration trees built by hand; one test per matching rule, the LCS ordering, each whitespace policy, and every status.
- **Declaration engine, provider tests:** `BasePlatformTestCase` (Java via the light Java fixture); parse fixture text and compare the produced tree with an expected outline.
- **Golden fixtures:** `src/test/testData/<language>/<case>/{old,new}.<ext>` plus `expected-<engine id>.txt`, a text rendering of the outline per engine: the status, then one line per reported node and per container of reported nodes, with its labels and the lines it spans on each side. Every fixture is also checked for nothing hidden. `-Psmartdiff.updateGolden=true` rewrites the expected files from the current results, for review before committing. Java, Kotlin, Scala 2 and Scala 3 have their own directories (`java/`, `kotlin/`, `scala2/`, `scala3/`). Each language must cover: added/removed/changed method, overloads, signature change, overloads that all changed signature, reorder, move between classes, class rename, method rename, formatting-only, comment-only change, string literal change, parse error on one side, empty and identical files, declarations that share a key (several static initializers in Java, a parameter and a `vararg` overload in Kotlin), and (Kotlin, Scala) a line-break-only change. Scala adds a companion object change and an extension method change, Scala 3 also a given, an enum and a change from braces to indentation, which is never formatting-only. Java also has `noisy-change`, a realistic edit that mixes most of these with reformatting, plus code extracted into a new method and into a new class, as a demo of what the outline is for.
- **Corpus validation (opt-in):** `CorpusValidationTest` compares every modified Java, Kotlin and Scala file of a list of real commits (`-Psmartdiff.validation.corpus=<list>`) and flags results that look wrong or noisy: a fallback status, a nothing-hidden violation, a difference with nothing reported, uncertain matches, removed + added pairs that may be renames or unpaired signature changes, extractions and extraction near misses, more reported changes than native hunks suggest, and slow files. CI skips it; it runs before each release, and the results are kept in [VALIDATION.md](VALIDATION.md).
- **Integration test:** `OutlineIntegrationTest` opens `SimpleDiffRequest`s through `DiffManager.createRequestPanel`: the outline appears for Java and Kotlin, selecting a node highlights its range on both sides, a selected moved pair keeps its frames aligned while scrolling, including after a fold changes the line layout, and releases the hold when a frame leaves view, the selection changes or clears, a document changes or the viewer is disposed, the outline toggle and the panel header's Hide button hide it and show the native diff, the header's Minimise button leaves the computed rows behind a strip that restores the panel at its previous width, the toggle's default shortcut is unclaimed in Keymap, the toggle is first in the diff toolbar, edits rerun the comparison, and the size limit, time limit and viewer disposal behave as in §9.2. `FrameScrollSyncTest` checks the piecewise mapping before, inside and after the frames and the scaling for different lengths. The test-only engine appears in the dropdown there and can be selected. `OutlineModelTest` checks the outline rows and summary (§10) without the IDE. `ChangesSummaryTest` builds trees with the platform's `TreeModelBuilder` as the Local Changes view does and checks that a modified Java file gets its summary on its own row once it is computed, that added, deleted and unsupported files get none, and that the setting turns the summary text off while declaration rows remain when they are on; that the file's declarations are under it as helper rows drawn as in the outline; that opening one shows the file's diff, through a recording `DiffManager`, whose outline selects it; and that when the platform action cannot be loaded, the row still opens the file's own diff without a selected declaration, with both revisions read off the UI thread (changes-tree/02 R16). `ChangesTreeFilterTest` checks the View Options placement and its defensive toolbar fallback, the two independent toggles, the tree's own filter and hidden counts, a file whose every change is hidden staying in the tree with `· all hidden` and no rows, speed search, the separate settings, that a toggle or filter change does not compare again, and that the first analysis stamp change clears the cache, including when the listener is created after the change; the same row rules are checked on `ChangesTreeFilter` in `OutlineModelTest` ([changes-tree/04](specs/changes-tree/04-hide-and-filter.md)). `ChangesTreeInteractionTest` puts the same model into a `ChangesListView`, installs the tree setup and drives mouse and key events through the platform's tree handlers: the setup reaches both the Local Changes and Commit trees; a double-click and Enter on a summarized file row use the same platform handler and produce the same preview or source choice as a plain row in Local Changes with the diff setting on and off and in the Commit tool window with the preview setting on and off; a single click on either row reaches the preview handler with that row's change; a summarized row falls back to the source when the platform handler declines; Left/Right and the expand control expand and collapse; helper directory and changelist rows still expand; turning summaries off resets the setup; a double-click and Enter on a declaration row open its diff through the selected path, including when the double-click is on the expand control; configuring twice keeps Enter working for ordinary and declaration rows; and an unrecognized platform Enter listener handles leaf declarations without a duplicate while non-leaf declarations are opened directly (changes-tree/02 R9, R14, R15, R17 to R20).

### 12.1 Build and CI

- Build target: IntelliJ IDEA Community 2025.1.3 (`platformVersion` in `gradle.properties`), `since-build` 251, no `until-build`. Bundled `com.intellij.java` and `org.jetbrains.kotlin` plugins, the pinned Scala plugin (`scalaPluginVersion` in `gradle.properties`, currently 2025.1.32, not bundled), IntelliJ Platform test framework with the Java test framework, JUnit 4 and ArchUnit.
- GitHub Actions (`.github/workflows/build.yml`) on every push to `main` and every pull request: `buildPlugin check` (build and all tests), and `verifyPlugin` with Plugin Verifier on IntelliJ IDEA Community 2025.1.3 and the latest IntelliJ IDEA release. The verifier resolves the optional `org.intellij.scala` dependency and installs a compatible Scala plugin for each target; the 2026-11-05 run used `org.intellij.scala:2025.1.32` on 2025.1.3 and `org.intellij.scala:2026.3.26` on 2026.3, and each run's report records the resolved version. Scala PSI use is therefore in the verifier's internal- and experimental-API report, as [relationships/04](specs/relationships/04-rename-grouping.md) R15 requires.

## 13. Prototype spikes

This table is the single list of spikes; the roadmap and the feature specifications refer to it.

Spikes S1 to S3 ran as platform tests and were retired once the tests of §12 covered the same ground: the provider tests cover S2, `OutlineIntegrationTest` covers S1 (local-change, VCS-history and "Compare with" diffs in Java and Kotlin, and CRLF revisions), and S3 remains as the opt-in `LimitsBenchmarkTest` (§9.3). Later spikes follow the same pattern.

| Spike | Question | Done when | Result |
| --- | --- | --- | --- |
| S1 | Option A or B (§9.1)? | Both attach to local-change and VCS-history diffs in Java and Kotlin, or one is shown not to | Done 2026-10-21: Option A, [ADR 0005](adr/0005-outline-attaches-to-the-native-diff-viewer.md); now covered by `OutlineIntegrationTest` |
| S2 | Do PSI files from text (default project, K2 Kotlin) give correct declarations and offsets? | Provider tests pass for both languages on VCS-revision content | Done 2026-10-21: yes, with the Java language level forced (§6.2); now covered by the provider tests |
| S3 | Are the limits in §9.3 realistic? | Analysis time measured on the 20 largest Java/Kotlin files in the IntelliJ Community repository | Done 2026-10-21: yes, kept as they are (§9.3); rerun with `LimitsBenchmarkTest` |
| S4 | After the first release, for [Scala](ROADMAP.md#scala): can the provider choose the Scala 2 or Scala 3 dialect for text-built PSI (from the file's module, `scalaVersion`, or syntax), and does the Scala plugin's PSI give correct declarations and offsets for both? | Provider tests pass for both dialects on VCS-revision content, with a documented fallback when the dialect is unknown | Done 2026-10-22: the file's language alone decides the dialect, and both texts choose it together by parse errors and Scala 3 syntax (§6.2); offsets equal the request text in both dialects; covered by `ScalaDeclarationProviderTest` |
| S5 | After the first release, for the [Changes tree summary](ROADMAP.md#multi-file-summary-in-the-changes-tree): which extension points can add text or child nodes to file entries of the Changes tree? | The hooks of the Local Changes and Commit tree are known, with a choice for level 1 | Done 2026-10-22: `changesViewModifier`, replacing file nodes ([ADR 0006](adr/0006-changes-tree-summary-through-changes-view-modifier.md)); level 2 adds child nodes and `changesViewNodeAction` to open them; now covered by `ChangesSummaryTest` |
| S6 | After the first release, for the [three-way merge outline](THREE-WAY-MERGE.md): does the merge window run `DiffExtension`s, or must a delegating `MergeTool` wrap the platform's text merge viewer? Which titles name the sides in a merge, rebase, cherry-pick and unstash, and does the result pane start as the base? | A placeholder attaches to a Java merge window and a three-sided diff in a platform test, with the choice in ADR 0007 | Done 2026-10-23: the merge window runs no `DiffExtension`, so a `MergeTool` registered first decorates the platform's viewer in place ([ADR 0007](adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)); Git names branches in the content titles of a rebase only; the result starts as the base after the first comparison ([THREE-WAY-MERGE.md §12](THREE-WAY-MERGE.md#12-spike-s6-results)); covered by `S6MergeSpikeTest` |

## 14. Open questions

- **Q1 Default visibility (resolved 2026-10-21).** The outline opens by default, with a setting to turn it off and a toolbar button in every diff to switch between the outline and the plain native diff (§10, §11).
- **Q2 Renamed containers (resolved 2026-10-21, revised 2026-10-22).** 0.1.0 ships rename candidates: a removed and an added declaration with a similar body and name pair as a rename candidate (rule M5), classes included, instead of the literal removed + added the original decision kept. The validation before 0.1.0 showed literal renames as the largest remaining noise ([roadmap](ROADMAP.md#rename-candidates)).
- **Q3 Multi-file overview (resolved 2026-10-21).** Not in the first release, which is per-file only. The next step is a per-file summary inside the IDE's existing Changes/Commit tree ([roadmap](ROADMAP.md#multi-file-summary-in-the-changes-tree)); level 1 is in §9.4.
- **Q4 Plugin ID (resolved 2026-10-21).** The plugin ID, Gradle group and root package are `io.github.agadek.smartdiff`, which also names the extension point namespace (§3.2).
- **Q5 Third-party engines (resolved 2026-10-21).** The `comparisonEngine` extension point stays internal: only SMART Diff and its own optional descriptors register engines, and the engine interface and result model may change freely. Revisit after a second production engine has used the result model without breaking changes.
- **Q6 Engine choice scope (resolved 2026-10-21).** The default engine is chosen per language (§4, §11).
- **Q7 Scala declaration model (resolved 2026-10-22).** For the [Scala provider](ROADMAP.md#scala) (§6.8): `TRAIT`, `GIVEN` and `EXTENSION` are added to `DeclarationKind`, rather than mapping traits to `INTERFACE`, since traits can hold state and code; anonymous givens are named by their type as written; an extension block is an `EXTENSION` container named by its receiver type, with its methods as children; a pattern definition such as `val (a, b) = …` is one property named `(a, b)`; parameter clauses are joined with `;`, and a `using` or `implicit` clause keeps its keyword.
