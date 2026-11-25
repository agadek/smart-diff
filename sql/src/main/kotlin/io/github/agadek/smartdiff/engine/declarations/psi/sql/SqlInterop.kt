package io.github.agadek.smartdiff.engine.declarations.psi.sql

import com.intellij.lang.Language
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlBatchBlock
import com.intellij.sql.psi.SqlColumnDefinition
import com.intellij.sql.psi.SqlCreateIndexStatement
import com.intellij.sql.psi.SqlCreateProcedureStatement
import com.intellij.sql.psi.SqlCreateSchemaStatement
import com.intellij.sql.psi.SqlCreateStatement
import com.intellij.sql.psi.SqlCreateTableStatement
import com.intellij.sql.psi.SqlCreateTriggerStatement
import com.intellij.sql.psi.SqlCreateTypeStatement
import com.intellij.sql.psi.SqlCreateViewStatement
import com.intellij.sql.psi.SqlDefinition
import com.intellij.sql.psi.SqlIdentifier
import com.intellij.sql.psi.SqlRoutineDefinition
import com.intellij.sql.psi.SqlStatement
import com.intellij.sql.psi.SqlTypedDefinition
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The only place that touches the Database Tools and SQL plugin's PSI (languages/20-sql-details §1, §4). Every entry
 * point the provider calls is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine turns into
 * a failed result.
 */
internal object SqlInterop {

    data class Decl(
        val kind: String,
        val name: String,
        val signatureKey: String?,
        val signatureDisplay: String?,
        val members: List<PsiElement>,
        val range: TextRange,
    )

    /** The dialects tried for a file, in order (details §2): PostgreSQL, MySQL, SQLite, SQL Server, Oracle, Generic SQL. */
    val DIALECTS = listOf("PostgreSQL", "MySQL", "SQLite", "TSQL", "Oracle", "GenericSQL")

    private const val DEFAULT_NAME = "file.sql"

    /**
     * The first of [DIALECTS] that parses every one of [texts] without a parse error, or else the one with the fewest
     * parse errors in all of them, the earlier one on a tie (details §2).
     */
    fun dialect(project: Project, texts: List<CharSequence>, fileName: String?): Language? = link {
        var best: Language? = null
        var fewest = Int.MAX_VALUE
        for (id in DIALECTS) {
            ProgressManager.checkCanceled()
            val language = Language.findLanguageByID(id) ?: continue
            val errors = texts.sumOf { errors(parse(project, it, fileName, language)) }
            if (errors == 0) return@link language
            if (errors < fewest) {
                fewest = errors
                best = language
            }
        }
        best
    }

    fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile = link {
        val language = dialect ?: Language.findLanguageByID("GenericSQL") ?: Language.findLanguageByID("SQL")
            ?: error("the SQL language is not registered")
        PsiFileFactory.getInstance(project).createFileFromText(fileName ?: DEFAULT_NAME, language, text, false, false)
            ?: error("PsiFileFactory returned null for SQL")
    }

    /** The number of parse errors in [file]. */
    private fun errors(file: PsiFile): Int = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).size

    /**
     * The file's `CREATE` statements that are declarations (§4), in source order, including those inside a batch or a
     * block the parser made around a parse error; nothing inside another statement.
     */
    fun topLevel(file: PsiFile): List<PsiElement> = link {
        buildList {
            fun visit(element: PsiElement) {
                for (child in children(element)) {
                    when {
                        child is SqlCreateStatement -> if (decl(child) != null) add(child)
                        child is SqlBatchBlock || (child !is SqlStatement && child !is PsiComment && child.firstChild != null) -> visit(child)
                    }
                }
            }
            visit(file)
        }
    }

    fun decl(element: PsiElement): Decl? = link {
        when (element) {
            is SqlColumnDefinition -> name(element)?.let { Decl("COLUMN", it, null, null, emptyList(), extend(element, rangeOf(element), ",")) }
            is SqlCreateStatement -> statementDecl(element)
            else -> null
        }
    }

    private fun statementDecl(statement: SqlCreateStatement): Decl? {
        val kind = when (statement) {
            is SqlCreateTableStatement -> "TABLE"
            is SqlCreateViewStatement -> "VIEW"
            is SqlCreateIndexStatement -> "INDEX"
            is SqlCreateTriggerStatement -> "TRIGGER"
            is SqlCreateProcedureStatement -> if ("FUNCTION" in type(statement)) "FUNCTION" else "PROCEDURE"
            is SqlCreateTypeStatement -> "TYPE"
            is SqlCreateSchemaStatement -> "SCHEMA"
            else -> if ("CREATE_SEQUENCE" in type(statement)) "SEQUENCE" else return null
        }
        val name = name(statement) ?: return null
        val (key, display) = if (statement is SqlRoutineDefinition) parameters(statement) else null to null
        val members = if (statement is SqlCreateTableStatement) columns(statement) else emptyList()
        return Decl(kind, name, key, display, members, extend(statement, rangeOf(statement), ";"))
    }

    /** A table's columns, in source order: those of its element list (§4); constraints and an `AS` query are not read. */
    private fun columns(table: PsiElement): List<PsiElement> = buildList {
        for (child in children(table)) {
            when {
                child is SqlColumnDefinition -> add(child)
                type(child).endsWith("TABLE_ELEMENT_LIST") -> addAll(children(child).filterIsInstance<SqlColumnDefinition>())
            }
        }
    }

    /**
     * The definition's name as written, with its qualifiers and without identifier quotes (§6): the names of the
     * identifiers of its name element, joined by `.`; null for a definition without a name, such as an unnamed index.
     */
    private fun name(definition: SqlDefinition): String? {
        val element = definition.nameElement ?: return null
        val identifiers = if (element is SqlIdentifier) listOf(element) else PsiTreeUtil.findChildrenOfType(element, SqlIdentifier::class.java).toList()
        val parts = identifiers.sortedBy { it.textRange.startOffset }.map { it.name.orEmpty() }
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        return parts.joinToString(".")
    }

    /**
     * Each parameter's type, in order (§5), as a key without whitespace and as a display with single spaces:
     * `INTEGER, NUMERIC(12, 2)`. A parameter without a type, as in a SQLite-like dialect, has an empty type.
     */
    private fun parameters(routine: SqlRoutineDefinition): Pair<String, String> {
        val displays = routine.parameterList?.parameters.orEmpty().map { parameter -> (parameter as? SqlTypedDefinition)?.typeElement?.let(::spaced).orEmpty() }
        return displays.joinToString(",") { it.replace(" ", "") } to displays.joinToString(", ")
    }

    /** [element]'s tokens without comments, with one space where the source has whitespace between them. */
    private fun spaced(element: PsiElement): String = buildString {
        var gap = false
        fun visit(e: PsiElement) {
            var child = e.firstChild
            while (child != null) {
                when {
                    child is PsiWhiteSpace || child is PsiComment -> gap = true
                    child.firstChild == null -> if (child.textLength > 0) {
                        if (gap && isNotEmpty()) append(' ')
                        gap = false
                        append(child.text)
                    }
                    else -> visit(child)
                }
                child = child.nextSibling
            }
        }
        if (element.firstChild == null) append(element.text) else visit(element)
    }

    /** [range] extended to the end of the [separator] after [element], if only whitespace is between them (§7). */
    private fun extend(element: PsiElement, range: TextRange, separator: String): TextRange {
        var sibling = element.nextSibling
        while (sibling is PsiWhiteSpace) sibling = sibling.nextSibling
        val ends = sibling != null && sibling.firstChild == null &&
            (sibling.text == separator || (separator == ";" && type(sibling).endsWith(" DELIM")))
        return if (ends) TextRange(range.start, sibling!!.textRange.endOffset) else range
    }

    /** The element type's name, such as `PG_CREATE_FUNCTION_STATEMENT`. */
    private fun type(element: PsiElement): String = element.node.elementType.toString()

    /**
     * [element]'s range extended back over the comments directly above it (§7): `--`, `#` and `/* */` comments that end
     * on the line before, with no blank line in the run or after it. A comment after code on its line stays outside.
     */
    private fun rangeOf(element: PsiElement): TextRange {
        val text = element.containingFile.viewProvider.contents
        val range = element.textRange
        var start = range.startOffset
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling is PsiComment) {
                val comment = sibling.textRange
                if (lineBreaks(text, comment.endOffset, start) > 1) break
                if (!startsLine(text, comment.startOffset) && !afterComment(sibling)) break
                start = comment.startOffset
            } else if (sibling !is PsiWhiteSpace) {
                break
            }
            sibling = sibling.prevSibling
        }
        return TextRange(start, range.endOffset)
    }

    private fun lineBreaks(text: CharSequence, from: Int, to: Int): Int = (from until to).count { text[it] == '\n' }

    /** Whether only whitespace is between [offset] and the start of its line. */
    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') {
            if (!text[i].isWhitespace()) return false
            i--
        }
        return true
    }

    /** Whether the code before [comment] on its line is another comment, as in `/* a */ /* b */`. */
    private fun afterComment(comment: PsiElement): Boolean {
        var sibling = comment.prevSibling
        while (sibling is PsiWhiteSpace && '\n' !in sibling.text) sibling = sibling.prevSibling
        return sibling is PsiComment
    }

    private fun children(element: PsiElement): List<PsiElement> = generateSequence(element.firstChild) { it.nextSibling }.toList()

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("SQL plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
