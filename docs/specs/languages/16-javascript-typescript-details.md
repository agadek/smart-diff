# JavaScript and TypeScript: details

Status: implemented, 2026-11-24

**Feature:** [JavaScript and TypeScript](16-javascript-typescript.md)

This is phase B1 of the [format coverage plan](../../FORMAT-COVERAGE.md#5-phases), with phase B0, the build pattern for paid IDEs, in the same change.

## 1. Packaging and IDE versions

- **Plugin:** the JavaScript and TypeScript PSI comes from the JetBrains JavaScript plugin, plugin id `JavaScript`. WebStorm, IntelliJ IDEA Ultimate, PhpStorm, PyCharm Professional, GoLand, RubyMine, Rider and CLion bundle it. IntelliJ IDEA Community, the main build target, does not have it, and it cannot be installed there.
- **Descriptor:** the provider registers in `META-INF/smartdiff-javascript.xml`, loaded through `<depends optional="true" config-file="smartdiff-javascript.xml">JavaScript</depends>` (R1).
- **Closed source:** the plugin's sources are not published. The PSI interfaces the provider uses are in `com.intellij.lang.javascript.psi`, `com.intellij.lang.javascript.psi.ecma6`, `com.intellij.lang.javascript.psi.ecmal4` (`JSClass`) and `com.intellij.lang.ecmascript6.psi`; the provider keeps every call to them in one class, `JavaScriptInterop`, as `RustInterop` does for Rust.
- **Language ids (R2):** `JavaScript`, `ECMAScript 6`, `JSX Harmony`, `Flow JS`, `TypeScript` and `TypeScript JSX`. The provider supports all six. The file types of the plugin map the extensions of R2 to these languages. In 2025.1 `JSX Harmony` is not a registered language, and the editor opens `.js`, `.mjs`, `.cjs` and `.jsx` files as `ECMAScript 6`, which reads JSX; the id stays supported for IDEs that report it. The provider picks the dialect from the diff's file name (§7).
- **IDE versions:** no change to the lowest supported IDE. The JavaScript plugin is always bundled, so its version is the IDE's.

## 2. Build target (B0)

This follows [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md), whose amendment of 2026-11-24 makes its option B the pattern for every language whose plugin is paid or tied to another IDE.

- A Gradle subproject `javascript/`, included in `settings.gradle.kts`, compiles and tests the provider against **IntelliJ IDEA Ultimate 2025.1.3** with the **bundled** `JavaScript` plugin. There is no Marketplace pin, since the plugin ships with Ultimate.
- The build file is the `rust/` one with the Rust lines replaced: `intellijIdeaUltimate("2025.1.3")`, `bundledPlugin("JavaScript")` and `testBundledPlugin("JavaScript")`, plus any bundled plugin the JavaScript plugin's descriptor requires that the test sandbox does not add by itself. Which ones those are is checked when the subproject is first built and recorded in §7.
- The root adds `pluginModule(runtimeOnly(project(":javascript")))`; `:javascript` adds `compileOnly(project(":"))`, `testImplementation(project(":"))` and `testImplementation(testFixtures(project(":")))`, as `:rust` does.
- `./gradlew buildPlugin check` runs `:javascript:test`.

## 3. Plugin Verifier

- Add WebStorm 2025.1.3 and the latest WebStorm release (`IntelliJPlatformType.WebStorm`) as verifier targets, in a `webstorm` family of the verifier matrix next to `idea`, `pycharm` and `rustrover`, part of the default set and of the build workflow's matrix.
- On IntelliJ IDEA Community the optional dependency stays unresolved, which the verifier reports as a missing optional dependency, not a failure.
- **Absent bundled-only dependencies.** The JavaScript plugin is not in IntelliJ IDEA Community and the Java plugin is not in WebStorm, and neither can be installed from the Marketplace, so the optional descriptor that uses each one (`smartdiff-javascript.xml`, `smartdiff-java.xml`) never loads in that IDE. On the Linux IDE builds that CI verifies, Plugin Verifier 1.411 still reports that descriptor's PSI as unresolved classes and packages; on the macOS builds it reports the same dependency as missing and no problem. These reports are false positives, and each family ignores only its own through an ignored-problems file, which `pluginVerification.ignoredProblemsFile` passes for the requested families:
  - `idea` ([gradle/verifier/ignored-problems-idea.txt](../../../gradle/verifier/ignored-problems-idea.txt)): unresolved packages and classes under `com.intellij.lang.javascript`, `com.intellij.lang.ecmascript6` and `com.intellij.lang.typescript`.
  - `webstorm` ([gradle/verifier/ignored-problems-webstorm.txt](../../../gradle/verifier/ignored-problems-webstorm.txt)): the Java plugin classes that `JavaDeclarationProvider` and `JavaReferenceResolver` reference, each by name, and the `com.intellij.lang.java` and `com.intellij.pom.java` packages. There is no `com.intellij.psi` prefix, so a new Java PSI class fails the WebStorm targets until it is reviewed and added.
  - The verifier matches a pattern against a problem's short description, which does not name the referencing class, so a pattern cannot be limited to the Java or JavaScript adapter; the per-family choice is the limit. The other families ignore nothing, WebStorm keeps every JavaScript check and IntelliJ IDEA every Java check. CI and the release workflow run one family per invocation; a local run over several families gets all the requested families' files.
  - Every ignored problem stays in the report, in `ignored-problems.txt` next to the verdict. `verifierFamiliesTest` checks which files each family gets and that the files keep to these limits.
- JavaScript PSI use that the verifier reports as internal or experimental is reviewed and listed in [PLATFORM-API.md](../../PLATFORM-API.md) with the `JAVASCRIPT_PROVIDER` guarded feature. A `LinkageError` from the plugin becomes a `FAILED` result with the native diff shown ([platform/01](../platform/01-platform-api-stability.md) R11).
- **First run (2026-11-24):** `verifyPlugin -Psmartdiff.verifier.ides=idea,pycharm,rustrover,webstorm` reported every target `Compatible`: WebStorm WS-251.26927.40 and WS-263.7307.202, IntelliJ IDEA IC-251.26927.53 and IU-263.7307.200, PyCharm and RustRover 2025.1 and 2026.3. It reported no internal or experimental use of the JavaScript plugin's API, so the `PLATFORM-API.md` row lists only the package for the architecture test, and `checkPlatformApiInventory` passed. That run used the macOS IDE builds. CI on Linux then reported IC-251.26927.53 with 2 problems (the unresolved `com.intellij.lang.javascript.psi` and `com.intellij.lang.ecmascript6` packages) and WS-251.26927.40 with 38 (the unresolved Java plugin PSI), all from the absent bundled-only dependencies above. The 2026.3 targets were `Compatible`.

## 4. Declarations

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `JSFunction` that is a function declaration, not inside a function body | FUNCTION | `name`, or `default` (R7) | §5 |
| `JSClass` declaration | CLASS | `name`, or `default` (R7) | — |
| `JSFunction` in a class | METHOD; CONSTRUCTOR for `constructor` | `name`; a getter `get name` and a setter `set name` | §5 |
| A class field (`JSField` in a class) | FIELD | `name`; a private name keeps its `#` | — |
| `JSVariable` of a `JSVarStatement` at file level or in a namespace | VARIABLE; FUNCTION when its initializer is an arrow function or a function expression (R4) | `name` | §5 for a function, none otherwise |
| `TypeScriptInterface` | INTERFACE | `name` | — |
| `TypeScriptPropertySignature` in an interface | PROPERTY | `name` | — |
| `TypeScriptFunctionSignature` in an interface | METHOD | `name` | §5 |
| `TypeScriptTypeAlias` | TYPE_ALIAS | `name` | — |
| `TypeScriptEnum` | ENUM | `name` | — |
| `TypeScriptEnumField` | ENUM_CONSTANT | `name` | — |
| `TypeScriptModule` written `namespace` or `module` with a name | NAMESPACE | the dotted name, `A.B` | — |
| `TypeScriptModule` written `declare module "x"` or `declare global` | MODULE | the quoted name as written, or `global` | — |

- **Namespace kind:** the provider reuses `NAMESPACE`, labelled `namespace`, which the [Tree-sitter engine](../engines/05-tree-sitter-details.md) added to `DeclarationKind` for C# and C++; it adds no kind of its own. A TypeScript namespace is not a module, and `module` would read wrongly for the common `namespace` form. The matcher's type kinds for rule M4b gain no new kind: a class turned into an interface of the same name is already a kind change between two type kinds.
- **Containers:** classes (methods, constructors, accessors, fields), interfaces (properties, methods), enums (members), namespaces and `declare` modules (every item of this table). A class expression or object literal is not a container, except the class of an `export default` (R7).
- **PSI shapes:** a class field is a `JSField` inside a field statement, and the field statement, with its decorators, modifiers and semicolon, is the declaration's range. Enum members share one field statement, so the commas between them belong to the enum. `namespace A.B { }` is a `TypeScriptModule` inside an implicit module of the same range, which the provider reads through. `TypeScriptInterface`, `TypeScriptEnum` and `TypeScriptTypeAlias` are `JSClass` subtypes and `TypeScriptFunctionSignature` is a `JSFunction`, so the provider tests the TypeScript types first.
- **Several names (R4):** `const a = 1, b = 2` is two declarations sharing their statement, as Python's `a = b = 0` is, with the `group` and `range` of `DeclarationInfo`: the first takes the statement's start, with its JSDoc, `export` and keyword, the last takes its end and the semicolon, and the commas between them belong to the container. A single name owns the whole statement.
- **Exports (R7):** in the PSI, `export` is part of the declaration's attribute list, so it is in its range and its own tokens; adding `export` changes the declaration. `export default function` and `export default class` are an `ES6ExportDefaultAssignment` holding a function or class expression; that element is the declaration, named after the function or class, or `default` when it has no name. An `export default` of any other expression, `export { a, b }`, `export * from` and `import` belong to the file region (R8).
- **Not declarations (R8):** everything inside a function or method body, arrow functions and function expressions other than those of R4, object literal members, destructuring declarations (`const { a, b } = …`), `module.exports = …` and `exports.x = …`, index, call and construct signatures in an interface, and class static blocks. Their source belongs to the declaration that contains them, or to the file region.
- **Ranges (R9):** decorators are part of the PSI element. A JSDoc comment (`/** … */`) right above a declaration is the first child of its PSI element, so it is in the range, as a Java doc comment is; other comments follow the existing rule for leading comments.
- **Repeated keys (R12):** TypeScript overload signatures are separate `JSFunction` elements of one name; their signatures differ by parameter types, so each has its own key. When two share a key, as an untyped implementation and an overload of `any` can, they follow the matcher's rules for repeated keys.

## 5. Signatures

- **TypeScript (R10):** each parameter's type annotation, normalized as in technical specification §6.6, joined with `,`. A parameter without an annotation is `any`. An optional parameter (`a?: T`) is `T?`, a rest parameter (`...a: T[]`) is `...T[]`, and a parameter with a default value and no annotation is `any`. `this:` parameters are kept as `this: T`, since they change how a function is called. An optional or rest parameter without a type is `any?` or `...any`. Parameter properties (`constructor(private x: number)`) are written as their type. Generic parameters and the return type are left out, as for the other languages.
- **JavaScript (R11):** each parameter's name, joined with `,`, with `...` before a rest parameter and without default values; a destructuring parameter is its pattern normalized like a display signature, without default values. This follows Python, where most parameters have no type.
- The key form drops all whitespace; the display form keeps one space after `,` and `:`, around `|`, `&`, `=>` and `=`, and between two words, as in `keyof T`.
- A function, method or constructor has a signature even with no parameters (the empty one). A getter has the empty signature and a setter its one parameter, so a getter and a setter of one name differ by name already (§4).
- A function-valued variable of R4 takes the signature of its arrow function or function expression.

## 6. Whitespace policy

- JavaScript and TypeScript trees use `INSIGNIFICANT`, like Java (R13), with significant ranges from `significantWhitespace(file)`, as Rust's macro invocations are. A whitespace token in a significant range is compared exactly.
- **Automatic semicolon insertion (R14):** the significant ranges are the whitespace tokens between a statement, class field or type alias that does not end with `;` and the next code token, comments between them included, and the whitespace tokens right after the keywords `return`, `throw`, `break`, `continue` and `yield`. A line break joined or split there can change what the code means, so it is a change. A statement that ends with a block, such as `if`, a loop or a function declaration, needs no terminator and gets no range; one that ends with an expression in braces, such as `const f = () => {}`, does. When the next code token is `;`, `}`, `)` or `,`, or there is none, the statement ends there with or without a line break, so that whitespace gets no range either. A file written with semicolons gets no significant range from the first rule, and reformatting it stays formatting-only. In a file without semicolons, changing the indentation of a line that follows such a statement is a change too, since the whole whitespace token is compared.
- **JSX text (R15):** whitespace tokens that are direct content of a JSX element, after its start tag's `>` and before its end tag's `</`, are significant, since JSX keeps spaces inside a line of text; whitespace inside a tag, between attributes, is not. The lexer emits the same `WHITE_SPACE` tokens there as the PSI, so the ranges match the tokens exactly.
- **Strings:** string literals and template literal text are single tokens or parts of the lexer's template tokens, so a change inside them is a change already.
- `OutlineController.whitespacePolicy` maps every language id of §1 to `INSIGNIFICANT`.

## 7. Parsing and parse errors

- Each side is parsed with `PsiFileFactory.createFileFromText(fileName, language, text, false, false)` inside the engine's read action, with the diff's file name, or `file.js`, `file.ts` or `file.tsx` by language when the diff has none. The language is chosen from the file name's extension: `TypeScript` for `.ts`, `.mts` and `.cts`, `TypeScript JSX` for `.tsx`, and `ECMAScript 6` for `.js`, `.mjs`, `.cjs` and `.jsx`; without one of these extensions it follows the request's language id, with every JavaScript id, Flow included, read as `ECMAScript 6`. Passing the plain `JavaScript` language would parse an older dialect without classes or arrow functions.
- Offsets are checked as for the other languages: the element's text equals the request text sliced by its range, on content with CRLF line ends.
- Parse errors follow technical specification §6.7.
- **Findings of the first build (2026-11-24):** the JavaScript plugin of IntelliJ IDEA Ultimate 2025.1.3 declares `<depends>` on `com.intellij.modules.ultimate`, `com.intellij.modules.xml` and `com.intellij.modules.json`. The test sandbox needs only `com.intellij.modules.json` as an extra bundled plugin (`bundledPlugin` and `testBundledPlugin`); the other two are modules of the Ultimate platform itself. The plugin loads headless with no licence prompt, and no test-only stand-in is needed.
- **PSI found:** files are `JSFileImpl`; languages `JavaScript`, `ECMAScript 6`, `Flow JS`, `TypeScript` and `TypeScript JSX` are registered. Declarations are `JSFunction` (`TypeScriptFunction` in TypeScript), `JSClass` (`ES6Class`, `TypeScriptClass`, and `ES6ClassExpression` or `TypeScriptClassExpression` under `export default`), `JSVarStatement` with `JSVariable` children, `JSField` (`ES6Field`, `TypeScriptField`) in a field statement, `TypeScriptInterface` with a `TypeScriptObjectType` body holding `TypeScriptPropertySignature`, `TypeScriptFunctionSignature`, `TypeScriptIndexSignature` and `TypeScriptCallSignature`, `TypeScriptTypeAlias`, `TypeScriptEnum` with `TypeScriptEnumField`, `TypeScriptModule` (in a `TypeScriptImplicitModule` for dotted names) and `ES6ExportDefaultAssignment`. Parameters are `JSParameter` or `TypeScriptParameter` and `JSDestructuringParameter` in a `JSParameterList`; blocks are `JSBlockStatement`, static blocks `JSStaticBlock`, and JSX elements `JSXXmlLiteralExpression`, a `JSXmlLiteralExpression`.

## 8. Statements for moved code

- `findsStatements` is on (R16). A statement block is a `JSBlockStatement`, and a statement is any `JSStatement` child of it, as Java's statements are. Local functions and classes inside a body are statements, since they are not declarations here (R8).

## 9. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates and kind changes (R17):** they work on the declaration model and need no JavaScript code.
- **Rename grouping:** out of scope. Until a `JavaScriptReferenceResolver` exists, renames show no usage count, as for a language without a resolver.
- **Merges and the Changes tree (R18):** both use the engine's result and need no JavaScript code.

## 10. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| B0 | The ADR 0011 amendment, the `javascript/` subproject, `smartdiff-javascript.xml` and the `webstorm` verifier family | `buildPlugin check` runs a JavaScript platform test; the verifier passes on all targets |
| B1 | `JavaScriptDeclarationProvider`, `JavaScriptInterop` (reusing the `NAMESPACE` kind of the Tree-sitter engine), the whitespace rules, provider tests, golden fixtures and merge fixtures | R1 to R19 pass in tests named after them |
| B1b | Relationship benchmark fixtures and a corpus validation on two active open-source TypeScript projects | The benchmark keeps its `no-new-wrong-pairs` gate; a corpus run is in [VALIDATION.md](../../VALIDATION.md) |

B0 and B1 ship in one change. B1b follows as its own change.

## 11. Tests

- **`JavaScriptDeclarationProviderTest`** (`javascript/src/test/kotlin/.../psi/javascript/`): tests named after R1 to R16, each with `// languages/16-javascript-typescript Rn`; offset checks assert that every declaration's range and tokens match the request text. R1 also checks that the optional descriptor registers the provider when the JavaScript plugin is present.
- **Golden fixtures** in `src/test/testData/javascript/` and `src/test/testData/typescript/`, run by `JavaScriptGoldenFixturesTest` through the shared golden harness, which takes a case's extension from its `old.*` file, so `.jsx` and `.tsx` cases sit among the `.js` and `.ts` ones: the standard set of technical specification §12, plus a method added to a class, a field added, a getter and setter pair, an arrow function constant changed (R4), `export` added (R7), an anonymous default export, a decorator changed (R9), a JSDoc comment changed (R9), an interface property added (R5), an enum member added (R5), a namespace member changed (R5), TypeScript overloads (R12), a parameter type changed (R10), a JavaScript parameter renamed (R11), a file with semicolons reformatted (R13), a semicolon-free file where joining two lines changes the meaning (R14), JSX text re-spaced (R15), a TSX component changed, and a statement moved between two methods (R16).
- **Merge fixtures:** `JavaScriptMergeFixturesTest` runs the cases in `src/test/testData/typescript/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus `export` added on one side against a method changed on the other and interface properties added on both sides.
- **Architecture test:** `JavaScriptArchitectureTest` checks that only `JavaScriptInterop` uses the JavaScript plugin's packages, as `RustArchitectureTest` does.

## 12. Open questions

- **ASI rule (R14):** whether the significant ranges should be narrowed further, to the whitespace where joining the lines would really change the parse, such as before `(`, `[`, `` ` ``, `+`, `-` and `/`. The implementation leaves out the points before `;`, `}`, `)`, `,` and the end of the file, where the parse cannot change, and marks every other point without a semicolon, which is strict but never hides a change. A corpus run (B1b) shows how often reindenting a file without semicolons is reported as changed.
