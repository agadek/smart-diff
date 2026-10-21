package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree

/**
 * Turns one side of a file into a [DeclarationTree] for one language (technical specification §6.1). Implementations
 * register on the engine-internal `io.github.agadek.smartdiff.declarationProvider` extension point, in the optional
 * descriptor of their language plugin.
 *
 * Both functions are called inside a read action. Providers never resolve references or types (§6.2).
 */
interface DeclarationProvider {
    /** Whether this provider handles [languageId], an IntelliJ `Language.getID()`. */
    fun supports(languageId: String): Boolean

    /** Parses [text] into a PSI file that belongs to no module (§6.2). [fileName] is the request's hint, if any. */
    fun parse(project: Project, text: CharSequence, fileName: String?): PsiFile

    fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree

    companion object {
        val EP_NAME: ExtensionPointName<DeclarationProvider> =
            ExtensionPointName.create("io.github.agadek.smartdiff.declarationProvider")
    }
}
