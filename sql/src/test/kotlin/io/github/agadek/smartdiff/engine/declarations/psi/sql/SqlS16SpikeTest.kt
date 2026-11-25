// TECHNICAL-SPECIFICATION S16
package io.github.agadek.smartdiff.engine.declarations.psi.sql

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Spike S16: confirms the SQL plugin id, the SQL language and dialect ids, the PSI classes an SQL provider would use,
 * how a text-built file parses in each dialect, where comments sit, and whether in-file references resolve.
 *
 * The test prints its findings as `S16 ...` lines, which the test task copies into the build log.
 */
class SqlS16SpikeTest : BasePlatformTestCase() {

    private val generic = """
        -- Orders placed by customers.
        CREATE TABLE orders (
            id INTEGER PRIMARY KEY,
            -- The customer who placed it.
            customer_id INTEGER NOT NULL REFERENCES customers (id),
            total DECIMAL(10, 2) DEFAULT 0,
            note VARCHAR(200) DEFAULT 'a  b',
            CONSTRAINT orders_total_positive CHECK (total >= 0)
        );

        CREATE INDEX orders_customer ON orders (customer_id);

        CREATE VIEW big_orders AS
            SELECT id, total FROM orders WHERE total > 100;

        CREATE SEQUENCE order_ids START WITH 1;

        ALTER TABLE orders ADD COLUMN placed_at TIMESTAMP;

        INSERT INTO orders (id, customer_id, total) VALUES (1, 2, 3.5);

        CREATE TABLE sales.customers (id INTEGER PRIMARY KEY, name VARCHAR(100));

        DROP TABLE legacy;
    """.trimIndent() + "\n"

    private val postgres = """
        CREATE SCHEMA billing;

        CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');

        CREATE TABLE billing.invoices (
            id BIGSERIAL PRIMARY KEY,
            amount NUMERIC(12, 2) NOT NULL
        );

        CREATE OR REPLACE FUNCTION billing.add(a INTEGER, b INTEGER DEFAULT 0) RETURNS INTEGER AS ${'$'}${'$'}
        BEGIN
            RETURN a + b;
        END;
        ${'$'}${'$'} LANGUAGE plpgsql;

        CREATE FUNCTION add(a TEXT, b TEXT) RETURNS TEXT AS 'select a || b' LANGUAGE sql;

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
    """.trimIndent() + "\n"

    private val mysql = """
        CREATE TABLE `users` (
            `id` INT NOT NULL AUTO_INCREMENT,
            `email` VARCHAR(255) NOT NULL,
            PRIMARY KEY (`id`),
            UNIQUE KEY `users_email` (`email`)
        ) ENGINE=InnoDB;

        DELIMITER //
        CREATE PROCEDURE touch(IN user_id INT)
        BEGIN
            UPDATE users SET email = email WHERE id = user_id;
        END //
        DELIMITER ;

        CREATE FUNCTION twice(x INT) RETURNS INT DETERMINISTIC RETURN x * 2;
    """.trimIndent() + "\n"

    fun `test plugin is loaded and the languages resolve`() {
        for (id in listOf("com.intellij.database", "com.intellij.sql", "intellij.database", "com.intellij.modules.sql")) {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(id))
            println("S16 pluginId=$id present=${plugin != null} name=${plugin?.name} version=${plugin?.version} enabled=${plugin?.isEnabled}")
        }
        val loaded = PluginManagerCore.loadedPlugins.map { it.pluginId.idString }.sorted()
        println("S16 loadedPlugins=${loaded.joinToString(",")}")
        val sql = Language.getRegisteredLanguages().filter { lang ->
            generateSequence(lang) { it.baseLanguage }.any { it.id.contains("sql", ignoreCase = true) } || lang.id.contains("sql", ignoreCase = true)
        }
        for (lang in sql.sortedBy { it.id }) {
            println("S16 language id=${lang.id} class=${lang.javaClass.name} base=${lang.baseLanguage?.id} display=${lang.displayName} fileType=${lang.associatedFileType?.name}")
        }
        val type = FileTypeManager.getInstance().getFileTypeByFileName("x.sql")
        println("S16 fileType for x.sql name=${type.name} class=${type.javaClass.name} language=${(type as? com.intellij.openapi.fileTypes.LanguageFileType)?.language?.id}")
        for (ext in listOf("x.ddl", "x.pgsql", "x.mysql", "x.psql", "x.plsql")) {
            val t = FileTypeManager.getInstance().getFileTypeByFileName(ext)
            println("S16 fileType for $ext name=${t.name} language=${(t as? com.intellij.openapi.fileTypes.LanguageFileType)?.language?.id}")
        }
    }

    fun `test text-built files parse in each dialect`() {
        val cases = listOf(
            Triple("generic-SQL", "SQL", generic),
            Triple("generic-GenericSQL", "GenericSQL", generic),
            Triple("generic-SQL92", "SQL92", generic),
            Triple("generic-PostgreSQL", "PostgreSQL", generic),
            Triple("postgres-PostgreSQL", "PostgreSQL", postgres),
            Triple("postgres-SQL", "SQL", postgres),
            Triple("postgres-GenericSQL", "GenericSQL", postgres),
            Triple("mysql-MySQL", "MySQL", mysql),
            Triple("mysql-SQL", "SQL", mysql),
            Triple("mysql-GenericSQL", "GenericSQL", mysql),
        )
        val outDir = File(System.getProperty("smartdiff.spike.out") ?: "build/sql-spike").apply { mkdirs() }
        for ((label, languageId, text) in cases) {
            val language = Language.findLanguageByID(languageId)
            if (language == null) {
                println("S16 [$label] language $languageId not registered")
                continue
            }
            val file = try {
                PsiFileFactory.getInstance(project).createFileFromText("sample.sql", language, text, false, false)
            } catch (t: Throwable) {
                println("S16 [$label] createFileFromText threw ${t.javaClass.name}: ${t.message}")
                null
            } ?: continue
            println("S16 [$label] file class=${file.javaClass.name} language=${file.language.id} textEqual=${file.text == text} viewProviderLanguages=${file.viewProvider.languages.map { it.id }}")
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            println("S16 [$label] errors count=${errors.size}")
            for (e in errors) println("S16 [$label] error offset=${e.textRange.startOffset} line=${text.substring(0, e.textRange.startOffset).count { it == '\n' } + 1} description=${e.errorDescription}")
            File(outDir, "psi-tree-$label.txt").writeText(DebugUtil.psiToString(file, true, false))

            for (child in generateSequence(file.firstChild) { it.nextSibling }) {
                if (child is PsiWhiteSpace) continue
                println("S16 [$label] top class=${child.javaClass.name} type=${child.node.elementType} range=${child.textRange} text=\"${child.text.lineSequence().first().take(60)}\"")
            }
            if (label.endsWith("-SQL") || label == "postgres-PostgreSQL" || label == "mysql-MySQL") {
                PsiTreeUtil.processElements(file) { element ->
                    if (element !is PsiWhiteSpace && element.firstChild != null) {
                        val cls = element.javaClass
                        val textOk = text.substring(element.textRange.startOffset, element.textRange.endOffset) == element.text
                        val depth = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.count()
                        if (depth <= 4) {
                            println("S16 [$label] element depth=$depth class=${cls.simpleName} type=${element.node.elementType} named=${(element as? PsiNamedElement)?.name} range=${element.textRange} textOk=$textOk text=\"${element.text.lineSequence().first().take(50)}\" interfaces=${allInterfaces(cls).joinToString(",")}")
                            describeExtras(label, element)
                        }
                    }
                    true
                }
                for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
                    val next = PsiTreeUtil.skipWhitespacesForward(comment)
                    println("S16 [$label] comment text=\"${comment.text.lineSequence().first()}\" class=${comment.javaClass.simpleName} parent=${comment.parent?.javaClass?.simpleName} parentRange=${comment.parent?.textRange} next=${next?.javaClass?.simpleName} nextRange=${next?.textRange}")
                }
            }
        }
    }

    private fun allInterfaces(cls: Class<*>): List<String> =
        generateSequence(cls) { it.superclass }.flatMap { c -> c.interfaces.asSequence() }
            .flatMap { i -> sequenceOf(i) + i.interfaces.asSequence() }
            .map { it.simpleName }.filter { it.startsWith("Sql") || it.startsWith("Db") }.distinct().toList()

    /** Accessors found by reflection, so the spike does not compile against SQL PSI types. */
    private fun describeExtras(label: String, element: PsiElement) {
        val getters = listOf("getName", "getNameElement", "getNameIdentifier", "getIdentifyingElement", "getQualifiedName",
            "getDasObject", "getKind", "getTargetKind", "getObjectKind", "getDefinitionKind", "getColumns", "getColumnList",
            "getTableElementList", "getDeclaredColumns", "getParameterList", "getParameters", "getRoutineKind",
            "getDataType", "getTypeElement", "getStatement", "getBody", "getRoutineBody", "getTarget", "getTargetReference")
        for (getter in getters) {
            val method = element.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } ?: continue
            val value = try { method.invoke(element) } catch (t: Throwable) { "threw ${t.javaClass.simpleName}" }
            val shown = when (value) {
                is PsiElement -> "${value.javaClass.simpleName}(\"${(value.text ?: "<null text>").lineSequence().first().take(40)}\" ${value.textRange})"
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { (it as? PsiElement)?.let { e -> "${e.javaClass.simpleName}(\"${(e.text ?: "<null text>").take(30)}\")" } ?: it.toString() }
                is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { (it as? PsiElement)?.let { e -> "${e.javaClass.simpleName}(\"${(e.text ?: "<null text>").take(30)}\")" } ?: it.toString() }
                else -> value?.toString()?.take(80)
            }
            println("S16 [$label]   $getter -> $shown")
        }
    }

    fun `test in-file reference resolves`() {
        val language = Language.findLanguageByID("SQL") ?: return
        val file = PsiFileFactory.getInstance(project).createFileFromText("sample.sql", language, generic, false, false) ?: return
        for (needle in listOf("ON orders (" to "ON ".length, "(customer_id)" to 1, "FROM orders" to "FROM ".length)) {
            val offset = generic.indexOf(needle.first) + needle.second
            val reference = file.findReferenceAt(offset)
            val target = try { reference?.resolve() } catch (t: Throwable) { println("S16 resolve threw ${t.javaClass.name}: ${t.message}"); null }
            println("S16 reference at \"${needle.first}\" class=${reference?.javaClass?.name} -> class=${target?.javaClass?.name} name=${(target as? PsiNamedElement)?.name} offset=${target?.textRange?.startOffset}")
        }
    }
}
