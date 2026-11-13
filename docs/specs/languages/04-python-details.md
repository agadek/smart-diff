# Python: details

Status: proposed, 2026-11-13

**Feature:** [Python](04-python.md)

## 1. Packaging and IDE versions

- **Plugin:** the Python PSI comes from the Python plugin, `PythonCore` (the open-source part of the Python support in the IntelliJ Community repository, under `python/`). PyCharm bundles it from 2025.1, when PyCharm Community and Professional became one product. IntelliJ IDEA installs it from the Marketplace, and IntelliJ IDEA Ultimate's `Pythonid` plugin depends on it, so one dependency covers PyCharm, IntelliJ IDEA with either Python plugin, and the other IDEs that install it (R1).
- **Descriptor:** the provider and the reference resolver register in `META-INF/smartdiff-python.xml`, loaded through `<depends optional="true" config-file="smartdiff-python.xml">com.intellij.modules.python</depends>` in `plugin.xml`. Spike S7 confirmed that `PythonCore` 251.26927.70 declares `<module value="com.intellij.modules.python"/>`, so the module dependency is correct. If a target IDE does not declare it, the descriptor depends on `PythonCore`.
- **Build:** `PythonCore` is not bundled with IntelliJ IDEA Community 2025.1.3, the build target, so the build pins a Marketplace release compatible with `platformVersion`, as for Scala: `pythonPluginVersion` in `gradle.properties` and `plugin("PythonCore", …)` in `build.gradle.kts`. IntelliJ IDEA Community 2025.1.3 is build 251.26927.53, so the pinned release is `251.26927.70` (since-build 251.26927). `PythonCore`'s descriptor also requires the bundled JSON and TOML plugins; the build declares `com.intellij.modules.json` and `org.toml.lang` as bundled plugins so the test application loads `PythonCore`. Raise all three together with `platformVersion`.
- **Language id:** `Python`; `PyFileType` covers `.py`, `.pyw` and `.pyi` (R2). Other file types of the Python plugin, such as notebooks and requirements files, have other language ids and stay unsupported.
- **IDE versions:** no change to P6. The plugin still works on IntelliJ Platform 2025.1 and newer; Python support needs a `PythonCore` release for that IDE.

## 2. Plugin Verifier

- The verifier runs on IntelliJ IDEA Community 2025.1.3, the latest IntelliJ IDEA release, PyCharm 2025.1.3 and the latest PyCharm release. It resolves the optional `com.intellij.modules.python` dependency and installs a compatible `PythonCore` for each, as it does for `org.intellij.scala`; each run's report records the resolved version.
- The 2026-11-13 run with `-Psmartdiff.verifier.ides=pycharm` checked PyCharm 2025.1.3 (build 251.26927.74) and PyCharm 2026.3 (263.7307.206); every target was `Compatible` with 3 experimental-API usages, all from the existing Changes tree node action, none from Python. PyCharm Community 2025.1.3 bundles `PythonCore` 251.26927.74 and declares `com.intellij.modules.python`.
- `verifyPlugin` takes the Gradle property `smartdiff.verifier.ides`, a comma-separated set of IDE families (`idea`, `pycharm`, later `rustrover`; all by default). An unknown family fails the build with a clear message.
- Each extra IDE is a full download. The `verify` job of `.github/workflows/build.yml` is a matrix with one job per IDE family (`idea`, `pycharm`), `fail-fast: false`, each freeing disk space first, running `./gradlew verifyPlugin -Psmartdiff.verifier.ides=<family>` and uploading its own report artifact, so no single runner holds four IDEs. The release workflow runs plain `verifyPlugin` over all families.
- The verifier's `dependencies.txt` lists `com.intellij.modules.python` as an unresolved optional dependency, with the report's reason taken from the bundled Shell plugin's `intellij.sh.python` content module. The module itself is declared by `PythonCore` in the IDE; the optional descriptor is empty until P1, and P1 checks that the descriptor loads. If the verifier must load it, the fallback is the `PythonCore` plugin id, which the verifier resolves from the bundle or the Marketplace.
- Python PSI is public API in the IntelliJ Community repository, so it is not expected in the internal-API report. Any use the verifier reports as internal or experimental is reviewed there, as [relationships/04](../relationships/04-rename-grouping.md) R15 does for Kotlin and Scala.

## 3. Parsing

- `PsiFileFactory.createFileFromText(name, PythonLanguage.INSTANCE, text, false, false)`, inside the engine's read action, as in technical specification §6.2.
- **Language level (R17):** a file from text has no module and no interpreter, so its level would come from the default project's SDK, which the user can change. The provider sets the newest level, `LanguageLevel.getLatest()`, on the file before the tree is built, as Java forces its language level. S7 recorded `LanguageLevel.getLatest()` as 3.14 for `PythonCore` 251.26927.70, and a text-built file already reports 3.14 and parses `type X = int`, `match` and PEP 695 generics without `PsiErrorElement`, so forcing is not needed on 2025.1.3. If a level must be forced, `PythonLanguageLevelPusher.specifyFileLanguageLevel(lightVirtualFile, level)` is honoured before the tree is built (a light virtual file set to 2.7 reports 2.7); `createFileFromText` parses before a level can be set, so the provider creates a `LightVirtualFile`, sets the level and takes its PSI through `PsiManager`. P1 confirms that route.
- **Dialect:** Python has one dialect for the engine; `dialect()` returns null.
- **Offsets:** S7 confirmed that `PyClass`, `PyFunction` and module- and class-level `PyTargetExpression` elements' text equals the request text sliced by their ranges, on LF and on CRLF-converted content. The Python parser reports errors on the CRLF text (42 `PsiErrorElement`s, starting with `End of statement expected`, and `BAD_CHARACTER` for the carriage returns), although the element ranges still slice exactly; request texts are LF document snapshots (§6.2 of the technical specification), so the provider must not convert line separators itself.

## 4. Declarations

| PSI element | Kind | Name | Signature |
| --- | --- | --- | --- |
| `PyClass` | CLASS | `name` | — |
| `PyFunction` (module level or in a class body, `async` too) | FUNCTION | `name` | parameter names (§5) |
| `PyTargetExpression` of an assignment or annotated name at module level or in a class body | VARIABLE | the target name; `(a, b)` for a tuple target | — |
| `PyTypeAliasStatement` (Python 3.12) | TYPE_ALIAS | `name` | — |

- **New kind:** `VARIABLE`, added to `DeclarationKind` and shown as `variable`. Kotlin's `PROPERTY` would read `property x` and be confused with `@property`; Python has no other word for a module or class attribute.
- **Containers:** classes. A method is a `FUNCTION` child of its class; there is no separate method kind, as in Kotlin and Scala.
- **Compound statements (R7):** the provider walks into `if`, `elif`, `else`, `try`, `except`, `finally`, `with`, `for` and `while` statements at module level and in class bodies, and their definitions belong to the enclosing module or class. Their own keywords and conditions are the container's or the file region's tokens. Two definitions of one name in two branches, such as an `if TYPE_CHECKING:` import fallback, repeat a key and follow the matcher's rules for repeated keys.
- **Not declarations (R8):** everything inside a function body (nested functions, classes and assignments), lambdas, comprehensions, `self.x = …` assignments, `global` and `nonlocal`, imports, and other expression statements such as `if __name__ == "__main__": main()`. Their source belongs to the declaration that contains them, or to the file region.
- **Several targets:** `a = b = 0` gives two variables, `a` and `b`; as for Java fields (technical specification §6.4), the first takes the tokens before its name and the last takes everything after its own name. `a, b = 1, 2` binds through one tuple target, so it is one variable named `(a, b)` (R5), as in Scala. `__all__ = […]` is a variable like any other. Augmented assignments (`x += 1`) and assignments to attributes or subscripts bind no new name and are not declarations.
- **Annotations without a value:** `x: int` in a class body (a dataclass field) is a variable (R4).
- **Ranges (R9):** a declaration's range starts at its first decorator, or at the comments directly above it with no blank line between, and ends at the end of its last line. Decorators are in its own tokens, so a decorator change changes the declaration (R10).
- **Docstrings (R16):** a docstring is a string literal in the body, so it is an `OTHER` token and a change to it is a content change, not a comment-only change.
- **Repeated names (R12):** `@overload` variants, a property getter and its `@x.setter`, and a redefinition in the same container share a name and kind. They keep their own signatures where those differ and otherwise repeat a key.

## 5. Signatures

- Python calls by keyword as well as by position, so the parameter names are part of the call contract, and most code has no parameter types. The signature is therefore the parameter list with names and markers, not types (R11): each parameter's name, with `*` before a variadic and `**` before a keyword variadic, the bare `*` and `/` separators as written, joined with `,` and without whitespace, annotations or defaults. `def f(self, a: int = 1, /, *args, key=None, **kw)` → `self,a,/,*args,key,**kw`.
- `self` and `cls` are kept, so turning a method into a `@staticmethod` without `self` is a signature change.
- The display signature is the same list with `, ` between parameters: `self, a, /, *args, key, **kw`.
- A function with no parameters has the empty signature; classes and variables have none.
- **Limitation:** adding a default value or a type annotation alone is a content change, not a signature change. That is deliberate: the matching key must not change when only an annotation does.

## 6. Whitespace policy

- Python trees use `INDENTATION_SIGNIFICANT`, the Scala 3 policy (technical specification §8): line breaks and the indentation after them count, while spaces within a line and blank lines are formatting (R13 to R15). This is the dedicated, tested rule that [ADR 0003](../../adr/0003-conservative-classification.md) requires before Python gets a formatting-only claim; the ADR gets a dated note linking here.
- The rule is strict in two places, which never hides a change: a line break inside brackets, where Python joins lines implicitly, still counts (R14), so re-wrapped arguments show as changed; and indentation is compared as written, so a whole file re-indented from 4 to 2 spaces is changed.
- Spike S7 ran the lexer through `LanguageParserDefinitions` for `PythonCore` 251.26927.70. `PythonParserDefinition.whitespaceTokens` is LINE_BREAK, SPACE, TAB and FORMFEED; it does not contain INDENT, DEDENT or STATEMENT_BREAK. `commentTokens` is END_OF_LINE_COMMENT, and `PyTokenTypes.WHITESPACE` is SPACE, TAB and FORMFEED. The lexer emits INDENT, DEDENT and STATEMENT_BREAK as empty tokens, and its LINE_BREAK token carries the line break and the indentation after it (`"\n    "`), so under `INDENTATION_SIGNIFICANT` the indentation is part of a whitespace token. INDENT, DEDENT and STATEMENT_BREAK stay `OTHER` tokens and are compared exactly, so a removed or added indentation level differs even before the whitespace policy applies.
- `OutlineController.whitespacePolicy` maps `Python` to `INDENTATION_SIGNIFICANT`.

## 7. Parse errors

- Code the newest Python version does not accept, such as Python 2 `print` statements, is a parse error and marks only the declarations whose code changed, as technical specification §6.7 describes.
- S7 confirmed that `# type: int` and other special comments are `END_OF_LINE_COMMENT` tokens in `PythonParserDefinition.commentTokens`, and a file containing them parses without `PsiErrorElement`.

## 8. Relationships, merges and the Changes tree

- **Extraction, inlining, rename candidates and kind changes (R18):** these work on the declaration model and need no Python code. A `class` that becomes a function of the same name, or a function that becomes a variable, is a kind change.
- **Rename grouping (R19):** a `PythonReferenceResolver` registers next to the provider. It finds usages of a function, class or variable in the compared file through `PyReferenceExpression.reference.resolve()` on the light copy, which needs no interpreter for names defined in the same file. S7 confirmed that, without an SDK, `MAX` resolves to a `PyTargetExpression`, `helper` to a `PyFunction`, `C` to a `PyClass`, and `self.method()` to the method's `PyFunction`, with `self` resolving to a `PyNamedParameter`. The resolver therefore includes `self.` calls to methods of the enclosing class; other attribute references (`obj.helper`) resolve through type inference and stay left out. A reference that does not resolve is left out, as for the other resolvers.
- **Merges and Changes tree (R20):** both use the engine's result and need no Python code.

## 9. Phases

One pull request per phase, each with its own spec status update. The phases run in parallel with the [Rust phases](05-rust-details.md#10-phases) (decided 2026-11-13), which says how the two share the verifier matrix and `DeclarationKind`.

| Phase | Content | Done when |
| --- | --- | --- |
| P0 | Build and optional dependency: pinned `PythonCore`, `smartdiff-python.xml` with no provider yet, the verifier matrix with PyCharm, and spike S7 as a platform test | `buildPlugin check` passes with the Python plugin on the classpath; the verifier passes on IntelliJ IDEA 2025.1.3, the latest IntelliJ IDEA, PyCharm 2025.1 and the latest PyCharm; S7 answers §3, §6, §7 and §8 |
| P1 | `PythonDeclarationProvider`, `VARIABLE`, the whitespace policy, provider tests and golden fixtures | R1 to R17 pass in tests named after them |
| P2 | `PythonReferenceResolver`, merge fixtures, relationship benchmark fixtures and the corpus validation | R18 to R21 pass; the benchmark keeps its `no-new-wrong-pairs` gate; a corpus run is in [VALIDATION.md](../../VALIDATION.md) |

After P2 the feature is implemented, the plugin description, README and CHANGELOG name Python, and it ships in the next release.

## 10. Tests

- **`PythonDeclarationProviderTest`** (`BasePlatformTestCase`): one test per declaration rule, R3 to R12, R16 and R17; offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/python/`: the standard set of technical specification §12 (added, removed and changed function, overloads through `@overload`, signature change, overloads that all changed signature, reorder, move between classes, class rename, method rename, formatting-only, comment-only change, string literal change, parse error on one side, empty and identical files, repeated keys), plus Python cases: decorator added (R10), property getter and setter (R12), definitions under `if TYPE_CHECKING:` (R7), nested function changed (R8), docstring change (R16), a block re-indented (R13), arguments re-wrapped (R14), spaces and blank lines only (R15), `async def` added, a tuple assignment (R5), a dataclass field added (R4), a `type` statement (R6), and a Python 2 `print` statement (§7).
- **Merge fixtures** in `src/test/testData/python/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus re-indentation on one side as a change on that side (R13, R20).
- **Relationship benchmark** cases in `src/test/testData/python/relationships/` with `corpus: python`: extraction, inlining, rename with usages, kind change and move, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md); the benchmark gets a `python` row.
- **Corpus validation:** `CorpusValidationTest` and the relationship benchmark's real-commit lines accept `.py` files. The first run uses recent commits from two active open-source Python projects with different styles, for example a web framework and a data library, and records the results in [VALIDATION.md](../../VALIDATION.md).
- **Contract corpus:** like Scala, Python stays out of `ContractCorpus`; the provider tests and golden fixtures cover it.
- **Requirement names:** every test names its requirement, such as `// languages/04-python R13`.

## 11. Open questions

- **Signature form:** names with markers (§5, proposed), or also the annotated types where every parameter has one. Types would make `@overload` variants distinct keys, but a missing annotation would then change the key.
- **Indentation width:** whether a later opt-in rule may treat a consistent re-indent as formatting-only. It needs the relative indentation of every line, which `INDENTATION_SIGNIFICANT` does not keep; left out for now.
