# PHP: details

Status: implemented, 2026-11-24

**Feature:** [PHP](19-php.md)

## 1. Packaging and IDE versions

- **Plugin:** the PHP PSI comes from the JetBrains PHP plugin, `com.jetbrains.php` (Marketplace plugin 6610). PhpStorm bundles it; IntelliJ IDEA Ultimate installs it from the Marketplace. IntelliJ IDEA Community, the main build target, cannot use it, since the plugin requires the `com.intellij.modules.ultimate` module.
- **Marketplace facts (spike S15, 2026-11-24):** plugin 6610 is `FREE` (`pricingModel`). The update for IntelliJ IDEA Ultimate 2025.1.3 (`IU-251.26927.53`) is `251.26927.70` (since 251.26927, until 251.*); the later 251 updates, `251.28293.22` to `251.29188.37`, need newer builds.
- **Plugin dependencies:** the plugin's `META-INF/plugin.xml` requires `com.intellij.modules.xml`, `com.intellij.modules.ultimate`, `com.intellij.modules.php-capable` and `com.intellij.modules.json`. Its other `<depends>` entries (diagrams, coverage, deployment, IntelliLang, live edit, REST client, copyright, duplicates, performance testing, terminal) are optional, as is its one content module (`intellij.symfony`), and the test sandbox loads the plugin without them: S15 found only `com.intellij`, `com.intellij.modules.json`, `com.jetbrains.php` and this plugin loaded.
- **Descriptor:** the provider registers in `META-INF/smartdiff-php.xml`, loaded through `<depends optional="true" config-file="smartdiff-php.xml">com.jetbrains.php</depends>` (R1).
- **Closed source:** the PHP plugin's sources are not published. S15 confirmed the PSI interfaces of §4 in `com.jetbrains.php.lang.psi.elements` on `251.26927.70`; the verifier report shows which of them it treats as internal or experimental API, and the provider keeps every PHP plugin call in one class, `PhpInterop`, as `GoInterop` does.
- **Language id:** `PHP` in upper case (S15: `Language.findLanguageByID("PHP")` is `com.jetbrains.php.lang.PhpLanguage`; `"php"` finds nothing), with the PHP file type for `.php` (R2).
- **IDE versions:** no change to P6. PHP support needs a PHP plugin release for that IDE, which in PhpStorm is always the bundled one.

## 2. Build target

PHP follows the paid-IDE build pattern of [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md) (its amendment for phase B0 names one IntelliJ IDEA Ultimate subproject per language), as Go does: a Gradle subproject `php/` (`include("php")`) targets **IntelliJ IDEA Ultimate 2025.1.3** with the pinned Marketplace plugin **`com.jetbrains.php:251.26927.70`**, and the main build stays on Community.

- The subproject applies `org.jetbrains.intellij.platform.module`, the Kotlin JVM plugin, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4, like `go/`.
- It adds `com.intellij.modules.json` as `bundledPlugin` and `testBundledPlugin`, since the PHP plugin requires it and the test platform does not add it by itself. `com.intellij.modules.xml` is a platform module and needs no entry. S15 needed no other plugin and no test-only stand-in class.
- As for Go, the root packages it with `pluginModule(runtimeOnly(project(":php")))`, and `:php` adds `compileOnly(project(":"))` for its main source set and `testImplementation(project(":"))` and `testImplementation(testFixtures(project(":")))` for tests, so there is no task cycle.
- `./gradlew buildPlugin check` runs `:php:test`; the test task copies `S15 …`, `GOLDEN …` and `PHP VALIDATION …` lines into the build log, and forwards `-Psmartdiff.updateGolden` and `-Psmartdiff.validation.php.corpus`.

## 3. Plugin Verifier

- `verifyPlugin` gains the `phpstorm` family: PhpStorm 2025.1.3 (`IntelliJPlatformType.PhpStorm`) and the latest PhpStorm release. It is in the default set and is one more job of the `verify` matrix in `.github/workflows/build.yml` and of the release workflow's loop.
- On IntelliJ IDEA Community the optional dependency stays unresolved. As for Go ([languages/17](17-go-details.md#3-plugin-verifier)), the Linux IDE builds that CI uses report the PSI of a descriptor that never loads there as unresolved, so PHP adds exactly two problem shapes to `gradle/verifier/ignored-problems-idea.txt`: the package `com.jetbrains.php` (and its subpackages) not found, and access to an unresolved `com.jetbrains.php` class. Nothing else is ignored for PHP, no other family ignores anything for PHP, and the verifier still lists the ignored problems in its report.
- **PhpStorm has no Java plugin.** As in WebStorm, `com.intellij.java` is not bundled in PhpStorm and cannot be installed (the 2025.1.3 report lists it as an unresolved optional dependency), so `smartdiff-java.xml` never loads there. WebStorm's Linux 2025.1 build reported the Java PSI that the Java provider references as unresolved, so `phpstorm` gets the same file as `webstorm`, [gradle/verifier/ignored-problems-webstorm.txt](../../../gradle/verifier/ignored-problems-webstorm.txt), with the Java classes by name and nothing for PHP: PhpStorm keeps every PHP and JavaScript check. A run that asks for both families passes the file once.
- `verifierFamiliesTest` checks that every `idea` line names only the JavaScript packages, `com.goide` or `com.jetbrains.php`, that `idea` ignores the PHP PSI, and that `phpstorm` gets only the Java file. CI and the release workflow run one family per `verifyPlugin` invocation.
- Every PHP PSI use the verifier reports as internal or experimental is listed in [PLATFORM-API.md](../../PLATFORM-API.md) and reviewed there. A `NoClassDefFoundError` or `NoSuchMethodError` from the PHP plugin turns the provider off through `ApiGuard` (`GuardedFeature.PHP_PROVIDER`) and shows the native diff, never an exception in the diff window ([product scope](../README.md#product-scope) P5).
- **First run (2026-11-24, macOS IDE builds, one family per invocation):** PhpStorm `PS-251.26927.60` and `PS-263.7307.352` resolved, both `Compatible`, with 12 and 13 experimental-API usages, none of them in the PHP provider, and the PHP plugin resolved as bundled on both. The verifier reports no internal, experimental or deprecated `com.jetbrains.php` use, so [PLATFORM-API.md](../../PLATFORM-API.md) gains only the `com.jetbrains.php..` package row, and `checkPlatformApiInventory` passed. The other families stayed `Compatible` ([VALIDATION.md](../../VALIDATION.md)).

## 4. Declarations

S15 confirmed these interfaces, all in `com.jetbrains.php.lang.psi.elements` with `Impl` classes in `com.jetbrains.php.lang.psi.elements.impl`, and that every element's `text` equals the request text sliced by its range. A text-built file is a `com.jetbrains.php.lang.psi.PhpFileImpl` whose view provider holds the `PHP` and `HTML` trees; the provider reads only the `PHP` tree. The S15 sample, with PHP 8 attributes, enums, promoted constructor properties, `readonly`, union and nullable types, parsed with no `PsiErrorElement`.

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `PhpNamespace` with a name | NAMESPACE | its name without the leading `\`, `App\Model` | — |
| `PhpClass` | CLASS, INTERFACE, TRAIT or ENUM, by `isInterface()`, `isTrait()` and `isEnum()` | `name` | — |
| `Function` that is not a closure | FUNCTION | `name` | parameters (§5) |
| `Method` named `__construct` (any case) | CONSTRUCTOR | `__construct` | parameters (§5) |
| any other `Method` | METHOD | `name` | parameters (§5) |
| `Field` with `isConstant()` | CONSTANT | `name` | — |
| any other `Field` | PROPERTY | `name`, without `$` | — |
| `PhpEnumCase` | ENUM_CONSTANT | `name` | — |
| `Constant` of a top-level `const` statement or `define()` call | CONSTANT | `name` | — |

- **No new kind:** every PHP declaration takes an existing `DeclarationKind`, so the matcher and the outline need no change. The M4b type family already holds `CLASS`, `TRAIT`, `INTERFACE` and `ENUM`, so a class turned into a trait or an interface of the same name is a kind change (R12).
- **Top level (R3):** a file's PSI is a `PhpFile` holding one group statement; the provider reads that statement's children. Classes, interfaces, traits and enums are `PhpClass`, functions are `Function`, and a `const A = 1, B = 2;` statement or a `define('A', 1);` call holds `Constant` elements. Abstract, final and readonly classes are classes.
- **Namespaces (R4):** a `PhpNamespace` with a name is a container, in both forms. In the statement form (`namespace App\Model;`) S15 showed that the namespace's range runs to the next namespace or the end of the file and holds a group statement with everything after the clause, so its classes, functions and constants are its members. In the braced form (`namespace A { … }`) its group statement is the block. A braced namespace without a name (`namespace { … }`) is no declaration: its declarations are top-level and its braces belong to the file region. A namespace's `use` imports and other statements are its own tokens.
- **Members (R5):** a class's body holds `Method`s, `PhpEnumCase`s, and field lists (`PhpClassFieldsList`) that hold the `Field`s of one property or class constant statement. Interface methods have no body. `use` statements in a class body (trait uses) are not declarations and belong to the class's own tokens, so adding a trait changes the class.
- **Written together (R6):** each `Field` of `public int $width = 0, $height = 0;` and each `Constant` of `const A = 1, B = 2;` is its own declaration, as each field of a Java `int a, b;` is. The tokens before the first part (comments, attributes, modifiers, type, the `const` keyword) belong to the first part, the `,` after a part belongs to that part, and the closing `;` to the last. Inserting a part changes no other part; appending one changes the former last part, whose `;` becomes a `,`.
- **Not declarations (R7):** the provider never looks inside a function or method body, so nested functions, closures, arrow functions, anonymous classes (`new class { … }`), local `const` statements and conditional declarations are content of their function or of the code around them. `use` imports, `declare(strict_types=1)` and other top-level statements belong to the file region or their namespace. Inline HTML outside `<?php … ?>` is template text in the PHP tree and belongs to the file region.
- **Promoted constructor properties (R9):** `__construct(private readonly int $y)` declares a property, but it is written as a parameter, inside the constructor's range; a class member there would sit inside its sibling. It is therefore part of the constructor: its parameter type and name are in the constructor's signature, and its modifiers are the constructor's own tokens, so changing `private` to `public` changes the constructor without changing its signature.
- **Repeated keys:** methods of one name in different classes have different paths; a `__construct` is unique in its class. PHP has no overloading.

## 5. Signatures

- PHP 8 has named arguments (`f(name: 'x')`), so a parameter's name is part of how a function is called: renaming one can break a caller. A signature is therefore each parameter's type, its `&` (by reference) and `...` (variadic) markers and its name, in order (R9). The key form has no whitespace (`int$x,?Point$p,string...$rest`); the display form has one space after the type and `, ` between parameters (`int $x, ?Point $p, string ...$rest`), as PHP code is written. A parameter without a type is its markers and name: `$untyped`, `&$list`.
- A type is the parameter's `PhpTypeDeclaration` as written, without whitespace or comments: `?Point`, `int|string`, `A&B`.
- Default values, parameter attributes (`#[SensitiveParameter]`), promoted-property modifiers (`private readonly`) and the return type are left out, as the other languages leave out return types. Renaming a parameter, adding `&` or `...`, or changing a type changes the signature; changing a default value or the return type changes only the content.
- Functions, methods and constructors have a signature even with no parameters (the empty one); other kinds have none.

## 6. Names

- A class, interface, trait or enum is named by its short name; its namespace is its container, so the outline reads `namespace App\Model › class Point`.
- A property is named without its `$`, as PhpStorm's structure view and `$this->x` name it; a parameter keeps its `$` in signatures, since that is how a signature is read.
- A declaration whose name the parser did not read (a parse error) is not a declaration; its source belongs to its container.

## 7. Ranges, comments and attributes

- S15 showed that a PHPDoc comment (`PhpDocComment`, a `PsiComment`) is a sibling before the declaration, outside its range, and that attributes (`PhpAttributesList`, `#[…]`) are the first child of the class, method or field list they belong to, inside its range.
- The provider therefore extends a declaration's range back over the comments directly above it (R8): the run of PHPDoc, `/* */`, `//` and `#` comments that ends on the line before the declaration, with no blank line between them and the declaration and none inside the run. A comment after a blank line and a comment on the same line as the previous code (`function a() {} // after a`) stay in the container's own tokens.
- The same rule applies inside class bodies and namespaces, to the comments above a method, property statement, constant statement or enum case. For a statement with several parts the comments go to the first part.
- The range is returned through `DeclarationInfo.range`, so the outline frames the doc comment and the attributes with their declaration, and adding a doc comment or an attribute changes the declaration.

## 8. Whitespace policy

- PHP statements end with `;` or a block, and a line break never ends one, so PHP trees use `INSIGNIFICANT`, as Java does (R10).
- String literals, the text of heredocs and nowdocs, and inline HTML are lexer tokens of their own, not whitespace tokens, so a change in their spacing, a heredoc line's indentation included, is a change already (R11). The provider declares no significant whitespace ranges.
- `OutlineController.whitespacePolicy` maps `PHP` to `INSIGNIFICANT`.

## 9. Parsing and parse errors

- `PsiFileFactory.createFileFromText("file.php", PhpLanguage, text, false, false)`, inside the engine's read action. A text-built file has no project PHP language level or interpreter; S15 found that the parser accepts the sample's PHP 8.1 syntax on it (attributes, enums, `readonly` promoted properties, nullable and union types) with no `PsiErrorElement`.
- Offsets are checked on LF and on CRLF content, as for the other languages.
- Parse errors follow technical specification §6.7. An empty side, as of a new or deleted file, parses with no error, so the file is `ANALYZED` with every declaration added or removed. A file with no `<?php` tag is all inline HTML and has no declarations.

## 10. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates, kind changes and moves inside declarations (R12):** they work on the declaration model. For [moves inside declarations](../relationships/08-moves-inside-declarations.md) the provider marks a `GroupStatement` as a statement block (`isStatementBlock`) unless it is the file's or a namespace's, so the statements of a function or method body, and of the blocks nested in it, can be moved, while a namespace's top-level statements cannot.
- **Rename grouping:** out of scope. S15 showed that in a text-built file, without an interpreter or include path, the reference `VERSION` in `echo VERSION` resolves to its `ConstantImpl`, the type `Point` in `?Point $p` and the trait `Movable` in `use Movable` to their `PhpClassImpl`, so a `PhpReferenceResolver` can follow as its own change; until it does, PHP renames show no usage count.
- **Merges and Changes tree (R13):** both use the engine's result and need no PHP code.
- **Benchmark:** the relationship benchmark gains the `php` corpus, with cases in `src/test/testData/php/relationships/` listed in `src/test/testData/relationships-corpus-php.txt`, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md), and run by `PhpRelationshipBenchmarkTest` from `:php:test`, under the `no-new-wrong-pairs` gate.

## 11. Tests

- **`PhpS15SpikeTest`** (`php/src/test/kotlin/.../psi/php/`): the spike that answered §1, §4, §7 and §10, printing `S15 …` lines and writing the PSI trees of a statement-namespace sample, a braced-namespace sample and an inline-HTML sample to `build/php-spike/`. It stays until the provider tests below cover the same ground.
- **`PhpDeclarationProviderTest`** (`php/src/test/kotlin/.../psi/php/`): tests named after R1 to R13, each naming its requirement with `// languages/19-php Rn`, with offset checks that every declaration's range and tokens match the request text, on LF and CRLF content. R1 also checks that the optional descriptor registers the provider when the PHP plugin is present.
- **Golden fixtures** in `src/test/testData/php/`, run by `PhpGoldenFixturesTest` through the shared golden harness: the standard set of technical specification §12 (overloads become methods of one name in two classes, since PHP has no overloading), plus PHP cases: statement and braced namespaces and a renamed namespace (R4), a property added to a `public $a, $b` statement and constants added (R6), an enum case added, a method added to an interface and a trait use added (R5), a class turned into a trait (R12), a closure changed inside a function and inline HTML changed (R7), a doc comment and an attribute added (R8), a parameter renamed, `&` and `...` added, a return type changed and a promoted property changed (R9), a line break added inside a call (R10) and whitespace changed inside a heredoc and a nowdoc (R11).
- **Merge fixtures:** `PhpMergeFixturesTest` (`php/src/test/kotlin/.../merge/`) runs the cases in `src/test/testData/php/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11 and four PHP cases: a doc comment against a body change, an attribute against a body change, a parameter renamed against a body change, and enum cases added on both sides (R13, R14).
- **Relationship benchmark:** `PhpRelationshipBenchmarkTest` (`php/src/test/kotlin/.../relationships/`), as §10 says (R12, R14).
- **Corpus validation:** `PhpCorpusValidationTest` (`php/src/test/kotlin/.../psi/php/`) runs with `-Psmartdiff.validation.php.corpus=<list>`; `CorpusValidationTest` and the benchmark's real-commit lines accept `.php` files. The first run uses recent commits from two active open-source PHP projects and records the results in [VALIDATION.md](../../VALIDATION.md).
- **Architecture:** `PhpArchitectureTest` checks that only `io.github.agadek.smartdiff.engine.declarations.psi.php..` uses `com.jetbrains.php..`, and the root inventory test lists that row as checked by the `php` subproject.

## 12. Open questions

- **Promoted properties as members:** whether a promoted constructor property should also be listed as a property of its class, for example as a member of the constructor or as a second, virtual node. The proposal keeps it part of the constructor (§4), since a class member inside the constructor's range would overlap its sibling.
- **Global code:** whether top-level statements of a script (`echo`, `require`, `if` blocks) should become moves inside the file region, as [relationships/08](../relationships/08-moves-inside-declarations.md) does for function bodies. They stay content of the file region until the corpus validation shows that script files matter.
