# Platform API inventory

Every API the plugin uses that Plugin Verifier reports as experimental, internal, deprecated or scheduled for removal, and the PSI of every optional plugin dependency and the Kotlin Analysis API ([platform/01](specs/platform/01-platform-api-stability.md) R6). Each one is used only by its adapter, which the architecture test checks (R7), and `./gradlew checkPlatformApiInventory` fails when a verifier report names a use by a plugin class that no class or member row of this table lists (R8); a package row names an optional plugin's whole PSI for the architecture test and lists no reported use.

Written from the Plugin Verifier reports of IntelliJ IDEA 2025.1 (IC-251.26927.53) and 2026.3 (IU-263.7307.200), PyCharm 2025.1 and 2026.3, and RustRover 2025.1 and 2026.3, on 2026-11-17, GoLand 2025.1 and 2026.3 and PhpStorm 2025.1 and 2026.3 on 2026-11-24, DataGrip 2025.1 and the latest DataGrip on 2026-11-25, and from the imports of the main, `rust`, `go`, `php` and `sql` source sets. A public replacement was checked once in 2025.1, the lowest supported build (R9).

## Inventory

The API column names a class, a class and member as `Class#member`, or a package with its subpackages as `package..`. The adapter column names the classes, or the packages ending in `..`, that may use it. The feature is the [`GuardedFeature`](../src/main/kotlin/io/github/agadek/smartdiff/engine/api/ApiGuard.kt) that `ApiGuard` turns off when the API is missing (R10).

| API | Status | Adapter | Feature | Fallback | Public replacement in 2025.1 |
| --- | --- | --- | --- | --- | --- |
| `com.intellij.openapi.vcs.changes.ChangesViewNodeAction` | experimental | `io.github.agadek.smartdiff.ui.changes.DeclarationNodeAction` | `CHANGES_TREE_HOOK` | The Changes tree without summaries or declaration rows, as the platform shows it (R13) | None: the platform's node action extension takes only this interface |
| `com.intellij.collaboration.util.RefComparisonChange` | experimental | `io.github.agadek.smartdiff.ui.changes.CodeReviewTreeAdapter` | `REVIEW_TREE_HOOK` | The pull and merge request trees without summaries or declaration rows, as the platform shows them (R13); the Git log and Changes tree keep theirs | None: the collaboration tools give a request's changes only as this experimental class |
| `com.intellij.collaboration.util.RefComparisonChange#getFilePathAfter` | experimental | `io.github.agadek.smartdiff.ui.changes.CodeReviewTreeAdapter` | `REVIEW_TREE_HOOK` | The pull and merge request trees without summaries or declaration rows, as the platform shows them (R13); the Git log and Changes tree keep theirs | None: the collaboration tools give a request's changes only as this experimental class |
| `com.intellij.collaboration.util.RefComparisonChange#getFilePathBefore` | experimental | `io.github.agadek.smartdiff.ui.changes.CodeReviewTreeAdapter` | `REVIEW_TREE_HOOK` | The pull and merge request trees without summaries or declaration rows, as the platform shows them (R13); the Git log and Changes tree keep theirs | None: the collaboration tools give a request's changes only as this experimental class |
| `com.intellij.collaboration.util.RefComparisonChange#getRevisionNumberAfter` | experimental | `io.github.agadek.smartdiff.ui.changes.CodeReviewTreeAdapter` | `REVIEW_TREE_HOOK` | The pull and merge request trees without summaries or declaration rows, as the platform shows them (R13); the Git log and Changes tree keep theirs | None: the collaboration tools give a request's changes only as this experimental class |
| `com.intellij.collaboration.util.RefComparisonChange#getRevisionNumberBefore` | experimental | `io.github.agadek.smartdiff.ui.changes.CodeReviewTreeAdapter` | `REVIEW_TREE_HOOK` | The pull and merge request trees without summaries or declaration rows, as the platform shows them (R13); the Git log and Changes tree keep theirs | None: the collaboration tools give a request's changes only as this experimental class |
| `com.jetbrains.python.ast.PyAstClass#getName` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..` | `PYTHON_PROVIDER` | Python files get the analysis fallback (R11) | None: `PyClass.getName` is declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstFunction#getName` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..` | `PYTHON_PROVIDER` | Python files get the analysis fallback (R11) | None: `PyFunction.getName` is declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstNamedParameter#getName` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..` | `PYTHON_PROVIDER` | Python files get the analysis fallback (R11) | None: declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstNamedParameter#isKeywordContainer` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..` | `PYTHON_PROVIDER` | Python files get the analysis fallback (R11) | None: declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstNamedParameter#isPositionalContainer` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..` | `PYTHON_PROVIDER` | Python files get the analysis fallback (R11) | None: declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstTargetExpression#getName` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.python..`, `io.github.agadek.smartdiff.references.PythonReferenceResolver` | `PYTHON_PROVIDER`, `PYTHON_REFERENCES` | The analysis fallback for the provider; renames without grouped usages for the resolver (R11, R12) | None: declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstReferenceExpression#getNameElement` | experimental | `io.github.agadek.smartdiff.references.PythonReferenceResolver` | `PYTHON_REFERENCES` | Python renames without grouped usages (R12) | None: declared on this experimental interface |
| `com.jetbrains.python.ast.PyAstReferenceExpression#getReferencedName` | experimental | `io.github.agadek.smartdiff.references.PythonReferenceResolver` | `PYTHON_REFERENCES` | Python renames without grouped usages (R12) | None: declared on this experimental interface |
| `org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader` | experimental | `io.github.agadek.smartdiff.engine.declarations.psi.markdown..` | `MARKDOWN_PROVIDER` | Markdown files get the analysis fallback (R11) | None: the front matter header has no public PSI type |
| `org.jetbrains.plugins.scala..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.scala..`, `io.github.agadek.smartdiff.references.ScalaReferenceResolver` | `SCALA_PROVIDER`, `SCALA_REFERENCES` | Scala files get the analysis fallback; Scala renames without grouped usages (R11, R12) | None: the Scala plugin publishes no stable PSI API |
| `com.jetbrains.python..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.python..`, `io.github.agadek.smartdiff.references.PythonReferenceResolver` | `PYTHON_PROVIDER`, `PYTHON_REFERENCES` | Python files get the analysis fallback; Python renames without grouped usages (R11, R12) | None: the Python plugin publishes no stable PSI API |
| `org.rust..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.rust..` | `RUST_PROVIDER` | Rust files get the analysis fallback (R11) | None: the Rust plugin publishes no stable PSI API |
| `com.intellij.lang.javascript..`, `com.intellij.lang.ecmascript6..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.javascript..` | `JAVASCRIPT_PROVIDER` | JavaScript and TypeScript files get the analysis fallback (R11) | None: the JavaScript plugin is closed source and publishes no stable PSI API |
| `com.goide..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.go..` | `GO_PROVIDER` | Go files get the analysis fallback (R11) | None: the Go plugin is closed source and publishes no stable PSI API |
| `com.intellij.psi.css..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.css..` | `CSS_PROVIDER` | CSS, SCSS and Less files get the analysis fallback (R11) | None: the CSS plugin is closed source and publishes no stable PSI API; the SCSS and Less constructs are read by element type name, so no Sass or Less class is used ([languages/18 details §1](specs/languages/18-css-details.md#1-packaging-and-ide-versions)) |
| `com.jetbrains.php..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.php..` | `PHP_PROVIDER` | PHP files get the analysis fallback (R11) | None: the PHP plugin is closed source and publishes no stable PSI API |
| `com.intellij.sql..` | non-public plugin PSI | `io.github.agadek.smartdiff.engine.declarations.psi.sql..` | `SQL_PROVIDER` | SQL files get the analysis fallback (R11) | None: the Database Tools and SQL plugin is closed source and publishes no stable PSI API; the provider uses its `com.intellij.sql.psi` interfaces and nothing of its `com.intellij.database` model ([languages/20 details §1](specs/languages/20-sql-details.md#1-packaging-and-ide-versions)) |
| `org.jetbrains.kotlin.analysis..` | Kotlin Analysis API, not used | `io.github.agadek.smartdiff.references.KotlinReferenceResolver` | `KOTLIN_REFERENCES` | Kotlin renames without grouped usages (R12) | Not needed: the resolver uses `PsiReference.resolve()` only |

Each optional plugin subproject's own architecture test checks its plugin's row, and the root's checks the others:

- `rust`: the `org.rust..` row;
- `javascript`: the JavaScript plugin row;
- `go`: the `com.goide..` row;
- `css`: the `com.intellij.psi.css..` row;
- `php`: the `com.jetbrains.php..` row;
- `sql`: the `com.intellij.sql..` row.

## Replaced

- `com.intellij.lang.LanguageStructureViewBuilder#INSTANCE` (deprecated) was replaced by `LanguageStructureViewBuilder.getInstance()`, public in 2025.1, in `StructureViewDeclarationProvider` (R9).

## Not listed

The reports also name experimental Python AST interfaces and `com.intellij.psi` interfaces that other plugins' own classes reference, such as `PyAstTupleExpression` referenced in `PyTupleExpression`. They are not uses by a plugin class, so the inventory check ignores them.

## Ignored verifier problems

An optional dependency that an IDE does not bundle and the Marketplace does not offer leaves its descriptor's classes unresolved. On the Linux IDE builds Plugin Verifier reports them as compatibility problems, although that descriptor never loads there. Each verifier family ignores only its own such problems, through an ignored-problems file under [gradle/verifier](../gradle/verifier) ([languages/16 details §3](specs/languages/16-javascript-typescript-details.md#3-plugin-verifier)):

| Family | Absent dependency | Ignored |
| --- | --- | --- |
| `idea` | JavaScript, Go and CSS plugins, in IntelliJ IDEA Community | Unresolved packages and classes under `com.intellij.lang.javascript`, `com.intellij.lang.ecmascript6` and `com.intellij.lang.typescript`, `com.goide` ([languages/17 details §3](specs/languages/17-go-details.md#3-plugin-verifier)) and `com.intellij.psi.css` ([languages/18 details §3](specs/languages/18-css-details.md#3-plugin-verifier)) |
| `webstorm` | Java plugin, in WebStorm | The Java plugin classes `JavaDeclarationProvider` and `JavaReferenceResolver` use, each by name, and the `com.intellij.lang.java` and `com.intellij.pom.java` packages |

Ignored problems are not uses of the API this inventory lists. They stay in each target's report, in `ignored-problems.txt`. A new class in an ignored family fails verification until it is reviewed and added to the file.
