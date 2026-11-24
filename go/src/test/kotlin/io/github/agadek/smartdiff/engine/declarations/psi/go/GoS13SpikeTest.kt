// TECHNICAL-SPECIFICATION S13
package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Spike S13: confirms the Go plugin id, the Go language id, the PSI classes a Go provider would use, how a text-built
 * file parses, and whether in-file references resolve without a Go SDK or module.
 *
 * The test prints its findings as `S13 ...` lines, which the test task copies into the build log.
 */
class GoS13SpikeTest : BasePlatformTestCase() {

    private val code = """
        package sample

        import "fmt"

        // Doc for Point.
        type Point struct {
        	X, Y int
        	Name string `json:"name"`
        	fmt.Stringer
        }

        type Shape interface {
        	Area() float64
        	fmt.Stringer
        }

        type (
        	ID    int64
        	Alias = string
        )

        type List[T any] struct{ items []T }

        const Pi = 3.14

        const (
        	A = iota
        	B
        )

        var x, y = 1, 2

        var (
        	z int
        )

        func (p *Point) Move(dx, dy int) { p.X += dx }

        func (l List[T]) Len() int { return len(l.items) }

        func Sum[T int | float64](xs ...T) (total T) { return }

        func init() {}

        func init() {}

        func main() {
        	type local int
        	f := func() {}
        	_ = f
        	fmt.Println(Pi)
        }
    """.trimIndent() + "\n"

    private fun go(): Language {
        val byLower = Language.findLanguageByID("go")
        val byUpper = Language.findLanguageByID("Go")
        println("S13 findLanguageByID(\"go\")=${byLower?.javaClass?.name} findLanguageByID(\"Go\")=${byUpper?.javaClass?.name}")
        return byLower ?: byUpper ?: error("no Go language registered")
    }

    private fun parse(): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("sample.go", go(), code, false, false)
            ?: error("PsiFileFactory returned null")

    fun `test plugin is loaded and the language resolves`() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.go"))
        println("S13 pluginId=org.jetbrains.plugins.go present=${plugin != null} name=${plugin?.name} version=${plugin?.version} enabled=${plugin?.isEnabled}")
        assertNotNull(plugin)
        val lang = go()
        println("S13 language id=${lang.id} class=${lang.javaClass.name} displayName=${lang.displayName}")
    }

    fun `test text-built file parses and its declarations are described`() {
        val file = parse()
        println("S13 file class=${file.javaClass.name} language=${file.language.id} textEqual=${file.text == code}")
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        println("S13 errors count=${errors.size}")
        for (e in errors) println("S13 error offset=${e.textRange.startOffset} description=${e.errorDescription}")

        val outDir = File(System.getProperty("smartdiff.spike.out") ?: "build/go-spike").apply { mkdirs() }
        File(outDir, "psi-tree.txt").writeText(DebugUtil.psiToString(file, true, false))
        println("S13 psiTree written=${File(outDir, "psi-tree.txt").absolutePath}")

        // Every non-leaf element whose class looks like a declaration-level node.
        val interesting = Regex("Declaration|Definition|Spec|TypeSpec|Struct|Interface|Field|Method|Function|Parameter|Receiver|Signature|Result|Anonymous|Const|Var|Import|Package|TypeParam|Embedded|Tag")
        PsiTreeUtil.processElements(file) { element ->
            if (element !is PsiWhiteSpace && element.firstChild != null) {
                val cls = element.javaClass
                val allNames = generateSequence<Class<*>>(cls) { it.superclass }.map { it.simpleName } + cls.interfaces.map { it.simpleName }
                if (allNames.any { interesting.containsMatchIn(it) }) {
                    val range = element.textRange
                    val textOk = code.substring(range.startOffset, range.endOffset) == element.text
                    val name = (element as? PsiNamedElement)?.name
                    val snippet = element.text.lineSequence().first().take(50)
                    val depth = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.count()
                    println("S13 element depth=$depth class=${cls.name} name=$name range=$range textOk=$textOk text=\"$snippet\" parent=${element.parent.javaClass.simpleName}")
                    describeExtras(element)
                }
            }
            true
        }

        for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
            val next = PsiTreeUtil.skipWhitespacesForward(comment)
            println("S13 comment text=\"${comment.text}\" class=${comment.javaClass.name} parent=${comment.parent.javaClass.name} parentRange=${comment.parent.textRange} nextSibling=${next?.javaClass?.name} nextRange=${next?.textRange}")
        }
        assertEquals("no parse errors", 0, errors.size)
    }

    /** Receiver and other accessors found by reflection, so the spike does not compile against Go PSI types. */
    private fun describeExtras(element: PsiElement) {
        val getters = listOf("getReceiver", "getReceiverType", "getType", "getTypeParameters", "getSignature", "getParameters",
            "getIdentifier", "getSpecType", "getFieldDefinitionList", "getAnonymousFieldDefinition", "getTag", "isVariadic",
            "getTypeSpecList", "getConstSpecList", "getVarSpecList", "getConstDefinitionList", "getVarDefinitionList",
            "getMethodSpecList", "getTypeReferenceExpression", "getQualifiedName", "getDefinitionList")
        for (getter in getters) {
            val method = element.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } ?: continue
            val value = try { method.invoke(element) } catch (t: Throwable) { "threw ${t.javaClass.simpleName}" }
            val shown = when (value) {
                is PsiElement -> "${value.javaClass.simpleName}(\"${value.text.lineSequence().first().take(40)}\")"
                is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { (it as? PsiElement)?.let { e -> "${e.javaClass.simpleName}(\"${e.text.take(30)}\")" } ?: it.toString() }
                else -> value?.toString()
            }
            println("S13   $getter -> $shown")
        }
    }

    fun `test in-file reference resolves without a go sdk`() {
        val file = parse()
        val offset = code.indexOf("Println(Pi)") + "Println(".length
        val reference = file.findReferenceAt(offset)
        println("S13 reference at Pi class=${reference?.javaClass?.name} element=${reference?.element?.javaClass?.name}")
        val target = try { reference?.resolve() } catch (t: Throwable) { println("S13 resolve threw ${t.javaClass.name}: ${t.message}"); null }
        println("S13 resolve Pi -> class=${target?.javaClass?.name} name=${(target as? PsiNamedElement)?.name} offset=${target?.textRange?.startOffset} text=\"${target?.text}\"")
        val fmtOffset = code.indexOf("fmt.Println") + 1
        val fmtTarget = try { file.findReferenceAt(fmtOffset)?.resolve() } catch (t: Throwable) { println("S13 resolve fmt threw ${t.javaClass.name}"); null }
        println("S13 resolve fmt -> class=${fmtTarget?.javaClass?.name} text=\"${fmtTarget?.text?.take(40)}\"")
    }
}
