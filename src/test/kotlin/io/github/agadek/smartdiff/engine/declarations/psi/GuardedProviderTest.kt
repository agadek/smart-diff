package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ApiGuard
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.java.JavaDeclarationProvider

/** A provider whose optional-plugin API is missing turns off alone, and the diff still opens (platform/01 R11, R14, R15). */
class GuardedProviderTest : BasePlatformTestCase() {

    private val warnings = mutableListOf<String>()
    private var savedReporter: (String) -> Unit = {}

    override fun setUp() {
        super.setUp()
        ApiGuard.resetForTests()
        savedReporter = ApiGuard.reporter
        ApiGuard.reporter = { warnings += it }
    }

    override fun tearDown() {
        try {
            ApiGuard.reporter = savedReporter
            ApiGuard.resetForTests()
        } finally {
            super.tearDown()
        }
    }

    /** Claims Java through a guarded feature and fails where a removed plugin method would. */
    private class BrokenProvider(private val failIn: String) : DeclarationProvider {
        private val java = JavaDeclarationProvider()
        override val guardedFeature: GuardedFeature get() = GuardedFeature.SCALA_PROVIDER
        override fun supports(languageId: String): Boolean {
            if (failIn == "supports") throw NoSuchMethodError("ScalaLanguage.gone()")
            return languageId == "JAVA"
        }
        override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
            java.parse(project, text, fileName, dialect)
        override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree =
            throw NoSuchMethodError("ScFunction.gone()")
    }

    private val request = ComparisonRequest("class A { void a() {} }\n", "class A { void b() {} }\n", "JAVA", "A.java")

    // platform/01-platform-api-stability R11, R14
    fun `test a provider failing in supports leaves the file to the next provider`() {
        ExtensionTestUtil.maskExtensions(DeclarationProvider.EP_NAME, listOf(BrokenProvider("supports"), JavaDeclarationProvider()), testRootDisposable)
        val engine = DeclarationEngine(null)

        assertTrue("the next provider supports the language", engine.supports("JAVA"))
        assertEquals(Status.ANALYZED, engine.compare(request, Cancellation.NONE).status)
        assertFalse(ApiGuard.isOn(GuardedFeature.SCALA_PROVIDER))
        assertEquals("one warning", 1, warnings.size)
    }

    // platform/01-platform-api-stability R11, R14
    fun `test a provider failing during analysis is retried without it`() {
        ExtensionTestUtil.maskExtensions(DeclarationProvider.EP_NAME, listOf(BrokenProvider("build"), JavaDeclarationProvider()), testRootDisposable)
        val result = DeclarationEngine(null).compare(request, Cancellation.NONE)
        assertEquals("the Java provider analyzed it after the retry", Status.ANALYZED, result.status)
        assertFalse(ApiGuard.isOn(GuardedFeature.SCALA_PROVIDER))
    }

    // platform/01-platform-api-stability R11, R15
    fun `test with no other provider the file gets the unsupported state`() {
        ExtensionTestUtil.maskExtensions(DeclarationProvider.EP_NAME, listOf(BrokenProvider("build")), testRootDisposable)
        val engine = DeclarationEngine(null)
        assertTrue(engine.supports("JAVA"))

        val result = engine.compare(request, Cancellation.NONE)

        assertEquals("never an analyzed result or a failure", Status.UNSUPPORTED, result.status)
        assertFalse("no engine supports the language any more, so the outline shows outline/05 R1", engine.supports("JAVA"))
        assertEquals(Status.UNSUPPORTED, engine.compare(request, Cancellation.NONE).status)
        assertEquals("one warning, never an error report", 1, warnings.size)
    }
}
