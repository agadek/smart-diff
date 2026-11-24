// TECHNICAL-SPECIFICATION S15
package io.github.agadek.smartdiff.engine.declarations.psi.php

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
 * Spike S15: confirms the PHP plugin id, the PHP language id, the PSI classes a PHP provider would use, how a text-built
 * file parses, where PHPDoc comments and attributes sit, and whether in-file references resolve without an interpreter.
 *
 * The test prints its findings as `S15 ...` lines, which the test task copies into the build log.
 */
class PhpS15SpikeTest : BasePlatformTestCase() {

    private val code = """
        <?php

        declare(strict_types=1);

        namespace App\Model;

        use Countable;

        const VERSION = '1.0';

        /**
         * Doc for Point.
         */
        #[Immutable]
        final class Point implements Countable
        {
            use Movable;

            public const ORIGIN = 0, MAX = 10;

            /** The x coordinate. */
            private int ${'$'}x = 0;

            public ?string ${'$'}name, ${'$'}label;

            public function __construct(private readonly int ${'$'}y = 0, string ...${'$'}tags)
            {
            }

            #[Pure]
            public static function of(int &${'$'}x, int ${'$'}y): static
            {
                ${'$'}local = function () { return 1; };
                return new static();
            }

            public function count(): int { return VERSION; }
        }

        interface Shape
        {
            public function area(): float;
        }

        trait Movable
        {
            public function move(): void {}
        }

        enum Suit: string
        {
            case Hearts = 'H';
            case Spades = 'S';

            public function label(): string { return ucfirst(${'$'}this->value); }
        }

        function helper(?Point ${'$'}p = null): void
        {
            echo VERSION;
        }
    """.trimIndent() + "\n"

    private val braced = """
        <?php
        namespace A {
            function a() {}
        }
        namespace {
            function b() {}
        }
    """.trimIndent() + "\n"

    private val html = "<html><body><?php function inline() {} ?><p>text</p></body></html>\n"

    private fun php(): Language {
        val byUpper = Language.findLanguageByID("PHP")
        val byLower = Language.findLanguageByID("php")
        println("S15 findLanguageByID(\"PHP\")=${byUpper?.javaClass?.name} findLanguageByID(\"php\")=${byLower?.javaClass?.name}")
        return byUpper ?: byLower ?: error("no PHP language registered")
    }

    private fun parse(text: String = code): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("sample.php", php(), text, false, false)
            ?: error("PsiFileFactory returned null")

    fun `test plugin is loaded and the language resolves`() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.jetbrains.php"))
        println("S15 pluginId=com.jetbrains.php present=${plugin != null} name=${plugin?.name} version=${plugin?.version} enabled=${plugin?.isEnabled}")
        assertNotNull(plugin)
        val lang = php()
        println("S15 language id=${lang.id} class=${lang.javaClass.name} displayName=${lang.displayName}")
        val loaded = PluginManagerCore.loadedPlugins.map { it.pluginId.idString }.sorted()
        println("S15 loadedPlugins=${loaded.joinToString(",")}")
    }

    fun `test text-built file parses and its declarations are described`() {
        for ((label, text) in listOf("statement" to code, "braced" to braced, "html" to html)) {
            val file = parse(text)
            println("S15 [$label] file class=${file.javaClass.name} language=${file.language.id} textEqual=${file.text == text} viewProviderLanguages=${file.viewProvider.languages.map { it.id }}")
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            println("S15 [$label] errors count=${errors.size}")
            for (e in errors) println("S15 [$label] error offset=${e.textRange.startOffset} description=${e.errorDescription}")

            val outDir = File(System.getProperty("smartdiff.spike.out") ?: "build/php-spike").apply { mkdirs() }
            File(outDir, "psi-tree-$label.txt").writeText(DebugUtil.psiToString(file, true, false))
            println("S15 [$label] psiTree written=${File(outDir, "psi-tree-$label.txt").absolutePath}")

            val interesting = Regex("Namespace|Class|Function|Method|Field|Constant|Const|Parameter|Attribute|Enum|Case|DocComment|Group|Use|Trait")
            PsiTreeUtil.processElements(file) { element ->
                if (element !is PsiWhiteSpace && element.firstChild != null) {
                    val cls = element.javaClass
                    val allNames = generateSequence<Class<*>>(cls) { it.superclass }.map { it.simpleName } + cls.interfaces.map { it.simpleName }
                    if (allNames.any { interesting.containsMatchIn(it) }) {
                        val range = element.textRange
                        val textOk = text.substring(range.startOffset, range.endOffset) == element.text
                        val name = (element as? PsiNamedElement)?.name
                        val snippet = element.text.lineSequence().first().take(50)
                        val depth = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.count()
                        println("S15 [$label] element depth=$depth class=${cls.name} name=$name range=$range textOk=$textOk text=\"$snippet\" parent=${element.parent?.javaClass?.simpleName} interfaces=${cls.interfaces.map { it.simpleName }}")
                        describeExtras(label, element)
                    }
                }
                true
            }

            for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
                val next = PsiTreeUtil.skipWhitespacesForward(comment)
                println("S15 [$label] comment text=\"${comment.text.lineSequence().first()}\" class=${comment.javaClass.name} parent=${comment.parent?.javaClass?.name} parentRange=${comment.parent?.textRange} nextSibling=${next?.javaClass?.name} nextRange=${next?.textRange}")
            }
        }
        assertEquals("no parse errors", 0, PsiTreeUtil.findChildrenOfType(parse(), PsiErrorElement::class.java).size)
    }

    /** Accessors found by reflection, so the spike does not compile against PHP PSI types. */
    private fun describeExtras(label: String, element: PsiElement) {
        val getters = listOf("getName", "getFQN", "getNameIdentifier", "getDocComment", "getAttributes", "getParameters",
            "getParameterList", "isVariadic", "isPassByRef", "isPromotedField", "getDeclaredType", "getTypeDeclaration",
            "getModifier", "getModifierList", "isInterface", "isTrait", "isEnum", "getOwnFields", "getOwnMethods",
            "getEnumCases", "isConstant", "getDefaultValue", "isStatements", "isBraced", "getStatements", "getFirstPsiChild")
        for (getter in getters) {
            val method = element.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } ?: continue
            val value = try { method.invoke(element) } catch (t: Throwable) { "threw ${t.javaClass.simpleName}" }
            val shown = when (value) {
                is PsiElement -> "${value.javaClass.simpleName}(\"${value.text.lineSequence().first().take(40)}\" ${value.textRange})"
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { (it as? PsiElement)?.let { e -> "${e.javaClass.simpleName}(\"${e.text.take(30)}\")" } ?: it.toString() }
                is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { (it as? PsiElement)?.let { e -> "${e.javaClass.simpleName}(\"${e.text.take(30)}\")" } ?: it.toString() }
                else -> value?.toString()
            }
            println("S15 [$label]   $getter -> $shown")
        }
    }

    fun `test in-file reference resolves without an interpreter`() {
        val file = parse()
        for (needle in listOf("echo VERSION" to "echo ".length, "?Point \$p" to 1, "use Movable" to "use ".length)) {
            val offset = code.indexOf(needle.first) + needle.second
            val reference = file.findReferenceAt(offset)
            val target = try { reference?.resolve() } catch (t: Throwable) { println("S15 resolve threw ${t.javaClass.name}: ${t.message}"); null }
            println("S15 reference at \"${needle.first}\" class=${reference?.javaClass?.name} -> class=${target?.javaClass?.name} name=${(target as? PsiNamedElement)?.name} offset=${target?.textRange?.startOffset}")
        }
    }
}
