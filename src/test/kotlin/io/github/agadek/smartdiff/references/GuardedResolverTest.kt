package io.github.agadek.smartdiff.references

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ApiGuard
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** A resolver whose optional-plugin API is missing resolves nothing, and other resolvers keep working (platform/01 R12). */
class GuardedResolverTest : BasePlatformTestCase() {

    private var savedReporter: (String) -> Unit = {}

    override fun setUp() {
        super.setUp()
        ApiGuard.resetForTests()
        savedReporter = ApiGuard.reporter
        ApiGuard.reporter = {}
    }

    override fun tearDown() {
        try {
            ApiGuard.reporter = savedReporter
            ApiGuard.resetForTests()
        } finally {
            super.tearDown()
        }
    }

    private class FakeResolver(
        private val language: String,
        override val guardedFeature: GuardedFeature?,
        private val broken: Boolean,
    ) : ReferenceResolver {
        override fun supports(languageId: String) = languageId == language
        override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement {
            if (broken) throw NoClassDefFoundError("org/jetbrains/plugins/scala/lang/psi/api/Gone")
            return file
        }
        override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation) = listOf(TextRange(0, 1))
        override fun context(file: PsiFile, usage: TextRange): TextRange? = null
        override fun whitespacePolicy(file: PsiFile) = WhitespacePolicy.INSIGNIFICANT
    }

    private val subject = Subject.Declaration(io.github.agadek.smartdiff.engine.api.DeclarationKind.METHOD, "total", "")

    // platform/01-platform-api-stability R12
    fun `test a resolver throwing NoClassDefFoundError leaves the rename without usages`() {
        val file = myFixture.configureByText("A.java", "class A { int total() { return 1; } }")
        val broken = ReferenceResolvers.guarded(FakeResolver("Scala", GuardedFeature.SCALA_REFERENCES, broken = true))

        assertTrue(broken.supports("Scala"))
        assertNull("no target: the rename keeps no usages", broken.target(file, TextRange(0, 1), subject))
        assertFalse("the resolver is off for the session", broken.supports("Scala"))
        assertEquals(emptyList<TextRange>(), broken.usages(file, file, Cancellation.NONE))
        assertEquals(emptyMap<PsiElement, List<TextRange>>(), broken.usages(file, setOf<PsiElement>(file), Cancellation.NONE))
        assertNull(broken.context(file, TextRange(0, 1)))
    }

    // platform/01-platform-api-stability R12
    fun `test other renames are unaffected`() {
        val file = myFixture.configureByText("A.java", "class A { int total() { return 1; } }")
        val broken = ReferenceResolvers.guarded(FakeResolver("Scala", GuardedFeature.SCALA_REFERENCES, broken = true))
        val python = ReferenceResolvers.guarded(FakeResolver("Python", GuardedFeature.PYTHON_REFERENCES, broken = false))
        val java = ReferenceResolvers.guarded(FakeResolver("JAVA", null, broken = false))
        broken.target(file, TextRange(0, 1), subject)

        assertTrue(python.supports("Python"))
        assertSame(file, python.target(file, TextRange(0, 1), subject))
        assertEquals(listOf(TextRange(0, 1)), python.usages(file, file, Cancellation.NONE))
        assertTrue("a resolver without a guarded feature is used as it is", java is FakeResolver)
        assertSame(file, java.target(file, TextRange(0, 1), subject))
    }

    // platform/01-platform-api-stability R12
    fun `test the registered resolvers that need an optional plugin are guarded`() {
        val guarded = ReferenceResolvers.all.filter { it.guardedFeature != null }
        assertTrue("guarded: $guarded", guarded.all { it is GuardedReferenceResolver })
        assertTrue(guarded.map { it.guardedFeature }.containsAll(listOf(GuardedFeature.KOTLIN_REFERENCES, GuardedFeature.SCALA_REFERENCES, GuardedFeature.PYTHON_REFERENCES)))
    }
}
