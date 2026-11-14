package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyNamedParameter
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.impl.PythonLanguageLevelPusher

// TECHNICAL-SPECIFICATION S7
class PythonS7SpikeTest : BasePlatformTestCase() {

    fun `test S7 a light virtual file honours the language level set before parsing`() {
        val vf = LightVirtualFile("forced-vf.py", PythonLanguage.getInstance(), "x = 1\n")
        PythonLanguageLevelPusher.specifyFileLanguageLevel(vf, LanguageLevel.PYTHON27)
        val file = ReadAction.compute<PsiFile, RuntimeException> {
            PsiManager.getInstance(project).findFile(vf) ?: error("no PSI file for the light virtual file")
        }
        assertEquals("the file-level setting is honoured before the tree is built", LanguageLevel.PYTHON27, levelOf(file))
        println("S7 forcedFileLevel=${levelOf(file)}")
    }

    fun `test S7 text-built Python file uses the latest level and parses Python 312 syntax`() {
        val text = """
            type Alias = int

            class C[T]:
                value: int = 0

                def f[T](x: T) -> T:
                    match x:
                        case 1:
                            return x
                        case _:
                            return x
        """.trimIndent() + "\n"

        val file = create(text, "latest.py")
        val level = levelOf(file)
        println("S7 latest=${LanguageLevel.getLatest()} fileLevel=$level fileClass=${file.javaClass.name} forcedNeeded=false")
        assertEquals("a text-built file uses the newest level", LanguageLevel.getLatest(), level)
        assertEquals("the Python parser is used", "Python", file.language.id)
        assertNoParseErrors(file, "Python 3.12 syntax")
    }

    fun `test S7 element text equals the request slice at LF and CRLF`() {
        val text = """
            type Alias = int

            MAX = 1
            x: int = 2

            class C[T]:
                VALUE: int = 0

                def f(self):
                    return helper(MAX, C)

            def helper(v, cls):
                match v:
                    case 1:
                        return cls
                    case _:
                        return None
        """.trimIndent() + "\n"

        val lf = create(text)
        assertSlicesExactly(text, lf, "LF")

        // A VCS revision with CRLF line ends is an LF document in a request; the raw CRLF text is checked too.
        val crlf = StringUtil.convertLineSeparators(text, "\r\n")
        val crlfFile = create(crlf)
        assertSlicesExactly(crlf, crlfFile, "CRLF")
        val crlfErrors = errorTexts(crlfFile)
        println("S7 crlfErrorCount=${crlfErrors.size} first=${crlfErrors.firstOrNull()}")
        assertTrue("the Python parser reports CRLF as errors, so requests must stay LF-separated", crlfErrors.isNotEmpty())
    }

    fun `test S7 lexer reports indentation, line break and statement break tokens`() {
        val parserDef = ReadAction.compute<com.intellij.lang.ParserDefinition, RuntimeException> {
            LanguageParserDefinitions.INSTANCE.forLanguage(PythonLanguage.getInstance())
                ?: error("no parser definition for Python")
        }
        val lexText = "def f():\n    x = 1\n    y = 2\n\n# c\n"
        val tokens = ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            val lexer = parserDef.createLexer(project)
            lexer.start(lexText)
            val result = mutableListOf<Pair<String, String>>()
            while (lexer.tokenType != null) {
                val type = lexer.tokenType!!
                result.add(type.debugName to lexer.tokenText)
                lexer.advance()
            }
            result
        }
        println("S7 tokens=" + tokens.joinToString(" | ") { (type, text) -> "$type:'${StringUtil.escapeStringCharacters(text)}'" })
        assertEquals("the tokens tile the source", lexText, tokens.joinToString("") { it.second })

        val types = tokens.map { it.first }.toSet()
        assertTrue("INDENT is emitted", "INDENT" in types)
        assertTrue("DEDENT is emitted", "DEDENT" in types)
        assertTrue("STATEMENT_BREAK is emitted", "STATEMENT_BREAK" in types)
        assertTrue("LINE_BREAK is emitted", "LINE_BREAK" in types)
        assertTrue("a comment is emitted", "END_OF_LINE_COMMENT" in types)

        val (whitespace, comments) = ReadAction.compute<Pair<Set<String>, Set<String>>, RuntimeException> {
            parserDef.whitespaceTokens.types.map { it.debugName }.toSet() to
                parserDef.commentTokens.types.map { it.debugName }.toSet()
        }
        val pyWhitespace = ReadAction.compute<Set<String>, RuntimeException> { PyTokenTypes.WHITESPACE.types.map { it.debugName }.toSet() }
        println("S7 whitespaceTokens=$whitespace commentTokens=$comments PyTokenTypes.WHITESPACE=$pyWhitespace")
        assertEquals("the whitespace set is line break, space, tab and form feed", setOf("LINE_BREAK", "SPACE", "TAB", "FORMFEED"), whitespace)
        assertEquals("the comment set is the end-of-line comment", setOf("END_OF_LINE_COMMENT"), comments)
        assertEquals("PyTokenTypes.WHITESPACE is space, tab and form feed", setOf("SPACE", "TAB", "FORMFEED"), pyWhitespace)
        assertFalse("INDENT is not whitespace", "INDENT" in whitespace)
        assertFalse("DEDENT is not whitespace", "DEDENT" in whitespace)
        assertFalse("STATEMENT_BREAK is not whitespace", "STATEMENT_BREAK" in whitespace)
    }

    fun `test S7 type comments are comments and parse without errors`() {
        val text = "x = 1  # type: int\n# plain\n"
        val parserDef = ReadAction.compute<com.intellij.lang.ParserDefinition, RuntimeException> {
            LanguageParserDefinitions.INSTANCE.forLanguage(PythonLanguage.getInstance())
                ?: error("no parser definition for Python")
        }
        val commentTokens = ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            val lexer = parserDef.createLexer(project)
            lexer.start(text)
            val result = mutableListOf<Pair<String, String>>()
            while (lexer.tokenType != null) {
                val type = lexer.tokenType!!
                if (parserDef.commentTokens.contains(type)) result.add(type.debugName to lexer.tokenText)
                lexer.advance()
            }
            result
        }
        println("S7 typeCommentTokens=" + commentTokens.joinToString(" | ") { (type, text) -> "$type:'${StringUtil.escapeStringCharacters(text)}'" })
        assertEquals(listOf("# type: int", "# plain"), commentTokens.map { it.second })
        assertTrue("type comments are comment tokens", commentTokens.all { it.first == "END_OF_LINE_COMMENT" })

        val file = create(text + "y = 2\n")
        assertNoParseErrors(file, "type comments")
    }

    fun `test S7 in-file references resolve without an SDK`() {
        val text = """
            MAX = 1

            def helper():
                return MAX

            class C:
                def method(self):
                    return 1

                def f(self):
                    return self.method()

            used = helper()
            made = C()
        """.trimIndent() + "\n"

        val file = create(text)
        assertNoParseErrors(file, "references")
        val resolved = ReadAction.compute<Map<String, PsiElement?>, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(file, PyReferenceExpression::class.java)
                .associate { it.text to it.reference.resolve() as? PsiElement }
        }
        for ((name, target) in resolved) println("S7 resolve '$name' -> ${target?.javaClass?.name}")
        assertTrue("a module variable resolves to a target", resolved["MAX"] is PyTargetExpression)
        assertTrue("a function reference resolves to a function", resolved["helper"] is PyFunction)
        assertTrue("a class reference resolves to a class", resolved["C"] is PyClass)
        assertTrue("a self.method call resolves to a method", resolved["self.method"] is PyFunction)
        assertTrue("self resolves to a parameter", resolved["self"] is PyNamedParameter)
    }

    private fun create(text: String, name: String = "a.py"): PsiFile =
        ApplicationManager.getApplication().runWriteAction(Computable {
            PsiFileFactory.getInstance(project)
                .createFileFromText(name, PythonLanguage.getInstance(), text, false, false)
                ?: error("createFileFromText returned null for $name")
        })

    private fun levelOf(file: PsiFile): LanguageLevel = ReadAction.compute<LanguageLevel, RuntimeException> {
        LanguageLevel.forElement(file)
    }

    private fun errors(file: PsiFile): List<PsiErrorElement> = ReadAction.compute<List<PsiErrorElement>, RuntimeException> {
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).toList()
    }

    private fun errorTexts(file: PsiFile): List<String> = errors(file).map { it.errorDescription + "@" + it.textRange }

    private fun assertNoParseErrors(file: PsiFile, label: String) {
        val errors = errorTexts(file)
        assertTrue("$label has no PsiErrorElement, found $errors", errors.isEmpty())
    }

    private fun assertSlicesExactly(text: String, file: PsiFile, label: String) {
        ReadAction.run<RuntimeException> {
            val classes = PsiTreeUtil.findChildrenOfType(file, PyClass::class.java)
            val functions = PsiTreeUtil.findChildrenOfType(file, PyFunction::class.java)
            val targets = PsiTreeUtil.findChildrenOfType(file, PyTargetExpression::class.java).filter {
                PsiTreeUtil.getParentOfType(it, PyFunction::class.java) == null
            }
            println("S7 counts $label classes=${classes.size} functions=${functions.size} targets=${targets.size}")
            for ((kind, elements) in listOf(
                "PyClass" to classes,
                "PyFunction" to functions,
                "PyTargetExpression" to targets,
            )) {
                for (element in elements) {
                    val range = element.textRange
                    val expected = text.substring(range.startOffset, range.endOffset)
                    val actual = element.text
                    println("S7 slice $label $kind ${element.javaClass.simpleName} range=$range")
                    assertEquals("$label $kind text equals the request slice", expected, actual)
                }
            }
            assertTrue("$label has declarations", classes.isNotEmpty() && functions.isNotEmpty() && targets.isNotEmpty())
        }
    }
}
