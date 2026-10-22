package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
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
 * Scala declarations, a first step (roadmap, Scala): only top-level classes and objects, parsed in the Scala 2
 * dialect. It proves the Scala plugin dependency end to end; members, the other kinds and the Scala 3 dialect follow.
 */
class ScalaDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean =
        languageId == ScalaLanguage.INSTANCE.id || languageId == Scala3Language.INSTANCE.id

    override fun parse(project: Project, text: CharSequence, fileName: String?): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("SmartDiff.scala", ScalaLanguage.INSTANCE, text, false, false)

    // The Scala PSI returns Scala collections; ScalaInterop turns them into Kotlin ones.
    override fun topLevel(file: PsiFile): List<PsiElement> = ScalaInterop.list((file as ScalaFile).typeDefinitions())

    // name() is the name as written; getName() of an object is its JVM name, `Shapes$`.
    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is ScObject -> element.name()?.let { DeclarationInfo(DeclarationKind.OBJECT, it, signature = null) }
        is ScClass -> element.name()?.let { DeclarationInfo(DeclarationKind.CLASS, it, signature = null) }
        else -> null
    }
}
