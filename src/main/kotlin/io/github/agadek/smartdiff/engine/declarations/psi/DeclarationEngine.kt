package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.declarations.analysis.DeclarationAnalysis
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * The declaration engine (technical specification §6 to §8): a language's [DeclarationProvider] turns each side into a
 * declaration tree, then [DeclarationAnalysis] matches and classifies them.
 *
 * The provider first chooses the dialect from both texts and the request's related texts; then each side is parsed in its
 * own cancellable read action.
 * Matching and classification run outside any read action and hold no PSI.
 */
class DeclarationEngine(private val project: Project?) : ComparisonEngine {
    override val id: EngineId = ID
    override val displayName: String = "Declarations"

    /**
     * The engine's own version and the platform build whose parsers it uses, so results from another IDE differ; the
     * Scala plugin's version is added too when its provider is loaded, since its parser can change a result without a
     * platform update (relationships/02 R24).
     */
    override val version: String = formatVersion(ApplicationInfo.getInstance().build.asStringWithoutProductCode(), scalaPluginVersion())

    override fun supports(languageId: String): Boolean = provider(languageId) != null

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
        val provider = provider(request.languageId, request.useStructureViewFallback)
            ?: return ComparisonResult.unsupported(this, "no declaration provider for language ${request.languageId}")
        return try {
            val (old, new) = trees(provider, request, cancellation)
            val result = DeclarationAnalysis(id, version).compare(old, new, request.limits.maxDeclarations, cancellation, request.confidence)
            provider.analysisNote?.let { result.copy(analysisNote = it) } ?: result
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            ComparisonResult.threw(this, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The declaration trees of both sides of [request], for the corpus validation's diagnostics. */
    internal fun trees(request: ComparisonRequest, cancellation: Cancellation): Pair<DeclarationTree, DeclarationTree>? =
        provider(request.languageId, request.useStructureViewFallback)?.let { trees(it, request, cancellation) }

    private fun trees(provider: DeclarationProvider, request: ComparisonRequest, cancellation: Cancellation): Pair<DeclarationTree, DeclarationTree> {
        val dialect = cancellableReadAction(cancellation) {
            provider.dialect(
                project(),
                listOf(request.oldText, request.newText) + request.relatedTexts,
                request.fileName,
                request.languageId,
            )
        }
        return build(provider, request.oldText, request.fileName, dialect, request.languageId, cancellation) to
            build(provider, request.newText, request.fileName, dialect, request.languageId, cancellation)
    }

    private fun build(
        provider: DeclarationProvider,
        text: CharSequence,
        fileName: String?,
        dialect: Language?,
        languageId: String,
        cancellation: Cancellation,
    ): DeclarationTree =
        cancellableReadAction(cancellation) {
            provider.build(provider.parse(project(), text, fileName, dialect, languageId), cancellation)
        }

    private fun project(): Project = project ?: ProjectManager.getInstance().defaultProject

    private fun provider(languageId: String, useStructureViewFallback: Boolean = true): DeclarationProvider? =
        DeclarationProvider.EP_NAME.extensionList.firstOrNull { provider ->
            provider.supports(languageId) && (useStructureViewFallback || provider.analysisNote == null)
        }

    companion object {
        val ID = EngineId("smartdiff.declarations")

        /** Raise when a change to a provider, matching or classification can change results. */
        const val VERSION = "11"

        /** The result's engine version: `9 (251.26927.53)`, or with the Scala plugin, `9 (251.26927.53; Scala 2025.1.32)`. */
        internal fun formatVersion(platformBuild: String, scalaPluginVersion: String?): String =
            if (scalaPluginVersion == null) "$VERSION ($platformBuild)" else "$VERSION ($platformBuild; Scala $scalaPluginVersion)"

        /**
         * The Scala plugin's version when its declaration provider is loaded, since its parser can change a result
         * (R24); null when it is not loaded, so an IDE without the Scala plugin keeps a shorter version.
         *
         * The Scala language class comes from the Scala plugin's classloader, whose classpath holds that plugin's
         * descriptor. Reading the descriptor is deliberate: the plugin manager's lookup is an internal platform API,
         * which the plugin verifier rejects since an internal API can change between releases.
         *
         * Every descriptor is read, not just the first: in a test every plugin shares one classloader, where the
         * first `META-INF/plugin.xml` belongs to another plugin.
         */
        private fun scalaPluginVersion(): String? {
            if (DeclarationProvider.EP_NAME.extensionList.none { it.supports(SCALA) }) return null
            val version = try {
                val language = Language.findLanguageByID(SCALA)
                val descriptors = language?.javaClass?.classLoader
                    ?.getResources(PLUGIN_DESCRIPTOR)
                    ?.let { Collections.list(it) }
                    .orEmpty()
                descriptors.firstNotNullOfOrNull { url ->
                    try {
                        url.openStream().use { stream ->
                            parseScalaPluginVersion(stream.bufferedReader(Charsets.ISO_8859_1).readText())
                        }
                    } catch (e: Exception) {
                        null
                    }
                } ?: UNKNOWN
            } catch (e: Exception) {
                UNKNOWN
            }
            if (version == UNKNOWN) {
                Logger.getInstance(DeclarationEngine::class.java).debug(
                    "Scala plugin version is $UNKNOWN: no $PLUGIN_DESCRIPTOR on the Scala language classloader gives " +
                        "a usable <version> for $SCALA_PLUGIN_ID, so the engine version cannot name its parser " +
                        "(relationships/02 R24)",
                )
            }
            return version
        }

        /** The version of a Scala plugin descriptor, or null when it is another plugin's or has no `<version>`. */
        internal fun parseScalaPluginVersion(descriptor: String): String? {
            if (!PLUGIN_ID_TAG.containsMatchIn(descriptor)) return null
            return VERSION_TAG.find(descriptor)?.groupValues?.get(1)
        }

        private const val SCALA = "Scala"
        private const val SCALA_PLUGIN_ID = "org.intellij.scala"
        private const val PLUGIN_DESCRIPTOR = "META-INF/plugin.xml"
        private const val UNKNOWN = "unknown"
        private val PLUGIN_ID_TAG = Regex("<id>\\s*${Regex.escape(SCALA_PLUGIN_ID)}\\s*</id>")
        private val VERSION_TAG = Regex("<version>\\s*([^<\\s]+)\\s*</version>")
    }
}
