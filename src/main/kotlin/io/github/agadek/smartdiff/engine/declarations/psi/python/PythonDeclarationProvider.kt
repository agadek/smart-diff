package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDecoratable
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyNamedParameter
import com.jetbrains.python.psi.PyParameter
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PySingleStarParameter
import com.jetbrains.python.psi.PySlashParameter
import com.jetbrains.python.psi.PyStarExpression
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyStatementList
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyTupleExpression
import com.jetbrains.python.psi.PyTupleParameter
import com.jetbrains.python.psi.PyTypeAliasStatement
import com.jetbrains.python.psi.PyTypeDeclarationStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Python declarations (languages/04-python-details.md), via the Python plugin's PSI. Uses PSI only, never type inference
 * or resolve. Definitions inside function bodies, lambdas, comprehensions, instance attributes, imports and other
 * statements are part of the declaration that contains them or of the file region.
 */
class PythonDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INDENTATION_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == PythonLanguage.getInstance().id

    override fun dialect(project: Project, texts: List<CharSequence>, fileName: String?): Language? = null

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText(fileName ?: "SmartDiff.py", PythonLanguage.getInstance(), text, false, false)

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INDENTATION_SIGNIFICANT

    override fun topLevel(file: PsiFile): List<PsiElement> = members(file)

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is PyClass -> element.name?.takeIf { it.isNotEmpty() }?.let {
            DeclarationInfo(
                DeclarationKind.CLASS, it, signature = null,
                members = members(element.statementList),
                range = declarationRange(element),
            )
        }
        is PyFunction -> function(element)
        is PyTypeAliasStatement -> element.name?.takeIf { it.isNotEmpty() }?.let {
            DeclarationInfo(DeclarationKind.TYPE_ALIAS, it, signature = null, range = declarationRange(element))
        }
        is PyTypeDeclarationStatement -> annotatedVariable(element)
        is PyTargetExpression -> variableTarget(element)
        is PyTupleExpression, is PyParenthesizedExpression -> tupleVariable(element)
        else -> null
    }

    /**
     * The elements of [container] that may be declarations, in source order. Definitions in compound statements at module
     * level and in class bodies belong to the enclosing container (R7); several assignment targets are listed one by one.
     */
    private fun members(container: PsiElement?): List<PsiElement> = statements(container).flatMap { statement ->
        when (statement) {
            is PyClass, is PyFunction, is PyTypeAliasStatement, is PyTypeDeclarationStatement -> listOf(statement)
            is PyAssignmentStatement -> targets(statement)
            is PyIfStatement -> statement.ifPart?.statementList?.let(::members).orEmpty() +
                statement.elifParts.flatMap { it.statementList?.let(::members).orEmpty() } +
                statement.elsePart?.statementList?.let(::members).orEmpty()
            is PyTryExceptStatement -> statement.tryPart.statementList?.let(::members).orEmpty() +
                statement.exceptParts.flatMap { it.statementList?.let(::members).orEmpty() } +
                statement.elsePart?.statementList?.let(::members).orEmpty() +
                statement.finallyPart?.statementList?.let(::members).orEmpty()
            is PyWithStatement -> statement.statementList?.let(::members).orEmpty()
            is PyForStatement -> statement.forPart.statementList?.let(::members).orEmpty() +
                statement.elsePart?.statementList?.let(::members).orEmpty()
            is PyWhileStatement -> statement.whilePart.statementList?.let(::members).orEmpty() +
                statement.elsePart?.statementList?.let(::members).orEmpty()
            else -> emptyList()
        }
    }

    private fun statements(container: PsiElement?): List<PyStatement> = when (container) {
        null -> emptyList()
        is PyFile -> container.statements.toList()
        is PyStatementList -> container.statements.toList()
        else -> PsiTreeUtil.getChildOfType(container, PyStatementList::class.java)?.statements?.toList().orEmpty()
    }

    private fun function(function: PyFunction): DeclarationInfo? {
        val name = function.name?.takeIf { it.isNotEmpty() } ?: return null
        val parts = parameterParts(function.parameterList?.parameters.orEmpty().toList())
        val signature = parts.joinToString(",")
        val displaySignature = parts.joinToString(", ")
        return DeclarationInfo(
            DeclarationKind.FUNCTION,
            name,
            signature,
            displaySignature = displaySignature,
            body = function.statementList,
            range = declarationRange(function),
        )
    }

    /**
     * The range of a declaration: from its first decorator or own-line leading comments (R9) to its end.
     * Only comments that start their own line directly above the declaration, with no blank line between, are included.
     */
    private fun declarationRange(element: PsiElement, baseStart: Int = elementRangeStart(element), baseEnd: Int = element.textRange.endOffset): TextRange =
        TextRange(leadingCommentStart(element, baseStart), baseEnd)

    private fun elementRangeStart(element: PsiElement): Int =
        (element as? PyDecoratable)?.decoratorList?.textRange?.startOffset ?: element.textRange.startOffset

    /** Start of own-line comments directly above [element], extending [baseStart] upward when present (R9). */
    private fun leadingCommentStart(element: PsiElement, baseStart: Int): Int {
        val fileText = element.containingFile.text
        var start = baseStart
        var prev = element.prevSibling
        var commentStart: Int? = null
        while (prev is PsiWhiteSpace || prev is PsiComment) {
            if (prev is PsiComment) {
                if (!startsOwnLine(fileText, prev.textRange.startOffset)) break
                commentStart = prev.textRange.startOffset
                prev = prev.prevSibling
                continue
            }
            val breaks = prev.text.count { it == '\n' }
            // A blank line (more than one line break) separates comments from the declaration.
            if (breaks > 1) break
            prev = prev.prevSibling
        }
        if (commentStart != null && commentStart < start) start = commentStart
        return start
    }

    private fun startsOwnLine(fileText: CharSequence, offset: Int): Boolean {
        if (offset <= 0) return true
        val lineStart = fileText.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        for (i in lineStart until offset) {
            val c = fileText[i]
            if (c != ' ' && c != '\t' && c != '\r') return false
        }
        return true
    }

    /** Parameter names with markers (R11), in order; without annotations and defaults. */
    private fun parameterParts(parameters: List<PyParameter>): List<String> = parameters.mapNotNull { parameter ->
        when (parameter) {
            is PySlashParameter -> "/"
            is PySingleStarParameter -> "*"
            is PyNamedParameter -> namedParameter(parameter)
            is PyTupleParameter -> tupleParameter(parameter)
            else -> parameter.text.trim().ifEmpty { null }
        }
    }

    private fun namedParameter(parameter: PyNamedParameter): String? {
        val name = parameter.name ?: return null
        return when {
            parameter.isKeywordContainer -> "**$name"
            parameter.isPositionalContainer -> "*$name"
            else -> name
        }
    }

    private fun tupleParameter(tuple: PyTupleParameter): String =
        "(" + tuple.contents.joinToString(", ") { p ->
            when (p) {
                is PyNamedParameter -> namedParameter(p) ?: p.name ?: ""
                is PyTupleParameter -> tupleParameter(p)
                else -> p.text.trim()
            }
        } + ")"

    /** Module- or class-level assignment/annotation targets that bind a name (R4, R5, R8). */
    private fun targets(assignment: PyAssignmentStatement): List<PsiElement> {
        if (assignment is PyAugAssignmentStatement) return emptyList()
        val targets = assignment.targets
        if (targets.isEmpty()) return emptyList()
        if (targets.size == 1) {
            val target = targets.single()
            return when {
                isTupleTarget(target) -> listOf(target)
                isNameTarget(target) -> listOf(target)
                else -> emptyList()
            }
        }
        // Tuple unpacking `a, b = ...` is one declaration; chained `a = b = 0` gives separate variables.
        val lhs = assignment.leftHandSideExpression
        if (lhs != null && isTupleTarget(lhs)) return listOf(lhs)
        return targets.filter(::isNameTarget)
    }

    private fun isNameTarget(expression: PyExpression): Boolean =
        expression is PyTargetExpression && !expression.name.isNullOrEmpty() && expression.qualifier == null

    private fun isTupleTarget(expression: PyExpression): Boolean = when (expression) {
        is PyTupleExpression -> true
        is PyParenthesizedExpression -> expression.containedExpression is PyTupleExpression
        else -> false
    }

    private fun annotatedVariable(statement: PyTypeDeclarationStatement): DeclarationInfo? {
        val target = statement.target
        val name = when (target) {
            is PyTargetExpression -> if (target.qualifier == null) target.name else null
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return null
        return DeclarationInfo(
            DeclarationKind.VARIABLE, name, signature = null, group = statement,
            range = declarationRange(statement),
        )
    }

    private fun variableTarget(element: PyTargetExpression): DeclarationInfo? {
        val name = element.name?.takeIf { it.isNotEmpty() } ?: return null
        if (element.qualifier != null) return null
        val assignment = PsiTreeUtil.getParentOfType(element, PyAssignmentStatement::class.java) ?: return null
        val targets = assignment.targets.toList()
        // A single name target (possibly annotated): the declaration owns the whole assignment statement.
        if (targets.size == 1 && isNameTarget(targets.single())) {
            return DeclarationInfo(
                DeclarationKind.VARIABLE, name, signature = null, group = assignment,
                range = declarationRange(assignment),
            )
        }
        // Part of `a = b = 0`; only the first target takes leading comments (R9).
        if (targets.size > 1) {
            val index = targets.indexOf(element)
            if (index >= 0) {
                val end = if (index == targets.lastIndex) assignment.textRange.endOffset else element.textRange.endOffset
                val start = if (index == 0) {
                    leadingCommentStart(assignment, assignment.textRange.startOffset)
                } else {
                    element.textRange.startOffset
                }
                return DeclarationInfo(DeclarationKind.VARIABLE, name, signature = null, group = assignment, range = TextRange(start, end))
            }
        }
        return null
    }

    private fun tupleVariable(element: PsiElement): DeclarationInfo? {
        val expression = element as? PyExpression ?: return null
        if (!isTupleTarget(expression)) return null
        val assignment = PsiTreeUtil.getParentOfType(element, PyAssignmentStatement::class.java) ?: return null
        val tuple = when (element) {
            is PyTupleExpression -> element
            is PyParenthesizedExpression -> element.containedExpression as? PyTupleExpression
            else -> null
        } ?: return null
        val name = tupleName(tuple) ?: return null
        return DeclarationInfo(
            DeclarationKind.VARIABLE, name, signature = null, group = assignment,
            range = declarationRange(assignment),
        )
    }

    private fun tupleName(tuple: PyTupleExpression): String? {
        val names = tuple.elements.map { targetElementName(it) ?: return null }
        return if (names.isEmpty()) null else names.joinToString(", ", "(", ")")
    }

    private fun targetElementName(element: PyExpression): String? = when (element) {
        is PyTargetExpression -> element.name?.let { if (element.qualifier == null) it else null }
        is PyStarExpression -> element.expression?.let { inner -> targetElementName(inner)?.let { "*$it" } ?: inner.text.trim().let { if (it.startsWith("*")) it else "*$it" } }
        is PyTupleExpression -> tupleName(element)
        is PyParenthesizedExpression -> (element.containedExpression as? PyTupleExpression)?.let(::tupleName)
            ?: (element.containedExpression as? PyExpression)?.let(::targetElementName)
        else -> null
    }

    override fun isParseError(error: PsiErrorElement): Boolean = true
}
