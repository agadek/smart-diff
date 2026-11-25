# SQL: details

Status: implemented, 2026-11-25

**Feature:** [SQL](20-sql.md)

## 1. Packaging and IDE versions

- **Plugin:** the SQL PSI comes from the JetBrains Database Tools and SQL plugin, `com.intellij.database`. DataGrip and IntelliJ IDEA Ultimate 2025.1 bundle it, as do the other paid IDEs; from 2025.3 IntelliJ IDEA is one product and SQL support is in its free tier. IntelliJ IDEA Community 2025.1.3, the main build target, has no SQL plugin.
- **Spike S16 (2026-11-25):** on IntelliJ IDEA Ultimate 2025.1.3 (`IU-251.26927.53`) the plugin is `com.intellij.database` `251.26927.53`, in `plugins/DatabaseTools`, with its PSI interfaces in `lib/database-openapi.jar` (`com.intellij.sql.psi`). Its descriptor also declares the module aliases `com.intellij.modules.sql`, `com.intellij.modules.database` and `com.intellij.modules.database.core`, and requires `com.intellij.modules.json`, `intellij.grid.plugin` and `com.intellij.platform.images`; the Grid plugin's content module `intellij.grid.charts.impl` requires `intellij.charts`. A test sandbox that declares only `com.intellij.database` loads the plugin but leaves it disabled, so the subproject declares all five.
- **Descriptor:** the provider registers in `META-INF/smartdiff-sql.xml`, loaded through `<depends optional="true" config-file="smartdiff-sql.xml">com.intellij.database</depends>` (R1).
- **Closed source:** the plugin's sources are not published. The provider uses only the PSI interfaces of §4, all in `com.intellij.sql.psi`, and element type names for the two cases no interface tells apart (a function from a procedure, a sequence from another `CREATE` statement). Every SQL plugin call is in one class, `SqlInterop`, as `PhpInterop` does for PHP; nothing of the plugin's database model (`com.intellij.database`) is used.
- **Language ids:** the SQL file type (`.sql` and `.ddl`) has the language `SQL` (`com.intellij.sql.psi.SqlLanguage`), whose dialects are languages of their own with `SQL` as base: `GenericSQL`, `SQL92`, `PostgreSQL`, `MySQL`, `MariaDB`, `SQLite`, `TSQL`, `Oracle`, `DB2` and 25 more on 2025.1.3. A diff's language id is the file type's, `SQL`, whatever dialect the project maps the file to, so the provider supports `SQL` only (R2). A text-built file in `SQL` is a `GenericSQL` file.
- **IDE versions:** no change to P6. SQL support needs the plugin in the IDE, which DataGrip and IntelliJ IDEA always have.

## 2. Dialect

S16 parsed the same samples in several dialects. Generic SQL and SQL:2016 report errors for common PostgreSQL and MySQL syntax (`CREATE OR REPLACE FUNCTION … $$ … $$`, `CREATE MATERIALIZED VIEW`, `COMMENT ON`, `AUTO_INCREMENT`, `UNIQUE KEY`, `ENGINE=`, `DELIMITER`), 20 and 10 errors on the two samples, and even reject `DROP TABLE legacy;` without `CASCADE` or `RESTRICT`; the PostgreSQL and MySQL dialects parse their samples with none. A diff of a revision has no project file whose dialect mapping could be read, so the provider chooses the dialect from the text (R3):

1. `dialect` parses every text of the comparison, both sides of a diff or the base and both sides of a merge, in `PostgreSQL`, `MySQL`, `SQLite`, `TSQL` (Microsoft SQL Server), `Oracle` and `GenericSQL`, in that order, and counts the `PsiErrorElement`s of all of them. A merge that chose from the base and one side could parse the base in two dialects, so one side's dialect-specific syntax, such as MySQL's `ENGINE=InnoDB`, decides for all three (merge fixture `dialect-from-every-side`).
2. The first dialect with no errors is chosen, and later ones are not tried, so a clean PostgreSQL file is parsed twice per text. The loop checks for cancellation before each dialect.
3. If every dialect has errors, the one with the fewest is chosen, the earlier one on a tie; its errors are parse errors as in technical specification §6.7.
4. The engine parses every text, and the reference pass its copies, in the chosen dialect, so the trees compare.

The order puts the dialects of most open-source schemas and migrations first; a file that parses in two dialects gets the earlier one, which reads it the same way. A dialect missing from the IDE is skipped.

## 3. Build target and Plugin Verifier

SQL follows the paid-IDE build pattern of [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md), as PHP does, which answers [F3](../../FORMAT-COVERAGE.md#6-open-questions) without raising the build target: a Gradle subproject `sql/` (`include("sql")`) targets **IntelliJ IDEA Ultimate 2025.1.3**, which bundles the plugin, so there is no Marketplace pin, and the main build stays on Community 2025.1.3.

- **Why not raise the build target:** raising the minimum platform to 2025.3, where IntelliJ IDEA's free tier has SQL, would drop every 2025.1 and 2025.2 IDE for all languages (P6) for one language's build. The subproject costs one more module and one more verifier family, and keeps 2025.1.3 as the lowest supported IDE.
- The subproject applies `org.jetbrains.intellij.platform.module`, the Kotlin JVM plugin, `jvmToolchain(21)`, `testFramework(TestFrameworkType.Platform)` and JUnit 4, like `php/`, and declares `com.intellij.database`, `com.intellij.modules.json`, `intellij.grid.plugin`, `com.intellij.platform.images` and `intellij.charts` as `bundledPlugins` and `testBundledPlugins` (§1).
- The root packages it with `pluginModule(runtimeOnly(project(":sql")))`, and `:sql` adds `compileOnly(project(":"))` for its main source set and `testImplementation(project(":"))` and `testImplementation(testFixtures(project(":")))` for tests, so there is no task cycle.
- `./gradlew buildPlugin check` runs `:sql:test`; the test task copies `S16 …`, `GOLDEN …`, `SQL BENCHMARK …` and `SQL VALIDATION …` lines into the build log, and forwards `-Psmartdiff.updateGolden` and `-Psmartdiff.validation.sql.corpus`.
- `verifyPlugin` gains the `datagrip` family: DataGrip 2025.1.3 (`IntelliJPlatformType.DataGrip`) and the latest DataGrip release. It is in the default set and is one more job of the `verify` matrix in `.github/workflows/build.yml` and of the release workflow's loop.
- On IntelliJ IDEA Community 2025.1.3 the optional dependency stays unresolved. As for PHP, the SQL PSI of a descriptor that never loads there is reported as unresolved, so SQL adds exactly two problem shapes to `gradle/verifier/ignored-problems-idea.txt`: the package `com.intellij.sql` (and its subpackages) not found, and access to an unresolved `com.intellij.sql` class. The latest IntelliJ IDEA has SQL, so it checks the provider.
- **DataGrip has no Java plugin,** as WebStorm and PhpStorm have none, so `datagrip` gets the same file as `webstorm`, [gradle/verifier/ignored-problems-webstorm.txt](../../../gradle/verifier/ignored-problems-webstorm.txt), with the Java classes by name and nothing for SQL.
- `verifierFamiliesTest` checks that every `idea` line names only the JavaScript packages, `com.goide`, `com.intellij.psi.css`, `com.jetbrains.php` or `com.intellij.sql`, that `idea` ignores the SQL PSI, that no family ignores `com.intellij.database`, and that `datagrip` gets only the Java file.
- Every SQL PSI use the verifier reports as internal or experimental is listed in [PLATFORM-API.md](../../PLATFORM-API.md). A `NoClassDefFoundError` or `NoSuchMethodError` from the plugin turns the provider off through `ApiGuard` (`GuardedFeature.SQL_PROVIDER`) and shows the native diff ([product scope](../README.md#product-scope) P5).

## 4. Declarations

| PSI interface (`com.intellij.sql.psi`) | Kind | Members | Signature |
| --- | --- | --- | --- |
| `SqlCreateTableStatement` | TABLE | its columns | — |
| `SqlCreateViewStatement`, materialized views included | VIEW | — | — |
| `SqlCreateIndexStatement` with a name | INDEX | — | — |
| `SqlCreateProcedureStatement` whose element type name has `FUNCTION` | FUNCTION | — | parameter types (§5) |
| any other `SqlCreateProcedureStatement` | PROCEDURE | — | parameter types (§5) |
| `SqlCreateTriggerStatement` | TRIGGER | — | — |
| `SqlCreateTypeStatement` | TYPE | — | — |
| `SqlCreateSchemaStatement` | SCHEMA | — | — |
| another `SqlCreateStatement` whose element type name has `CREATE_SEQUENCE` | SEQUENCE | — | — |
| `SqlColumnDefinition` in a table's element list | COLUMN | — | — |

- **New kinds:** `TABLE`, `VIEW`, `COLUMN`, `INDEX`, `SEQUENCE`, `PROCEDURE`, `TRIGGER` and `SCHEMA` join `DeclarationKind`, labelled `table`, `view`, `column` and so on, so the outline reads `table orders › column total`. Functions and types take the existing `FUNCTION` and `TYPE`.
- **Kind families (R12):** `TABLE` and `VIEW` are a family of their own, so a table replaced by a view of the same name is a kind change; `PROCEDURE` joins the family of functions and methods, so a function turned into a procedure is one too.
- **Dialect classes:** S16 found the dialects' own classes behind the same interfaces, such as `PgCreateTableStatementImpl` (`PG_CREATE_TABLE_STATEMENT`) and `SqlCreateTableLikeStatementImpl` for MySQL, `PgCreateFunctionStatementImpl` for PostgreSQL and `SqlCreateFunctionStatementImpl` for MySQL, both `SqlCreateProcedureStatement`. Every element's text equals the request text sliced by its range.
- **Top level:** the provider reads the file's children. A `CREATE` statement of the table above is a declaration; another statement is not, and nothing inside it is read. A child that is no statement, such as an `SqlBatchBlock` or the block the parser makes around a parse error, is read the same way, so the declarations around an error stay declarations.
- **Columns (R6):** a table's columns are the `SqlColumnDefinition`s of its `SQL_TABLE_ELEMENT_LIST` (no interface; read as the table's children's children), in source order. Table constraints (`CONSTRAINT … CHECK`, `PRIMARY KEY (…)`, `UNIQUE KEY`), table options (`ENGINE=InnoDB`) and the query of `CREATE TABLE … AS SELECT` are the table's own tokens.
- **Not declarations (R7):** `ALTER`, `DROP`, `INSERT`, `UPDATE`, `SELECT`, `GRANT`, `COMMENT ON`, `SET`, MySQL `DELIMITER` statements and `CREATE` statements of other objects (extensions, roles, domains, databases) are content of the file region. A routine body, whether dollar-quoted, a string or a `BEGIN … END` block, is content of its routine. `CREATE INDEX ON t (c)`, a PostgreSQL index without a name, is no declaration.
- **Repeated keys:** PostgreSQL overloads, functions of one name with different parameter types, have different signatures and so different keys.

## 5. Signatures

- A routine is called by position, and PostgreSQL tells overloads apart by their parameter types, so a signature is each parameter's type in order (R9), from `SqlRoutineDefinition.getParameterList()` and each parameter's `getTypeElement()`. The key form has no whitespace (`INTEGER,NUMERIC(12,2)`); the display form has one space where the source has whitespace and `, ` between parameters (`INTEGER, NUMERIC(12, 2)`).
- Parameter names, modes (`IN`, `OUT`, `INOUT`), default values and the `RETURNS` clause are left out. Changing a type changes the signature; renaming a parameter or changing a default changes only the content. A parameter without a type has an empty type.
- Types are compared as written: `int` and `INTEGER` are different text, so changing one into the other changes the signature.
- Functions and procedures have a signature even with no parameters (the empty one); other kinds have none.

## 6. Names

- A declaration is named by `SqlDefinition.getNameElement()`: the names of its `SqlIdentifier`s, which leave out the quotes of `"…"`, `` `…` `` and `[…]`, joined by `.` (R5): `billing.invoices`, `users` for `` `users` ``. Changing only the quoting of a name keeps the declaration; the quotes are still content.
- Names keep their case, so changing it is a rename candidate, as it is in a quoted identifier.
- A definition without a name element, or with an empty part, is no declaration.
- A trigger, and an index in MySQL and SQL Server, is named without its table, as the statement writes it: two tables' `CREATE TRIGGER audit … ON a` and `… ON b` are two declarations with the same key, which the engine pairs as it pairs any same-key declarations, by order. PostgreSQL index names are unique in their schema, so its indexes do not collide.

## 7. Ranges and comments

- S16 showed that a comment above a statement is a sibling before it in the file, and one above a column is a sibling before it in the table's element list, outside their ranges, and that a statement's `;` is a sibling after it.
- The provider extends a declaration's range back over the comments directly above it (R8), by the PHP rule ([languages/19 details §7](19-php-details.md#7-ranges-comments-and-attributes)): `--`, `#` and `/* */` comments that end on the line before, with no blank line between. A comment after code on its line stays outside.
- It extends a statement's range forward to the `;` after it, past whitespace only, and a column's to the `,` after it, so adding or removing a statement or a column changes nothing else. A MySQL statement that ends with a custom delimiter, such as `//` after `DELIMITER //`, takes that token the same way: S16 showed that the MySQL lexer gives it an element type whose name ends with ` DELIM`, so `END //` ends the procedure's range. Appending a column after the last one changes the former last column, whose range gains the new `,`, as appending a part to a PHP property statement does.

## 8. Whitespace policy

- SQL statements end with `;` and a line break never ends one, so SQL trees use `INSIGNIFICANT`, as Java does (R10), and `OutlineController.whitespacePolicy` maps `SQL` to it.
- String literals and quoted identifiers are lexer tokens, so a change in their spacing is a change (R11). A PostgreSQL dollar-quoted routine body (`$$ … $$`) is a string constant in PostgreSQL and one lazily parsed token for the lexer, so re-indenting a line inside it is a change, not formatting-only; the provider declares no significant whitespace ranges.

## 9. Parsing and parse errors

- `PsiFileFactory.createFileFromText(fileName ?: "file.sql", dialect, text, false, false)`, inside the engine's read action, in the dialect of §2.
- Offsets are checked on LF and on CRLF content, as for the other languages.
- Parse errors follow technical specification §6.7. An empty side, as of a new or deleted file, parses with no error, so the file is `ANALYZED` with every declaration added or removed.

## 10. Relationships, merges and the Changes tree

- **Rename candidates, kind changes and moves (R12):** they work on the declaration model; a renamed table keeps its columns as members, so their paths change with it. A column is short: one renamed with only its type, such as `email VARCHAR(200)` to `email_address VARCHAR(200)`, has too little content to be a rename candidate, and shows as removed and added (golden fixture `column-rename`).
- **Out of scope:** extraction and inlining detection, which need routine bodies as statement blocks, and moves inside declarations; the provider gives no body and no statement blocks. Rename grouping: S16 showed that in a text-built file `orders` in `CREATE INDEX … ON orders` and in `FROM orders` resolves to its `SqlCreateTableStatementImpl`, and `customer_id` to its `SqlColumnDefinitionImpl`, so an `SqlReferenceResolver` can follow as its own change.
- **Merges and Changes tree (R13):** both use the engine's result and need no SQL code.
- **Benchmark:** the relationship benchmark gains the `sql` corpus, with cases in `src/test/testData/sql/relationships/` listed in `src/test/testData/relationships-corpus-sql.txt`, labelled as in [relationships/01](../relationships/01-relationship-benchmark.md), and run by `SqlRelationshipBenchmarkTest` from `:sql:test`, under the `no-new-wrong-pairs` gate.

## 11. Tests

- **`SqlS16SpikeTest`** (`sql/src/test/kotlin/.../psi/sql/`): the spike that answered §1, §2, §4, §7 and §10, printing `S16 …` lines and writing the PSI trees of a generic, a PostgreSQL and a MySQL sample in several dialects to `build/sql-spike/`; `-Dsmartdiff.spike.verbose=true` also prints every element with its interfaces.
- **`SqlDeclarationProviderTest`** (`sql/src/test/kotlin/.../psi/sql/`): tests named after R1 to R13, each naming its requirement with `// languages/20-sql Rn`, with offset checks that every declaration's range and tokens match the request text, on LF and CRLF content. R1 also checks that the optional descriptor registers the provider when the plugin is present.
- **Golden fixtures** in `src/test/testData/sql/`, run by `SqlGoldenFixturesTest` through the shared golden harness: the standard set of technical specification §12 where SQL has it (identical, empty, new file, formatting-only, whole file re-indented, line break only, comment only, reorder, kind change, signature change, parse error), plus SQL cases: a comment added (R8), a string literal's spacing and a dollar-quoted body re-indented (R11), a routine body changed, a parameter renamed, a return type changed and an overload added (R9), a table renamed, columns added, inserted, removed, retyped and renamed, and a constraint changed (R6), an index added and a view changed (R4), an `INSERT` and an `ALTER TABLE` changed (R7), and two MySQL cases (R3).
- **Merge fixtures:** `SqlMergeFixturesTest` (`sql/src/test/kotlin/.../merge/`) runs the cases in `src/test/testData/sql/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, with `SET search_path` statements as the imports, and four SQL cases: a comment against a column change, columns added on both sides, a signature against a body change, and a side whose MySQL syntax decides the dialect of all three texts (R3, R13, R14).
- **Relationship benchmark:** `SqlRelationshipBenchmarkTest` (`sql/src/test/kotlin/.../relationships/`), with a table and a function renamed, a function renamed with a body change, two functions swapped and a table turned into a view (R12, R14).
- **Corpus validation:** `SqlCorpusValidationTest` (`sql/src/test/kotlin/.../psi/sql/`) runs with `-Psmartdiff.validation.sql.corpus=<list>`; `CorpusValidationTest` and the benchmark's real-commit lines accept `.sql` files.
- **Architecture:** `SqlArchitectureTest` checks that only `io.github.agadek.smartdiff.engine.declarations.psi.sql..` uses `com.intellij.sql..` or `com.intellij.database..`, and the root inventory test lists that row as checked by the `sql` subproject.

## 12. Open questions

- **Project dialect:** whether a diff of a file in the project should use the dialect the project maps it to, when the request names the file, instead of §2's choice. §2 gives every text one dialect in every diff and merge, including revisions with no project file.
- **Same-key triggers and indexes:** whether a trigger, and a MySQL or SQL Server index, should be named with its table (`a.audit`), so two tables' triggers of one name never pair by order (§6).
- **`ALTER TABLE`:** whether a migration's `ALTER TABLE … ADD COLUMN` should show as a change to the table it alters when the table is in the same file. Migration files are mostly `ALTER` statements, which stay content of the file region until the corpus validation shows how often this matters.
