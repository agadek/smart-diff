package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree

/**
 * Turns one side of a file into a [DeclarationTree] for one language (technical specification §6.1). Implementations
 * register on the engine-internal `io.github.agadek.smartdiff.declarationProvider` extension point, in the optional
 * descriptor of their language plugin.
 *
 * All functions are called inside a read action. Providers never resolve references or types (§6.2).
 */
interface DeclarationProvider {
    /** Whether this provider handles [languageId], an IntelliJ `Language.getID()`. */
    fun supports(languageId: String): Boolean

    /**
     * A note the outline appends for this provider's results, such as [AnalysisNote.STRUCTURE_VIEW] for the
     * structure-view fallback; null for a dedicated language provider.
     */
    val analysisNote: String? get() = null

    /**
     * The dialect every side is parsed in, chosen once from all [texts] so their trees compare (§6.2): both sides of the
     * request, then its related texts. Null for a language with one dialect.
     */
    fun dialect(project: Project, texts: List<CharSequence>, fileName: String?): Language? = null

    /**
     * [dialect] with the request's language id, so a provider that covers many languages can resolve one when the file
     * name does not. A dedicated provider keeps using its three-argument implementation.
     */
    fun dialect(project: Project, texts: List<CharSequence>, fileName: String?, languageId: String): Language? =
        dialect(project, texts, fileName)

    /**
     * Parses [text] into a PSI file that belongs to no module (§6.2), in [dialect] if it is not null. [fileName] is the
     * request's hint, if any.
     */
    fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile

    /** [parse] with the request's language id, passed explicitly rather than kept on the provider between calls. */
    fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?, languageId: String): PsiFile =
        parse(project, text, fileName, dialect)

    fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree

    companion object {
        val EP_NAME: ExtensionPointName<DeclarationProvider> =
            ExtensionPointName.create("io.github.agadek.smartdiff.declarationProvider")
    }
}
