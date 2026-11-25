package io.github.agadek.smartdiff.engine.declarations.psi.sql

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.merge.MergeComparison
import io.github.agadek.smartdiff.merge.MergeSide
import io.github.agadek.smartdiff.merge.MergeTexts
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout
import io.github.agadek.smartdiff.ui.merge.MergeOutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeSideName
import java.util.concurrent.Executors

class SqlDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports("SQL") }

    private fun compare(old: String, new: String): ComparisonResult = ReadAction.compute(ThrowableComputable {
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        engine.compare(ComparisonRequest(oldText = old, newText = new, languageId = "SQL", fileName = "file.sql"), Cancellation.NONE)
    })

    /** The declaration tree of [text], parsed in the dialect R3 chooses for it alone. */
    private fun tree(text: String): DeclarationTree = ReadAction.compute(ThrowableComputable {
        val project = ProjectManager.getInstance().defaultProject
        val provider = provider()
        val dialect = provider.dialect(project, listOf(text), "file.sql")
        provider.build(provider.parse(project, text, "file.sql", dialect), Cancellation.NONE)
    })

    private fun dialectOf(vararg texts: String): String? = ReadAction.compute(ThrowableComputable {
        provider().dialect(ProjectManager.getInstance().defaultProject, texts.toList(), "file.sql")?.id
    })

    private fun changes(result: ComparisonResult): List<ChangeNode> = buildList {
        fun visit(node: ChangeNode) {
            add(node)
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
    }

    private fun name(node: ChangeNode): String = (node.subject as Subject.Declaration).name

    private fun reported(result: ComparisonResult): List<ChangeNode> =
        changes(result).filter { it.presence != Presence.MATCHED || it.content != Content.UNCHANGED }

    /** Every declaration's range, own tokens and file region tokens are slices of [text] (details §9). */
    private fun assertOffsets(text: String, tree: DeclarationTree = tree(text)) {
        for (decl in tree.all) {
            assertTrue("range of ${decl.name} inside the text", decl.range.start >= 0 && decl.range.end <= text.length && decl.range.start < decl.range.end)
            assertEquals("tokens and starts of ${decl.name}", decl.ownTokens.size, decl.ownTokenStarts.size)
            decl.ownTokens.zip(decl.ownTokenStarts).forEach { (token, start) ->
                assertEquals("token at $start in ${decl.name}", text.substring(start, start + token.text.length), token.text)
            }
            for (child in decl.children) {
                assertTrue("${child.name} inside ${decl.name}", child.range.start >= decl.range.start && child.range.end <= decl.range.end)
            }
        }
        tree.fileRegion.tokens.zip(tree.fileRegion.tokenStarts).forEach { (token, start) ->
            assertEquals("file region token at $start", text.substring(start, start + token.text.length), token.text)
        }
    }

    /** [assertOffsets] on [text] and on the same text with CRLF line ends (details §9). */
    private fun assertOffsetsLfAndCrlf(text: String) {
        assertOffsets(text)
        val crlf = text.replace("\n", "\r\n")
        assertOffsets(crlf)
        assertEquals("same declarations on LF and CRLF", tree(text).all.map { it.kind to it.name }, tree(crlf).all.map { it.kind to it.name })
    }

    private fun slice(text: String, decl: Declaration): String = text.substring(decl.range.start, decl.range.end)

    private val schema = """
        -- Orders placed by customers.
        CREATE TABLE orders (
            id INTEGER PRIMARY KEY,
            -- The customer who placed it.
            customer_id INTEGER NOT NULL REFERENCES customers (id),
            total DECIMAL(10, 2) DEFAULT 0,
            CONSTRAINT orders_total_positive CHECK (total >= 0)
        );

        CREATE INDEX orders_customer ON orders (customer_id);

        CREATE VIEW big_orders AS
            SELECT id, total FROM orders WHERE total > 100;

        INSERT INTO orders (id, customer_id, total) VALUES (1, 2, 3.5);
    """.trimIndent() + "\n"

    private val postgres = """
        CREATE SCHEMA billing;

        CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');

        CREATE SEQUENCE billing.invoice_ids START WITH 1;

        CREATE TABLE billing.invoices (
            id BIGSERIAL PRIMARY KEY,
            amount NUMERIC(12, 2) NOT NULL
        );

        CREATE OR REPLACE FUNCTION billing.add(a INTEGER, b INTEGER DEFAULT 0) RETURNS INTEGER AS ${'$'}${'$'}
        BEGIN
            RETURN a + b;
        END;
        ${'$'}${'$'} LANGUAGE plpgsql;

        CREATE FUNCTION billing.add(a TEXT, b TEXT) RETURNS TEXT AS 'select a || b' LANGUAGE sql;

        CREATE PROCEDURE billing.close_month(IN month DATE, OUT closed INTEGER)
        LANGUAGE plpgsql AS ${'$'}${'$'}
        BEGIN
            closed := 1;
        END;
        ${'$'}${'$'};

        CREATE TRIGGER invoices_audit AFTER INSERT ON billing.invoices
            FOR EACH ROW EXECUTE FUNCTION billing.audit();

        CREATE MATERIALIZED VIEW billing.totals AS SELECT sum(amount) FROM billing.invoices;

        COMMENT ON TABLE billing.invoices IS 'Invoices';

        DROP TABLE legacy;
    """.trimIndent() + "\n"

    private val mysql = """
        CREATE TABLE `users` (
            `id` INT NOT NULL AUTO_INCREMENT,
            `email` VARCHAR(255) NOT NULL,
            PRIMARY KEY (`id`),
            UNIQUE KEY `users_email` (`email`)
        ) ENGINE=InnoDB;

        CREATE FUNCTION twice(x INT) RETURNS INT DETERMINISTIC RETURN x * 2;
    """.trimIndent() + "\n"

    fun `test R1 registers the SQL declaration provider when the plugin is present`() {
        // languages/20-sql R1
        assertNotNull("the Database Tools and SQL plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database")))
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("SQL") }
        assertEquals("one SQL provider, from smartdiff-sql.xml", 1, providers.size)
        assertTrue(providers.single() is SqlDeclarationProvider)
        assertEquals(GuardedFeature.SQL_PROVIDER, providers.single().guardedFeature)
    }

    fun `test R2 supports SQL files when the plugin is present`() {
        // languages/20-sql R2
        assertTrue(provider().supports("SQL"))
        assertFalse(provider().supports("PostgreSQL"))
        val result = compare("CREATE TABLE a (x INT);\n", "CREATE TABLE a (x BIGINT);\n")
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single().content)
        assertOffsetsLfAndCrlf(schema)
        assertOffsetsLfAndCrlf(postgres)
        assertOffsetsLfAndCrlf(mysql)
    }

    fun `test R3 both sides are parsed in the first dialect that reads them without errors`() {
        // languages/20-sql R3
        assertEquals("PostgreSQL", dialectOf(schema))
        assertEquals("PostgreSQL", dialectOf(postgres))
        assertEquals("MySQL", dialectOf(mysql))
        assertEquals("one dialect for both sides", "MySQL", dialectOf("CREATE TABLE a (x INT);\n", mysql))
        assertEquals(Status.ANALYZED, compare(mysql, mysql.replace("twice", "double_it")).status)
        assertEquals(Status.ANALYZED, compare(postgres, postgres.replace("RETURN a + b", "RETURN b + a")).status)
        assertNotNull("text no dialect reads still gets the dialect with the fewest errors", dialectOf("CREATE TABLE ( ;\n"))
    }

    fun `test R4 R5 create statements are declarations named by their qualified names`() {
        // languages/20-sql R4, R5
        assertEquals(
            listOf(
                DeclarationKind.SCHEMA to "billing",
                DeclarationKind.TYPE to "mood",
                DeclarationKind.SEQUENCE to "billing.invoice_ids",
                DeclarationKind.TABLE to "billing.invoices",
                DeclarationKind.FUNCTION to "billing.add",
                DeclarationKind.FUNCTION to "billing.add",
                DeclarationKind.PROCEDURE to "billing.close_month",
                DeclarationKind.TRIGGER to "invoices_audit",
                DeclarationKind.VIEW to "billing.totals",
            ),
            tree(postgres).topLevel.map { it.kind to it.name },
        )
        assertEquals(
            listOf(DeclarationKind.TABLE to "orders", DeclarationKind.INDEX to "orders_customer", DeclarationKind.VIEW to "big_orders"),
            tree(schema).topLevel.map { it.kind to it.name },
        )
        assertEquals("identifier quotes are left out", listOf("users", "twice"), tree(mysql).topLevel.map { it.name })
        assertEquals("quoted and unquoted names are one declaration", emptyList<ChangeNode>(), reported(compare("CREATE TABLE orders (id INT);\n", "CREATE TABLE \"orders\" (id INT);\n")).filter { it.presence != Presence.MATCHED })
    }

    fun `test R6 columns are members of their table`() {
        // languages/20-sql R6
        val orders = tree(schema).topLevel.first()
        assertEquals(listOf(DeclarationKind.COLUMN to "id", DeclarationKind.COLUMN to "customer_id", DeclarationKind.COLUMN to "total"), orders.children.map { it.kind to it.name })
        assertEquals(listOf("id", "email"), tree(mysql).topLevel.first().children.map { it.name })
        val added = compare(schema, schema.replace("    total DECIMAL(10, 2) DEFAULT 0,\n", "    total DECIMAL(10, 2) DEFAULT 0,\n    placed_at TIMESTAMP,\n"))
        assertEquals(listOf("placed_at" to Presence.ADDED), reported(added).filter { it.children.isEmpty() }.map { name(it) to it.presence })
        val retyped = compare(schema, schema.replace("total DECIMAL(10, 2)", "total DECIMAL(12, 2)"))
        assertEquals(listOf("total"), reported(retyped).filter { it.children.isEmpty() }.map { name(it) })
    }

    fun `test R7 other statements constraints bodies and unnamed indexes are not declarations`() {
        // languages/20-sql R7
        val names = tree(postgres).all.map { it.name }
        assertFalse("DROP, COMMENT ON and routine bodies", names.any { it == "legacy" || it == "closed" })
        assertFalse("table constraints", tree(schema).all.any { it.name == "orders_total_positive" })
        assertTrue("an unnamed index", tree("CREATE TABLE t (c INT);\n\nCREATE INDEX ON t (c);\n").topLevel.map { it.name } == listOf("t"))
        val insert = compare(schema, schema.replace("VALUES (1, 2, 3.5)", "VALUES (1, 2, 4.5)"))
        assertTrue("an INSERT is content of the file region", reported(insert).none { it.subject is Subject.Declaration })
        assertTrue("…which still reports the change", insert.hasReportedChanges)
    }

    fun `test R8 ranges include the comments above and the semicolon or comma after`() {
        // languages/20-sql R8
        val orders = tree(schema).topLevel.first()
        val text = slice(schema, orders)
        assertTrue(text.startsWith("-- Orders placed by customers.\nCREATE TABLE orders ("))
        assertTrue(text.endsWith(");"))
        val customer = orders.children.single { it.name == "customer_id" }
        assertEquals("-- The customer who placed it.\n    customer_id INTEGER NOT NULL REFERENCES customers (id),", slice(schema, customer))
        assertTrue("the last column has no comma", slice(schema, orders.children.last()).endsWith("DEFAULT 0,"))
        val commented = compare(schema, schema.replace("-- The customer who placed it.", "-- The customer who ordered."))
        assertEquals(listOf("customer_id"), reported(commented).filter { it.children.isEmpty() }.map { name(it) })
        val view = tree(schema).topLevel.single { it.name == "big_orders" }
        assertTrue(slice(schema, view).endsWith("total > 100;"))
        val detached = tree("-- header\n\nCREATE TABLE a (x INT);\n").topLevel.single()
        assertEquals("a comment after a blank line stays in the file region", "CREATE TABLE a (x INT);", slice("-- header\n\nCREATE TABLE a (x INT);\n", detached))
    }

    fun `test R9 signatures are parameter types in order`() {
        // languages/20-sql R9
        val routines = tree(postgres).topLevel.filter { it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.PROCEDURE }
        assertEquals(listOf("INTEGER,INTEGER", "TEXT,TEXT", "DATE,INTEGER"), routines.map { it.signature })
        assertEquals(listOf("INT"), tree(mysql).topLevel.filter { it.kind == DeclarationKind.FUNCTION }.map { it.signature })
        assertEquals("NUMERIC(12,2)", tree("CREATE FUNCTION f(a NUMERIC(12, 2)) RETURNS INT AS 'select 1' LANGUAGE sql;\n").topLevel.single().signature)
        val retyped = compare(postgres, postgres.replace("billing.add(a TEXT, b TEXT)", "billing.add(a TEXT, b VARCHAR)"))
        assertTrue("a parameter type is a signature change", reported(retyped).any { name(it) == "billing.add" && (it.signatureChanged || it.presence != Presence.MATCHED) })
        val defaulted = compare(postgres, postgres.replace("b INTEGER DEFAULT 0", "b INTEGER DEFAULT 1"))
        val node = reported(defaulted).single()
        assertEquals("a default value changes only the content", "INTEGER,INTEGER", (node.subject as Subject.Declaration).signature)
        val renamedParameter = compare(postgres, postgres.replace("close_month(IN month DATE", "close_month(IN day DATE"))
        assertEquals("DATE,INTEGER", (reported(renamedParameter).single().subject as Subject.Declaration).signature)
    }

    fun `test R10 whitespace outside strings is formatting-only`() {
        // languages/20-sql R10
        val reformatted = schema.replace("CREATE TABLE orders (\n", "CREATE   TABLE orders\n(\n").replace("    id INTEGER PRIMARY KEY,", "    id  INTEGER  PRIMARY KEY ,")
        val result = compare(schema, reformatted)
        assertTrue(changes(result).filter { it.content != Content.UNCHANGED }.all { it.content == Content.FORMATTING_ONLY })
        assertTrue(changes(result).any { it.content == Content.FORMATTING_ONLY })
        val lineBreak = compare("CREATE VIEW v AS SELECT a, b FROM t;\n", "CREATE VIEW v AS\n    SELECT a,\n           b\n    FROM t;\n")
        assertEquals(Content.FORMATTING_ONLY, lineBreak.changes.single().content)
    }

    fun `test R11 whitespace inside strings and quoted identifiers is a change`() {
        // languages/20-sql R11
        val literal = compare("CREATE VIEW v AS SELECT 'a b' AS x;\n", "CREATE VIEW v AS SELECT 'a  b' AS x;\n")
        assertEquals(Content.CHANGED, literal.changes.single().content)
        val quoted = compare("CREATE TABLE t (\"a b\" INT);\n", "CREATE TABLE t (\"a  b\" INT);\n")
        assertTrue(reported(quoted).any { it.content == Content.CHANGED || it.presence != Presence.MATCHED })
        val dollar = compare(postgres, postgres.replace("    RETURN a + b;", "        RETURN a + b;"))
        assertEquals("a dollar-quoted body is a string", Content.CHANGED, reported(dollar).single().content)
    }

    fun `test R12 renames kind changes and moves work for SQL`() {
        // languages/20-sql R12
        val renamed = compare(schema, schema.replace("CREATE TABLE orders", "CREATE TABLE purchases"))
        assertTrue("rename", renamed.relationships.any { RelationshipKind.RENAME in it.kinds })
        val kind = compare(
            "CREATE TABLE totals (id INT, amount INT);\n",
            "CREATE VIEW totals AS SELECT id, amount FROM payments;\n",
        )
        assertTrue("table to view is a kind change: ${kind.changes}", kind.relationships.any { RelationshipKind.KIND_CHANGE in it.kinds })
        assertEquals(DeclarationKind.TABLE, kind.changes.single().kindChangedFrom)
        val routine = compare(
            "CREATE FUNCTION touch(a INTEGER) RETURNS VOID AS 'select 1' LANGUAGE sql;\n",
            "CREATE PROCEDURE touch(a INTEGER) LANGUAGE sql AS 'select 1';\n",
        )
        assertEquals(DeclarationKind.FUNCTION, routine.changes.single().kindChangedFrom)
        val moved = compare(schema, schema.replace("CREATE INDEX orders_customer ON orders (customer_id);\n\n", "") + "\nCREATE INDEX orders_customer ON orders (customer_id);\n")
        assertTrue("moved: ${moved.changes}", changes(moved).any { name(it) == "orders_customer" && it.moved })
    }

    fun `test R13 merge outline and changes tree show SQL through the engine`() {
        // languages/20-sql R13
        val base = "CREATE TABLE t (a INT);\n"
        val left = "CREATE TABLE t (a BIGINT);\n"
        val right = "CREATE TABLE t (a SMALLINT);\n"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
            val joined = ReadAction.compute(ThrowableComputable {
                MergeComparison.compare(engine, MergeTexts(base, left, right, "SQL", "file.sql"), Cancellation.NONE, executor)
            })
            assertTrue(joined.isAnalyzed(MergeSide.LEFT))
            assertTrue(joined.isAnalyzed(MergeSide.RIGHT))
            val model = MergeOutlineModel.build(
                joined, base, left, right,
                MergeSideName("main", "main"), MergeSideName("feature", "feature"),
                MergeOutlineLayout.GROUPED_BY_SIDE,
            )
            assertTrue("merge summary: ${model.summary}", model.summary.contains("changed on both sides"))
            val summary = OutlineModel.summary(compare(base, left), oldText = base, newText = left)
            assertTrue("Changes tree summary: $summary", summary.contains("changed"))
        } finally {
            executor.shutdownNow()
        }
    }

    fun `test parse errors and empty files`() {
        // languages/20-sql R2, details §9
        val added = compare("", "CREATE TABLE a (x INT);\n")
        assertEquals(Status.ANALYZED, added.status)
        assertEquals(Presence.ADDED, added.changes.single().presence)
        val broken = compare(
            "CREATE TABLE a (x INT);\n\nCREATE TABLE b (y INT);\n",
            "CREATE TABLE a (x INT);\n\nCREATE TABLE b (y INT, );\n",
        )
        assertEquals("a parse error in one table leaves the rest analyzed", Status.PARTIAL, broken.status)
        assertTrue(tree("").topLevel.isEmpty())
    }
}
