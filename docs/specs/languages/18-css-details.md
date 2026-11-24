# CSS, SCSS and Less: details

Status: implemented, 2026-11-24

**Feature:** [CSS, SCSS and Less](18-css.md)

## 1. Packaging and IDE versions

- **Plugins:** the CSS PSI comes from the JetBrains CSS plugin, `com.intellij.css`. WebStorm, IntelliJ IDEA Ultimate and the other paid IDEs bundle it, with its dialect plugins `org.jetbrains.plugins.sass` (SCSS and the indented Sass syntax) and `org.jetbrains.plugins.less`. IntelliJ IDEA Community, the main build target, has none of them; the CSS plugin requires `com.intellij.modules.ultimate`.
- **Plugin dependencies:** in IntelliJ IDEA Ultimate 2025.1.3 the CSS plugin requires `com.intellij.modules.xml`, `com.intellij.modules.ultimate` and `com.intellij.platform.images`; the Sass and Less plugins each require `com.intellij.css` and `com.intellij.modules.lang`.
- **Descriptor:** the provider registers in `META-INF/smartdiff-css.xml`, loaded through `<depends optional="true" config-file="smartdiff-css.xml">com.intellij.css</depends>` (R1). There is no descriptor per dialect: the provider uses only the CSS plugin's own PSI interfaces in `com.intellij.psi.css` (`CssRuleset`, `CssAtRule`, `CssBlock`, `CssDeclaration`, `CssSelector`, `CssSelectorList`, `CssRulesetList`, `CssImportList`, `CssKeyframesRule`, `CssStylesheet`), which the Sass and Less PSI builds on, and recognizes the dialect constructs of §4 by their element type's name, such as `SCSS_MIXIN_DECLARATION`: the type's `toString`, without the `PsiElement(…)` that stub element types wrap it in, since `IElementType.getDebugName` is internal API. No class of the Sass or Less plugins is referenced, so the provider loads and reads CSS when either dialect plugin is missing, and a dialect's files are read whenever its plugin registers its language (R2).
- **IDEs without a dialect:** GoLand 2025.1.3 bundles the CSS plugin but neither the Sass nor the Less plugin; there CSS files get the provider and `.scss` and `.less` files are plain text, as they are without the provider. WebStorm, PyCharm and RustRover 2025.1.3 bundle all three.
- **If a dialect renames an element type,** its construct is no longer recognized and becomes content of its container: the outline shows less, never an error. The SCSS and Less golden fixtures catch such a change on the build target.
- **Closed source:** the CSS plugin's sources are not published. Every CSS plugin call is in one class, `CssInterop`, as `GoInterop` does, and a `LinkageError` becomes a plain exception that turns the provider off through `ApiGuard` (`GuardedFeature.CSS_PROVIDER`).
- **Language ids:** `CSS`, `SCSS` and `LESS` (R2). `SASS`, the indented syntax, is out of scope; its files keep the structure-view fallback.

## 2. Build target

CSS follows the paid-IDE build pattern of [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md), as Go and JavaScript do: a Gradle subproject `css/` (`include("css")`) targets **IntelliJ IDEA Ultimate 2025.1.3**, which bundles the three plugins, so there is no Marketplace pin.

- The subproject applies `org.jetbrains.intellij.platform.module`, the Kotlin JVM plugin, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4, like `go/`.
- It adds `com.intellij.css`, `org.jetbrains.plugins.sass` and `org.jetbrains.plugins.less` as `bundledPlugin` and `testBundledPlugin`; the dialect plugins are needed only by the tests, and on the compile classpath only so the tests can load them.
- The root packages it with `pluginModule(runtimeOnly(project(":css")))`, and `:css` adds `compileOnly(project(":"))` for its main source set and `testImplementation(project(":"))` and `testImplementation(testFixtures(project(":")))` for tests.
- `./gradlew buildPlugin check` runs `:css:test`; the test task copies `GOLDEN …` lines into the build log and forwards `-Psmartdiff.updateGolden`.

## 3. Plugin Verifier

- No family is added: WebStorm, PyCharm, RustRover and GoLand bundle the CSS plugin (§1), and the `webstorm` family already verifies the IDE where stylesheets matter most.
- IntelliJ IDEA Community has no CSS plugin. As for JavaScript and Go, the Linux IDE builds that CI uses report the `com.intellij.psi.css` classes that `CssInterop` references as unresolved, although `smartdiff-css.xml` never loads there. CSS therefore adds exactly two problem shapes to `gradle/verifier/ignored-problems-idea.txt`: the package `com.intellij.psi.css` (and its subpackages) not found, and access to an unresolved `com.intellij.psi.css` class. No other family ignores anything for CSS, and no Sass or Less package is ignored, since no class uses them. `verifierFamiliesTest` checks that every `idea` line names only the JavaScript packages, `com.goide` or `com.intellij.psi.css`, and that `idea` ignores the CSS PSI.
- The runs of 2026-11-24 resolved IC-251.26927.53 and IU-263.7307.353, PY-251.26927.74 and PY-263.7307.351, RR-251.25410.170 and RR-263.7307.349, WS-251.26927.40 and WS-263.7307.348, and GO-251.26927.50 and GO-263.7307.354, every target `Compatible` with no internal-API usage. The only new experimental-API report is `PsiExternalReferenceHost`, referenced by the CSS plugin's own `CssSelector`; it is not a use by a SMART Diff class, so the inventory does not list it ([PLATFORM-API.md, Not listed](../../PLATFORM-API.md#not-listed)).
- Every CSS PSI use the verifier reports as internal or experimental is listed in [PLATFORM-API.md](../../PLATFORM-API.md) and reviewed there.

## 4. Declarations

The provider reads the stylesheet's children, the rule set list and the import list it holds (a `@layer` rule written before the first rule set is in the import list), then each declaration's block. A `@keyframes` rule holds its keyframe rule sets directly, without a block.

| PSI element | Kind | Name | Signature | Members |
| --- | --- | --- | --- | --- |
| `CssRuleset`, or a Less element that only wraps one (every Less rule set is a `LESS_PRIMITIVE_MIXIN`) | RULE | its selectors (§5) | — | its block's declarations |
| `CssAtRule` with a `CssBlock`, or a `CssKeyframesRule` | AT_RULE | its keyword and prelude (§5) | — | its block's declarations, or a `@keyframes` rule's keyframe rule sets |
| `CssDeclaration` whose property is custom (`--name`) | VARIABLE | the property name | — | — |
| `SCSS_VARIABLE_DECLARATION` | VARIABLE | its `$name` | — | — |
| `SCSS_MIXIN_DECLARATION` | MIXIN | the identifier after `@mixin` | parameter names (§6) | — |
| `SCSS_FUNCTION_DECLARATION` | FUNCTION | the identifier after `@function` | parameter names (§6) | — |
| `LESS_VARIABLE_DECLARATION`, detached rule sets included | VARIABLE | its `@name` | — | — |
| `LESS_MIXIN` (a mixin written with parentheses) | MIXIN | its `LESS_MIXIN_NAME`, such as `.border` | parameter names (§6) | — |

- **New kinds:** `RULE` (`rule`), `AT_RULE` (`at-rule`) and `MIXIN` (`mixin`), added at the end of `DeclarationKind`. No existing kind reads right: `SECTION` and `TAG` are data-file words, and a rule set is neither a class nor a function. `DeclarationKind.label()` spells `AT_RULE` as `at-rule`. Custom properties and SCSS and Less variables reuse `VARIABLE`, and SCSS functions `FUNCTION`.
- **Kind families (rule M4b):** the matcher gains a family `{RULE, MIXIN}`, so a Less rule set turned into a mixin of the same name, or back, is a kind change (R17); a container path names that family by `RULE`. `AT_RULE` is in no family. `MIXIN` is not in the function family, so an SCSS mixin turned into a function is removed and added.
- **Not declarations (R11):** ordinary properties; `@import`, `@use`, `@forward`, `@charset` and `@namespace`; at-rules without a block, such as `@layer a, b;`; `@include`, Less mixin calls and `@extend`; SCSS nested properties (`font: { … }`); and SCSS control directives (`@if`, `@each`, `@for`, `@while`), which are elements whose type starts with `SCSS_` and are not a recognized construct. They are content of their container, or of the file region at top level.
- **Mixin and function bodies (R12):** a mixin or function has no members; everything in its body is its content.
- **Repeated keys:** two rule sets with the same selectors in one container, such as two `@font-face` rules, share a key and follow the matcher's rules for repeated keys.

## 5. Names

- **Selectors (R4):** each selector of the list is written from its parts: a whitespace run that is a direct child of the `CssSelector`, the descendant combinator, is one space; `>`, `+`, `~` and `||` have one space on each side; selectors are joined with `, `. Inside a simple selector, such as `:not( .a,.b )` or `[ type = "t" i ]`, whitespace is dropped, except one space between two words; `, ` follows a comma and nothing precedes `)`, `]` or `,`. Comments are left out. So `.a  .b,.c>.d` and `.a .b, .c > .d` share the name `.a .b, .c > .d`, and a reformatted selector list stays matched (R14). A nested SCSS or Less rule set is named as written, `&:hover` or `> .body`, and its path gives its context.
- **At-rules (R5):** the at-rule's text from its keyword to its block, with each whitespace run as one space, none after `(` or `[` or before `)`, `]` and `,`, `, ` after a comma, and `: ` after a `:` inside parentheses: `@media screen and (max-width:600px)` is named `@media screen and (max-width: 600px)`. A changed prelude renames the at-rule, which becomes a rename candidate when its content stays similar, or removed and added otherwise.
- **Less mixins:** the mixin name as written, `.border` or `#ns`, without its parameters.

## 6. Signatures

- A mixin's or SCSS function's signature is its parameter names in order: the variable of each parameter, `$color` or `@width`, without its default value; a rest parameter keeps `...` (`$args...`). The key joins them with `,` and the display with `, ` (R10).
- A Less parameter without a variable, a pattern such as `dark` in `.theme(dark; @color)`, is written as its tokens, so Less mixins overloaded by pattern or by arity have different signatures.
- A mixin without parentheses, `@mixin reset { … }`, has the empty signature, as `@mixin reset() { … }` does.
- A changed default value is a content change, not a signature change; an added, removed or renamed parameter is a signature change.

## 7. Ranges and comments

- A comment is a sibling before its declaration, in the rule set list or the block: `/* … */` in all three dialects, and `//` in SCSS and Less.
- The provider extends a declaration's range back over the comments directly above it (R13): the run of comments that ends on the line before the declaration, with no blank line between them and the declaration and none inside the run. A comment after a blank line, such as a file header, and a comment on the same line as the previous declaration stay outside.
- A variable's range, custom properties included, also takes the `;` that ends it on its line, so adding or removing one leaves no `;` behind in its container.

## 8. Whitespace policy

- CSS whitespace is insignificant except where it separates tokens that would otherwise read differently. CSS, SCSS and Less trees use `INSIGNIFICANT`, with significant ranges returned through `significantWhitespace` (technical specification §6.10), whose whitespace is compared exactly (R14, R15):
  - **Descendant combinators:** a whitespace run that is a direct child of a `CssSelector` and is not next to an explicit combinator (`>`, `+`, `~`, `||`). `.nav .item` and `.nav.item` select different elements, and the name of §5 already tells them apart; the significant range makes `.nav .item` and `.nav\n  .item` a change too, which is strict but never hides one.
  - **Operators:** outside a selector, a whitespace run next to a `+` or `-` token. `calc()` needs the spaces around them, and in SCSS and Less `$a - $b` is a subtraction while `$a -$b` is a list.
- Everything else is formatting: indentation, line breaks, spaces after `:` and `,`, spaces around `{` and explicit combinators. A minified file expanded to one property per line is formatting-only.
- Strings and comments are single tokens, so whitespace inside them is compared as written.
- The leading whitespace of the first declaration in a file counts as for the other languages, so moving the first rule set of a file that starts with it also marks the moved declarations formatting-only.
- `OutlineController.whitespacePolicy` maps `CSS`, `SCSS` and `LESS` to `INSIGNIFICANT`.

## 9. Parsing and parse errors

- `PsiFileFactory.createFileFromText("file.<css|scss|less>", language, text, false, false)`, inside the engine's read action, with the language found by `Language.findLanguageByID` from the request's language id, or from the file name's extension when the request names none.
- The stylesheet is a lazily parsed element; the provider walks it by first child and next sibling, which parses it, rather than with `PsiTreeUtil.processElements`, which does not enter it.
- Offsets are checked on LF and on CRLF content, as for the other languages.
- Parse errors follow technical specification §6.7. The CSS parser recovers from most errors inside a block; `PsiErrorElement`s mark the rule set that holds them. An empty side has no error.

## 10. Relationships, merges and the Changes tree

- **Rename candidates, kind changes, extraction and inlining (R16, R17, R18):** they work on the declaration model. A renamed selector is a rename candidate when the rule set's content stays similar.
- **Moves inside declarations:** the provider marks `CssBlock`, and an SCSS function's body, as statement blocks (`isStatementBlock`), so properties moved inside a rule set, a mixin or a function are code moves, under the 12-token minimum of [relationships/08](../relationships/08-moves-inside-declarations.md).
- **Rename grouping:** out of scope; there is no reference resolver for stylesheets, so renamed selectors show no usage count.
- **Merges and Changes tree (R19):** both use the engine's result and need no CSS code.
- **Benchmark and corpus validation:** skipped in this phase (out of scope). `CorpusValidationTest` accepts `.css`, `.scss` and `.less` files, so a later corpus run needs no code change.

## 11. Tests

- **`CssDeclarationProviderTest`** (`css/src/test/kotlin/.../psi/css/`): tests named after R1 to R19, each naming its requirement with `// languages/18-css Rn`, with offset checks that every declaration's range and tokens match the request text, on LF and CRLF content, for each dialect. R1 also checks that the optional descriptor registers the provider when the CSS plugin is present, and R2 that the Sass and Less plugins are loaded.
- **Golden fixtures** in `src/test/testData/css/`, `scss/` and `less/`, run by `CssGoldenFixturesTest`, `ScssGoldenFixturesTest` and `LessGoldenFixturesTest` through the shared golden harness (R14, R15, R20):
  - CSS: identical, empty files, new file, formatting-only (a minified file), comment-only, rule added, removed and changed, selector rename (R16), a rule changed inside `@media`, a media query changed, a moved rule, a custom property changed and added (R7), a descendant combinator removed (R15), a child combinator respaced (R14), a keyframe step changed and added, a parse error, properties moved inside a rule (§10), a reindented file, a string changed, `@layer` and `@supports` rules (R5) and an import added.
  - SCSS: identical, empty files, formatting-only, comment-only, a nested rule changed and added (R6), a mixin changed and its parameters changed (R10), a variable changed, a function changed, a placeholder changed (R8), a control directive changed (R11) and a parse error.
  - Less: identical, empty files, formatting-only, comment-only, a mixin changed and its parameters changed, a variable changed, a nested rule changed, a rule set turned into a mixin (R17), a guard changed and a parse error.
- **Merge fixtures:** `CssMergeFixturesTest` (`css/src/test/kotlin/.../merge/`) runs the cases in `src/test/testData/css/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11 plus a media query changed on one side and its nested rule on the other and custom properties changed on both sides, and the cases in `scss/merge/` and `less/merge/` (R19, R20).
- **Outline:** `OutlineModelTest` checks the whitespace policy of the three language ids and the labels `rule`, `at-rule` and `mixin`.
- **Architecture:** `CssArchitectureTest` checks that only `io.github.agadek.smartdiff.engine.declarations.psi.css..` uses `com.intellij.psi.css..`, that only `CssInterop` does inside it, and that no class uses the Sass or Less plugin packages; the root inventory test lists that row as checked by the `css` subproject.

## 12. Open questions

- **Rules inside control directives:** whether a rule set inside `@if` or `@each` should be a declaration, under a container named by the directive. The proposal keeps them content, since the directive's condition, not a name, identifies them.
- **The indented Sass syntax:** whether `.sass` files should be read through the same element types; their layout is significant, so they need their own whitespace policy.
