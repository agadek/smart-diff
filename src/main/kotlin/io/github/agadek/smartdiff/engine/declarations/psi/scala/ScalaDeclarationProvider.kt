package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject

/**
 * Scala declarations, a first step (roadmap, Scala): only top-level classes and objects, in the Scala 2 or Scala 3
 * dialect (technical specification §6.2). Members and the other kinds follow.
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
     * Scala 3 can make indentation significant (optional braces), so until the indentation-significant policy exists
     * nothing in a Scala 3 file is formatting-only. Scala 2, like Kotlin, infers semicolons from line breaks.
     */
    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy =
        if (file.language == Scala3Language.INSTANCE) WhitespacePolicy.SIGNIFICANT else WhitespacePolicy.LINE_BREAKS_SIGNIFICANT

    // The Scala PSI returns Scala collections; ScalaInterop turns them into Kotlin ones.
    override fun topLevel(file: PsiFile): List<PsiElement> = ScalaInterop.list((file as ScalaFile).typeDefinitions())

    // name() is the name as written; getName() of an object is its JVM name, `Shapes$`.
    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is ScObject -> element.name()?.let { DeclarationInfo(DeclarationKind.OBJECT, it, signature = null) }
        is ScClass -> element.name()?.let { DeclarationInfo(DeclarationKind.CLASS, it, signature = null) }
        else -> null
    }

    private fun errors(project: Project, text: CharSequence, language: Language): Int {
        val file = PsiFileFactory.getInstance(project).createFileFromText("SmartDiff.scala", language, text, false, false)
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size
    }
}
