package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.psi.api.base.ScAnnotation
import org.jetbrains.plugins.scala.lang.psi.api.base.ScFieldId
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPattern
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScEnumCase
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScEnumCases
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScExtension
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariable
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDeclaration
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDefinition
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameterClause
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameterType
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameters
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateParents
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScEnum
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScGiven
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScGivenAlias
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScGivenDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait

/**
 * Scala declarations (technical specification §6.8), in the Scala 2 or Scala 3 dialect (§6.2). Uses PSI only, never
 * type inference or resolve. Local definitions, anonymous classes, statements in a body, and the primary constructor
 * and its parameters are part of the declaration that contains them.
 *
 * The file's language alone decides the dialect: the Scala plugin hard-wires its parser and lexer to `ScalaLanguage` or
 * `Scala3Language`, and a file built from text has no module whose settings could change that.
 */
class ScalaDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    // A .scala file's type gives `Scala` even in a Scala 3 module, so requests never carry the `Scala 3` id.
    override fun supports(languageId: String): Boolean = languageId == ScalaLanguage.INSTANCE.id

    /**
     * Scala 3, unless Scala 2 parses both sides with fewer errors. The Scala 3 parser also accepts most Scala 2 code,
     * procedure syntax included, so a tie goes to Scala 3; Scala 2 wins on code Scala 3 dropped, such as `enum` as a name.
     */
    override fun dialect(project: Project, oldText: CharSequence, newText: CharSequence, fileName: String?): Language {
        val scala3 = errors(project, oldText, Scala3Language.INSTANCE) + errors(project, newText, Scala3Language.INSTANCE)
        if (scala3 == 0) return Scala3Language.INSTANCE
        val scala2 = errors(project, oldText, ScalaLanguage.INSTANCE) + errors(project, newText, ScalaLanguage.INSTANCE)
        return if (scala2 < scala3) ScalaLanguage.INSTANCE else Scala3Language.INSTANCE
    }

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("SmartDiff.scala", dialect ?: Scala3Language.INSTANCE, text, false, false)

    /**
     * Scala 3 optional braces make indentation significant, so a change in indentation is never formatting-only. Scala 2,
     * like Kotlin, infers semicolons from line breaks.
     */
    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy =
        if (file.language == Scala3Language.INSTANCE) WhitespacePolicy.INDENTATION_SIGNIFICANT else WhitespacePolicy.LINE_BREAKS_SIGNIFICANT

    // Definitions in package clauses, braced or not, are top-level declarations, like Scala 3 top-level definitions.
    override fun topLevel(file: PsiFile): List<PsiElement> = members(file)

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        // Enum cases are classes or objects too; `case A, B` gives one declaration per case.
        is ScEnumCase -> name(element)?.let { name ->
            val cases = element.parent as? ScEnumCases ?: return null
            part(element, cases, ScalaInterop.list(cases.declaredElements()), DeclarationKind.ENUM_CONSTANT, name)
        }
        is ScEnum -> type(element, DeclarationKind.ENUM)
        is ScTrait -> type(element, DeclarationKind.TRAIT)
        // A package object's name is its package's name.
        is ScObject -> type(element, DeclarationKind.OBJECT)
        is ScClass -> type(element, DeclarationKind.CLASS)
        // Before functions: a given alias is a function too.
        is ScGiven -> given(element)
        is ScFunction -> function(element)
        is ScExtension -> extension(element)
        is ScValueOrVariable -> property(element)
        // One of several properties declared together, listed by members: `val a, b = 0` or `val a, b: Int`.
        is ScPattern -> PsiTreeUtil.getParentOfType(element, ScValueOrVariableDefinition::class.java)?.let { definition ->
            propertyName(ScalaInterop.list(element.bindings()).map { it.name() })?.let { name ->
                part(element, definition, ScalaInterop.list(definition.pList().patterns()), DeclarationKind.PROPERTY, name)
            }
        }
        is ScFieldId -> PsiTreeUtil.getParentOfType(element, ScValueOrVariableDeclaration::class.java)?.let { declaration ->
            name(element)?.let { name ->
                part(element, declaration, ScalaInterop.list(declaration.getIdList().fieldIds()), DeclarationKind.PROPERTY, name)
            }
        }
        is ScTypeAlias -> name(element)?.let { DeclarationInfo(DeclarationKind.TYPE_ALIAS, it, signature = null) }
        else -> null
    }

    /**
     * The elements of [container] that may be declarations, in source order. Package clauses are looked through; enum
     * cases and properties declared together are listed one by one, since each is a declaration.
     */
    private fun members(container: PsiElement?): List<PsiElement> = container?.children.orEmpty().flatMap { child ->
        when (child) {
            is ScPackaging -> members(child)
            is ScEnumCases -> ScalaInterop.list(child.declaredElements())
            is ScValueOrVariableDefinition -> ScalaInterop.list(child.pList().patterns()).takeIf { it.size > 1 } ?: listOf(child)
            is ScValueOrVariableDeclaration -> ScalaInterop.list(child.getIdList().fieldIds()).takeIf { it.size > 1 } ?: listOf(child)
            else -> listOf(child)
        }
    }

    private fun type(definition: ScTemplateDefinition, kind: DeclarationKind): DeclarationInfo? =
        name(definition)?.let { DeclarationInfo(kind, it, signature = null, members(body(definition))) }

    private fun body(definition: ScTemplateDefinition): PsiElement? =
        definition.extendsBlock()?.let { ScalaInterop.orNull(it.templateBody()) }

    private fun function(function: ScFunction): DeclarationInfo? {
        val clauses = clauses(function)
        val signature = signature(clauses, Signatures::text)
        val label = signature(clauses, Signatures::scalaLabel)
        if (function.isConstructor) return DeclarationInfo(DeclarationKind.CONSTRUCTOR, "", signature, displaySignature = label)
        return name(function)?.let { DeclarationInfo(DeclarationKind.FUNCTION, it, signature, displaySignature = label) }
    }

    /**
     * An anonymous given is named by its type, normalized like a signature, not by the compiler's synthesized name
     * (`given_Ordering_Int`), which is harder to read and has changed between compiler versions.
     */
    private fun given(given: ScGiven): DeclarationInfo? {
        val name = if (ScalaInterop.orNull(given.nameElement()) != null) {
            name(given)
        } else {
            val type = when (given) {
                is ScGivenAlias -> ScalaInterop.orNull(given.typeElement())
                is ScGivenDefinition -> PsiTreeUtil.getChildOfType(given.extendsBlock(), ScTemplateParents::class.java)
                else -> null
            }
            type?.let { Signatures.text(it, ::isAnnotation) }?.takeIf { it.isNotEmpty() }
        } ?: return null
        val usings = clauses(given).filter { it.hasUsingKeyword() }
        val members = if (given is ScGivenDefinition) members(body(given)) else emptyList()
        return DeclarationInfo(
            DeclarationKind.GIVEN, name, signature(usings, Signatures::text), members,
            displaySignature = signature(usings, Signatures::scalaLabel),
        )
    }

    /** Named by its receiver's type; its methods are its children, so a change to the receiver shows on one node. */
    private fun extension(extension: ScExtension): DeclarationInfo? {
        val receiver = ScalaInterop.orNull(extension.targetTypeElement()) ?: return null
        val usings = clauses(extension).filter { it.hasUsingKeyword() }
        return DeclarationInfo(
            DeclarationKind.EXTENSION, Signatures.text(receiver, ::isAnnotation), signature(usings, Signatures::text),
            members(ScalaInterop.orNull(extension.extensionBody())), displaySignature = signature(usings, Signatures::scalaLabel),
        )
    }

    // A pattern definition `val (a, b) = …` binds through one pattern, so it is one property.
    private fun property(property: ScValueOrVariable): DeclarationInfo? {
        val names = when (property) {
            is ScValueOrVariableDefinition -> ScalaInterop.list(property.bindings()).map { it.name() }
            is ScValueOrVariableDeclaration -> ScalaInterop.list(property.getIdList().fieldIds()).map { it.name() }
            else -> emptyList()
        }
        return propertyName(names)?.let { DeclarationInfo(DeclarationKind.PROPERTY, it, signature = null) }
    }

    /** The bound name, or `(a, b)` for a pattern that binds several; null when nothing is bound, as in `val _ = f()`. */
    private fun propertyName(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> names.single()
        else -> names.joinToString(", ", "(", ")")
    }

    /**
     * One of several declarations written together in [group], such as `case A, B`. As for Java fields, the shared
     * tokens before the first belong to the first, and those after the last to the last.
     */
    private fun part(element: PsiElement, group: PsiElement, parts: List<PsiElement>, kind: DeclarationKind, name: String): DeclarationInfo {
        val index = parts.indexOf(element)
        val start = if (index == 0) group.textRange.startOffset else element.textRange.startOffset
        val end = if (index == parts.lastIndex) group.textRange.endOffset else element.textRange.endOffset
        return DeclarationInfo(kind, name, signature = null, group = group, range = TextRange(start, end))
    }

    // name() is the name as written; getName() of an object is its JVM name, `Shapes$`. Without a name identifier the
    // name was not parsed.
    private fun name(element: ScNamedElement): String? = element.nameId()?.let { element.name() }?.takeIf { it.isNotEmpty() }

    /** The parameter clauses written after the name, in order; none for `def f`, one empty clause for `def f()`. */
    private fun clauses(owner: PsiElement): List<ScParameterClause> =
        PsiTreeUtil.getChildOfType(owner, ScParameters::class.java)?.let { ScalaInterop.list(it.clauses()) }.orEmpty()

    /**
     * Each clause's parameter types joined with `,`, after `using ` or `implicit ` for such a clause, and the clauses
     * joined with `;` (§6.8); null without clauses. Repeated (`T*`) and by-name (`=> T`) types stay as written.
     */
    private fun signature(clauses: List<ScParameterClause>, text: (PsiElement, (PsiElement) -> Boolean) -> String): String? =
        clauses.takeIf { it.isNotEmpty() }?.joinToString(";") { clause ->
            val keyword = when {
                clause.hasUsingKeyword() -> "using "
                clause.hasImplicitKeyword() -> "implicit "
                else -> ""
            }
            keyword + Signatures.of(ScalaInterop.list(clause.parameters()).map { parameterType(it, text) })
        }

    /** The type after the colon, without annotations; the name, modifiers and a default value are not part of it. */
    private fun parameterType(parameter: ScParameter, text: (PsiElement, (PsiElement) -> Boolean) -> String): String =
        PsiTreeUtil.getChildOfType(parameter, ScParameterType::class.java)?.let { text(it, ::isAnnotation) } ?: ""

    private fun isAnnotation(element: PsiElement): Boolean = element is ScAnnotation

    private fun errors(project: Project, text: CharSequence, language: Language): Int {
        val file = PsiFileFactory.getInstance(project).createFileFromText("SmartDiff.scala", language, text, false, false)
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size
    }
}
