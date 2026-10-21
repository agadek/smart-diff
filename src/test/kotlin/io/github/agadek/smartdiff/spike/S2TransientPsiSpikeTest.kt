package io.github.agadek.smartdiff.spike

import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import java.util.concurrent.Callable

/**
 * Spike S2 (technical specification §13): do PSI files built from VCS-revision text in the default project give correct
 * declarations and offsets, for Java and for Kotlin in K2 mode?
 *
 * Parsing runs the way §6.2 and §3.1 describe it: on a pooled thread, inside a read action the engine takes itself, with
 * `PsiFileFactory.createFileFromText(name, language, text, false, false)` and no project of the diff.
 */
class S2TransientPsiSpikeTest : BasePlatformTestCase() {

    /** A declaration as found in the PSI, with its text taken from the request text by the PSI's range. */
    private data class Found(val label: String, val start: Int, val end: Int, val psiText: String, val sliced: String)

    private class Parsed(val fileText: String, val errors: List<String>, val declarations: List<Found>, val note: String)

    fun `test Kotlin plugin runs in K2 mode`() {
        assertTrue("tests must run with the Kotlin plugin in K2 mode", KotlinPluginModeProvider.isK2Mode())
        spike("S2 Kotlin plugin mode ${KotlinPluginModeProvider.currentPluginMode}")
    }

    fun `test VCS revision text is LF-normalized before it reaches an engine`() {
        val content = SpikeContents.vcsRevision(project, "class A {\r\n    void f() {}\r\n}\r\n", "/repo/A.java")
        val text = content.document.immutableCharSequence

        assertFalse("document text should not contain CR", text.contains('\r'))
        spike("S2 CRLF revision: document has ${text.length} chars, no CR; content line separator ${content.lineSeparator?.name}")
    }

    fun `test Java revision text parses in the default project with correct offsets`() {
        val text = revisionText(JAVA_SOURCE, "/repo/Shapes.java")

        val parsed = parse("Shapes.java", JavaLanguage.INSTANCE, text, forceHighestJavaLevel = true)

        assertParsedExactly(text, parsed)
        assertContainsElements(
            parsed.declarations.map { it.label },
            "PsiClass Shapes", "PsiClassInitializer", "PsiField a", "PsiField b", "PsiMethod Shapes", "PsiMethod list",
            "PsiClass Kind", "PsiEnumConstant CIRCLE", "PsiEnumConstant SQUARE", "PsiClass Point", "PsiClass Shape",
            "PsiClass Circle", "PsiClass Marker", "PsiAnnotationMethod value", "PsiMethod describe",
        )
        assertEquals("the class range starts at its doc comment", text.indexOf("/** Shapes."), declaration(parsed, "PsiClass Shapes").start)
        spike("S2 Java forced HIGHEST: ${parsed.declarations.size} members, ${parsed.errors.size} errors; ${parsed.note}")
    }

    fun `test Java language level of a default-project file`() {
        val text = revisionText(JAVA_SOURCE, "/repo/Shapes.java")

        val parsed = parse("Shapes.java", JavaLanguage.INSTANCE, text, forceHighestJavaLevel = false)

        // Observation only: whether modern syntax parses without forcing the language level decides §6.2's wording.
        assertEquals(text.toString(), parsed.fileText)
        spike("S2 Java default level: ${parsed.note}, ${parsed.errors.size} errors ${parsed.errors.take(5)}")
    }

    fun `test Kotlin revision text parses in the default project with correct offsets`() {
        val text = revisionText(KOTLIN_SOURCE, "/repo/Shapes.kt")

        val parsed = parse("Shapes.kt", KotlinLanguage.INSTANCE, text, forceHighestJavaLevel = false)

        assertParsedExactly(text, parsed)
        spike("S2 Kotlin declarations: ${parsed.declarations.map { it.label }}")
        assertContainsElements(
            parsed.declarations.map { it.label },
            "KtClass Shapes", "KtClassInitializer", "KtSecondaryConstructor", "KtNamedFunction second",
            "KtProperty size2", "KtProperty local", "KtClass Kind", "KtEnumEntry CIRCLE", "KtEnumEntry SQUARE",
            "KtObjectDeclaration Companion", "KtProperty K", "KtObjectDeclaration Named", "KtClass Shape",
            "KtClass Marker", "KtClass Point", "KtTypeAlias Name", "KtNamedFunction top", "KtProperty topLevel",
        )
        // An unnamed companion object reports the name "Companion", as §6.5 expects.
        assertEquals("the class range starts at its KDoc", text.indexOf("/** Shapes."), declaration(parsed, "KtClass Shapes").start)
        spike("S2 Kotlin (${KotlinPluginModeProvider.currentPluginMode}): ${parsed.declarations.size} declarations, ${parsed.errors.size} errors; ${parsed.note}")
    }

    fun `test a parse error on one side keeps the other declarations`() {
        val text = revisionText("class Broken {\n    void ok() {}\n    void bad( {\n    int after;\n}\n", "/repo/Broken.java")

        val parsed = parse("Broken.java", JavaLanguage.INSTANCE, text, forceHighestJavaLevel = true)

        assertTrue(parsed.errors.isNotEmpty())
        assertEquals(text.toString(), parsed.fileText)
        parsed.declarations.forEach { assertEquals(it.label, it.psiText, it.sliced) }
        assertContainsElements(parsed.declarations.map { it.label }, "PsiClass Broken", "PsiMethod ok")
        spike("S2 Java parse error: ${parsed.declarations.map { it.label }}, errors ${parsed.errors}")
    }

    private fun revisionText(source: String, path: String): CharSequence =
        SpikeContents.vcsRevision(project, source, path).document.immutableCharSequence

    private fun parse(name: String, language: Language, text: CharSequence, forceHighestJavaLevel: Boolean): Parsed {
        val task = Callable {
            ReadAction.compute(ThrowableComputable<Parsed, RuntimeException> {
                val defaultProject = ProjectManager.getInstance().defaultProject
                val file = PsiFileFactory.getInstance(defaultProject).createFileFromText(name, language, text, false, false)
                if (forceHighestJavaLevel) file.putUserData(PsiUtil.FILE_LANGUAGE_LEVEL_KEY, LanguageLevel.HIGHEST)
                describe(file, text)
            })
        }
        return PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(task), 30_000)
    }

    private fun describe(file: PsiFile, text: CharSequence): Parsed {
        val elements: Collection<PsiElement> = when (file) {
            is KtFile -> PsiTreeUtil.collectElementsOfType(file, KtDeclaration::class.java)
            else -> PsiTreeUtil.collectElementsOfType(file, PsiMember::class.java)
        }
        val declarations = elements.map { element ->
            val range = element.textRange
            Found(label(element), range.startOffset, range.endOffset, element.text, text.subSequence(range.startOffset, range.endOffset).toString())
        }
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
            .map { "${it.textRange.startOffset}: ${it.errorDescription}" }
        val note = when (file) {
            is PsiJavaFile -> "file ${file.javaClass.simpleName}, language level ${file.languageLevel}"
            else -> "file ${file.javaClass.simpleName}"
        }
        return Parsed(file.text, errors, declarations, note)
    }

    private fun label(element: PsiElement): String {
        val kind = element.javaClass.simpleName.removeSuffix("Impl")
        val name = when (element) {
            is KtAnonymousInitializer, is KtSecondaryConstructor -> null
            is PsiNamedElement -> element.name
            else -> null
        }
        return if (name == null) kind else "$kind $name"
    }

    private fun assertParsedExactly(text: CharSequence, parsed: Parsed) {
        assertEquals("PSI text equals the request text", text.toString(), parsed.fileText)
        assertEmpty("no parse errors expected", parsed.errors)
        parsed.declarations.forEach { assertEquals("offsets of ${it.label}", it.psiText, it.sliced) }
    }

    private fun declaration(parsed: Parsed, label: String): Found = parsed.declarations.first { it.label == label }

    private companion object {
        val JAVA_SOURCE = """
            package demo;

            import java.util.List;

            /** Shapes. */
            @Deprecated
            public class Shapes {
                static { System.out.println(); }

                private int a, b;

                public Shapes(int a) { this.a = a; }

                <T> List<T> list(final java.util.Map<String, List<Integer>> m, int... xs) { return null; }

                enum Kind { CIRCLE, SQUARE { } }

                record Point(int x, int y) { Point { } }

                sealed interface Shape permits Circle {}

                final class Circle implements Shape {}

                @interface Marker { String value() default ""; }

                String describe(Object o) {
                    return switch (o) {
                        case Point(int x, int y) when x > 0 -> "point";
                        default -> ""${'"'}
                            text block
                            ""${'"'};
                    };
                }
            }
        """.trimIndent() + "\n"

        val KOTLIN_SOURCE = """
            package demo

            import kotlin.math.max

            /** Shapes. */
            @Suppress("unused")
            class Shapes(val a: Int, var b: String) {
                init { println(a) }

                constructor(a: Int) : this(a, "")

                fun <T> List<T>.second(x: Int, vararg ys: String): T = this[1]

                val String.size2: Int get() = length

                private val local = run { val inner = 1; inner }

                enum class Kind { CIRCLE, SQUARE }

                companion object { const val K = 1 }

                object Named

                interface Shape

                annotation class Marker

                data class Point(val x: Int, val y: Int)
            }

            typealias Name = String

            fun top(): Int = max(1, 2)

            val topLevel = 1
        """.trimIndent() + "\n"
    }
}
