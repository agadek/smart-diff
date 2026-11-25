# SQL

Status: implemented, 2026-11-25

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports SQL files, through the parser of the JetBrains Database Tools and SQL plugin, when the IDE has that plugin.

**Use case:** A developer reviews a change to a schema or migration file in DataGrip, or in IntelliJ IDEA or another IDE with the Database Tools and SQL plugin, and sees which tables, columns, views, indexes, functions, procedures and triggers changed, without reading every changed line.

## Requirements

- **R1** SQL support works when the IDE has the Database Tools and SQL plugin, and the plugin loads without it.
- **R2** Files of the SQL file type, such as `.sql` and `.ddl` files, are a [supported language](../../GLOSSARY.md#supported-language) when the plugin is present.
- **R3** Both sides of a file are parsed in one [SQL dialect](../../GLOSSARY.md#sql-dialect): the first of PostgreSQL, MySQL, SQLite, Microsoft SQL Server, Oracle and Generic SQL that parses both sides without a [parse error](../../GLOSSARY.md#parse-error), or else the one with the fewest parse errors.
- **R4** `CREATE` statements of tables, views, materialized views, indexes, sequences, functions, procedures, triggers, types and schemas are [declarations](../../GLOSSARY.md#declaration).
- **R5** A declaration is named by its object's name as written, with its schema or database qualifier (`billing.invoices`) and without identifier quotes.
- **R6** The columns of a `CREATE TABLE` statement are declarations and members of their table.
- **R7** Other statements, such as `ALTER`, `DROP`, `INSERT`, `SELECT`, `GRANT`, `COMMENT ON` and `CREATE` statements of other objects, are not declarations; nor are table constraints, a routine's body or an unnamed index.
- **R8** A statement's range includes the comment lines directly above it and the `;` that ends it; a column's range includes the comment lines directly above it and the `,` after it.
- **R9** A function's or procedure's [signature](../../GLOSSARY.md#signature) is the type of each parameter, in order, without parameter names, modes, default values and the return type.
- **R10** Whitespace outside string literals and quoted identifiers is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R11** A whitespace change inside a string literal or a quoted identifier is a change.
- **R12** Rename candidates, kind changes between a table and a view and between a function and a procedure, and moves work for SQL as for the other languages.
- **R13** The merge outline and the Changes tree show SQL files as they show Java files.
- **R14** SQL has golden fixtures, merge fixtures and relationship benchmark fixtures, as the other languages do.

## Out of scope

- Choosing the dialect from the project's SQL dialect settings; a diff of a revision has no project file to read them from, and R3 gives both sides one dialect.
- Changes made by `ALTER TABLE`, such as an added column, as changes to the table they alter; `ALTER` statements are content of the file region.
- Extraction detection, inlining detection and moved code inside routine bodies, and grouping renames with their usages.
- Statements inside a routine body, a PostgreSQL `CREATE SCHEMA … CREATE TABLE` element, or a MySQL `DELIMITER` block as separate declarations.

## References

- [Details](20-sql-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md)
- [Format coverage plan, phase B6 and F3](../../FORMAT-COVERAGE.md#5-phases)
- [Roadmap: More languages](../../ROADMAP.md#more-languages)
