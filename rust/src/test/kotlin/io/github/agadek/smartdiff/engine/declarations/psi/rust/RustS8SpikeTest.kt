// TECHNICAL-SPECIFICATION S8
package io.github.agadek.smartdiff.engine.declarations.psi.rust

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Spike S8 (technical specification §13, Rust phase R0): confirms the Rust plugin id, the PSI classes a provider
 * would use, how a text-built file parses, and whether in-file references resolve without a Cargo project.
 *
 * The test prints its findings as `S8 ...` lines, which the test task copies into the build log.
 */
class RustS8SpikeTest : BasePlatformTestCase() {

    private val rust: Language by lazy {
        Language.findLanguageByID("Rust") ?: error("the Rust language is not registered")
    }

    private fun parse(name: String, text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, rust, text, false, false)
            ?: error("PsiFileFactory returned null for $name")

    private fun classFor(fqn: String): Class<out PsiElement> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName(fqn) as Class<out PsiElement>
    }

    fun `test plugin id com jetbrains rust is present`() {
        val id = PluginId.getId("com.jetbrains.rust")
        val plugin = PluginManagerCore.getPlugin(id)
        println("S8 pluginId=com.jetbrains.rust present=${plugin != null} name=${plugin?.name} version=${plugin?.version}")
        assertNotNull("com.jetbrains.rust plugin must be present", plugin)
    }

    fun `test rust language is registered`() {
        println("S8 language=Rust id=${rust.id} class=${rust.javaClass.name}")
        assertEquals("Rust", rust.id)
    }

    fun `test rust psi classes exist with their real fully qualified names`() {
        val fqns = listOf(
            "org.rust.lang.core.psi.RsFunction",
            "org.rust.lang.core.psi.RsStructItem",
            "org.rust.lang.core.psi.RsEnumItem",
            "org.rust.lang.core.psi.RsEnumVariant",
            "org.rust.lang.core.psi.RsNamedFieldDecl",
            "org.rust.lang.core.psi.RsTraitItem",
            "org.rust.lang.core.psi.RsImplItem",
            "org.rust.lang.core.psi.RsModItem",
            "org.rust.lang.core.psi.RsModDeclItem",
            "org.rust.lang.core.psi.RsTypeAlias",
            "org.rust.lang.core.psi.RsConstant",
            "org.rust.lang.core.psi.RsMacro",
            "org.rust.lang.core.psi.RsMacro2",
            "org.rust.lang.core.psi.RsMacroCall",
        )
        for (fqn in fqns) {
            val loaded = try { Class.forName(fqn) } catch (_: Throwable) { null }
            println("S8 psiClass=$fqn exists=${loaded != null}")
            assertNotNull("expected PSI class $fqn", loaded)
        }
    }

    fun `test parsed elements have the expected names and text offsets`() {
        val code = """
            fn foo(x: i32) -> i32 { x }
            struct S { f: i32 }
            enum E { V }
            trait T { fn m(&self); }
            impl T for S { fn m(&self) {} }
            mod m { }
            mod decl;
            type A = i32;
            const C: i32 = 1;
            macro_rules! mm { () => {}; }
            macro m2() { }
        """.trimIndent() + "\n"
        val file = parse("sample.rs", code)
        println("S8 parsed fileClass=${file.javaClass.name} length=${file.textLength} language=${file.language.id}")
        assertEquals("the parsed file text equals the request text", code, file.text)

        fun check(fqn: String, expectedNames: List<String>) {
            val elements = PsiTreeUtil.findChildrenOfType(file, classFor(fqn))
            val names = elements.map { (it as? PsiNamedElement)?.name }
            println("S8 elements $fqn count=${elements.size} names=$names")
            assertTrue("expected at least one $fqn", elements.isNotEmpty())
            for (name in expectedNames) {
                assertTrue("expected $fqn named $name", names.contains(name))
            }
            for (element in elements) {
                val range = element.textRange
                assertEquals("offset text of $fqn", code.substring(range.startOffset, range.endOffset), element.text)
            }
        }

        check("org.rust.lang.core.psi.RsFunction", listOf("foo"))
        check("org.rust.lang.core.psi.RsStructItem", listOf("S"))
        check("org.rust.lang.core.psi.RsEnumItem", listOf("E"))
        check("org.rust.lang.core.psi.RsEnumVariant", listOf("V"))
        check("org.rust.lang.core.psi.RsNamedFieldDecl", listOf("f"))
        check("org.rust.lang.core.psi.RsTraitItem", listOf("T"))
        check("org.rust.lang.core.psi.RsImplItem", emptyList())
        check("org.rust.lang.core.psi.RsModItem", listOf("m"))
        check("org.rust.lang.core.psi.RsModDeclItem", listOf("decl"))
        check("org.rust.lang.core.psi.RsTypeAlias", listOf("A"))
        check("org.rust.lang.core.psi.RsConstant", listOf("C"))
        check("org.rust.lang.core.psi.RsMacro", listOf("mm"))
        check("org.rust.lang.core.psi.RsMacro2", listOf("m2"))
    }

    fun `test edition 2021 and 2024 syntax on text built files`() {
        fun errors(snippet: String): List<String> {
            val file = parse("edition.rs", snippet)
            return PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }
        }

        val letElse = errors("fn f(o: Option<i32>) { let Some(x) = o else { return; }; let _ = x; }\n")
        val asyncClosure = errors("fn f() { let _ = async || { 1 }; }\n")
        val genBlock = errors("fn f() { let _ = gen { yield 1; }; }\n")
        println("S8 edition let_else_errors=$letElse async_closure_errors=$asyncClosure gen_errors=$genBlock")
        assertEquals("let-else parses without errors", emptyList<String>(), letElse)
        assertEquals("async closures parse without errors", emptyList<String>(), asyncClosure)
        assertEquals("gen blocks parse without errors", emptyList<String>(), genBlock)
    }

    fun `test element text equals request text for crlf content`() {
        val lf = "fn foo() {\n    let x = 1;\n}\n"
        val crlf = StringUtil.convertLineSeparators(lf, "\r\n")
        val file = parse("crlf.rs", crlf)
        assertEquals("the parsed file keeps CRLF line ends", crlf, file.text)
        val functions = PsiTreeUtil.findChildrenOfType(file, classFor("org.rust.lang.core.psi.RsFunction"))
        println("S8 crlf functions=${functions.size}")
        assertTrue("expected a function in the CRLF file", functions.isNotEmpty())
        for (function in functions) {
            val range = function.textRange
            assertEquals("offset text of the CRLF function", crlf.substring(range.startOffset, range.endOffset), function.text)
        }
    }

    fun `test in file function reference resolution without a cargo project`() {
        val code = "fn callee() {}\nfn caller() { callee(); }\n"
        val file = parse("refs.rs", code)
        val paths = PsiTreeUtil.findChildrenOfType(file, classFor("org.rust.lang.core.psi.RsPath"))
            .filter { it.text == "callee" }
        println("S8 refs RsPath(callee)=${paths.size}")
        assertTrue("expected a path expression naming callee", paths.isNotEmpty())
        val resolved = paths.first().reference?.resolve()
        println("S8 refs resolved=${resolved != null} target=${resolved?.let { (it as? PsiNamedElement)?.name }} class=${resolved?.javaClass?.name}")
        val targets = paths.mapNotNull { it.reference?.resolve() }
        assertEquals("in-file references resolve to the local function without Cargo", 1, targets.size)
        assertEquals("callee", (targets.first() as? PsiNamedElement)?.name)
    }
}
