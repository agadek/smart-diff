package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiErrorElement
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.LanguageLevel
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.Rendering

/** The Python provider's declarations, names, signatures and ranges (languages/04-python). */
class PythonDeclarationProviderTest : ProviderTestCase() {

    // languages/04-python R1, R2
    fun `test R1 R2 provider is registered for py and pyi`() {
        val provider = provider("Python")
        assertTrue(provider.supports("Python"))
        val py = build("Python", "def f():\n    pass\n", "a.py")
        assertEquals(1, py.topLevel.size)
        val pyi = build("Python", "def f() -> int: ...\n", "a.pyi")
        assertEquals(1, pyi.topLevel.size)
        assertEquals(DeclarationKind.FUNCTION, pyi.topLevel.single().kind)
    }

    // languages/04-python R3
    fun `test R3 classes and functions including async methods and nested classes`() {
        val text = """
            class Outer:
                class Inner:
                    pass

                def method(self):
                    pass

                async def fetch(self):
                    pass

            async def top():
                pass

            def plain():
                pass
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertTree(
            """
            class Outer  "class Outer:"
              class Inner  "class Inner:"
              function method(self)  "def method(self):"
              function fetch(self)  "async def fetch(self):"
            function top()  "async def top():"
            function plain()  "def plain():"
            """,
            tree, text,
        )
    }

    // languages/04-python R4
    fun `test R4 module and class level assigned and annotated names are variables`() {
        val text = """
            MAX = 3
            x: int
            y: int = 1

            @dataclass
            class Config:
                name: str
                count: int = 0
                FLAG = True
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertTree(
            """
            variable MAX  "MAX = 3"
            variable x  "x: int"
            variable y  "y: int = 1"
            class Config  "@dataclass"
              variable name  "name: str"
              variable count  "count: int = 0"
              variable FLAG  "FLAG = True"
            """,
            tree, text,
        )
    }

    // languages/04-python R5
    fun `test R5 tuple assignment is one variable`() {
        val text = """
            a, b = 1, 2
            (c, d) = pair
            head, *rest = items
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertEquals(listOf("(a, b)", "(c, d)", "(head, *rest)"), tree.topLevel.map { it.name })
        assertEquals(DeclarationKind.VARIABLE, tree.topLevel.first().kind)
    }

    // languages/04-python R6
    fun `test R6 type alias statement is a declaration`() {
        val text = """
            type Alias = int
            type Pair[T] = tuple[T, T]
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertTree(
            """
            type alias Alias  "type Alias = int"
            type alias Pair  "type Pair[T] = tuple[T, T]"
            """,
            tree, text,
        )
    }

    // languages/04-python R7
    fun `test R7 definitions in compound statements at module and class level`() {
        val text = """
            if TYPE_CHECKING:
                class A:
                    pass
            elif DEBUG:
                def debug():
                    pass
            else:
                def release():
                    pass

            try:
                class T:
                    pass
            except ImportError:
                class T:
                    pass
            finally:
                class Cleanup:
                    pass

            with ctx():
                def inside_with():
                    pass

            for i in range(1):
                class Loop:
                    pass

            while False:
                def never():
                    pass

            class Box:
                if FLAG:
                    def method(self):
                        pass
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertEquals(
            listOf("A", "debug", "release", "T", "T", "Cleanup", "inside_with", "Loop", "never", "Box"),
            tree.topLevel.map { it.name },
        )
        assertEquals(listOf("method"), tree.topLevel.last().children.map { it.name })
    }

    // languages/04-python R8
    fun `test R8 definitions inside function bodies and non-declarations are not declarations`() {
        val text = """
            import os
            from x import y

            def outer():
                def inner():
                    pass
                class Local:
                    x = 1
                value = 1
                self.attr = 1
                global g
                nonlocal n
                if __name__ == "__main__":
                    main()
                return [i for i in range(3)]

            MAX = 1
            MAX += 1
            obj.attr = 1
            items[0] = 1
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertEquals(listOf("outer", "MAX"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.first().children.isEmpty())
    }

    // languages/04-python R9
    fun `test R9 range includes decorators and directly preceding own-line comments`() {
        val text = """
            # helper
            @decorator
            @other(x)
            def f():
                pass

            # detached

            # class comment
            class C:
                # method comment
                @m
                def m(self):
                    pass
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        val f = tree.topLevel.first { it.name == "f" }
        assertEquals("f starts at its own-line leading comment", text.indexOf("# helper"), f.range.start)
        assertTrue(text.substring(f.range.start, f.range.end).contains("@decorator"))

        val c = tree.topLevel.first { it.name == "C" }
        assertEquals("C starts at its directly preceding own-line comment", text.indexOf("# class comment"), c.range.start)
        assertFalse("comment separated by blank line excluded from C", text.substring(c.range.start, c.range.end).contains("# detached"))

        val m = c.children.single()
        assertEquals("method starts at its own-line leading comment", text.indexOf("# method comment"), m.range.start)
    }

    // languages/04-python R9
    fun `test R9 trailing comment of previous line stays out of next declaration`() {
        val text = "x = 1  # note\ndef f():\n    pass\n"
        val tree = build("Python", text)
        val f = tree.topLevel.first { it.name == "f" }
        assertEquals("function does not take trailing comment of previous line", text.indexOf("def f"), f.range.start)
        assertFalse(text.substring(f.range.start, f.range.end).contains("# note"))
    }

    // languages/04-python R9
    fun `test R9 leading own-line comment applies to variables`() {
        val text = """
            # max retries
            MAX = 3

            # pair
            a, b = 1, 2

            # count
            count: int

            # chained
            x = y = 0
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        val max = tree.topLevel.first { it.name == "MAX" }
        assertEquals("variable takes own-line leading comment", text.indexOf("# max retries"), max.range.start)

        val pair = tree.topLevel.first { it.name == "(a, b)" }
        assertEquals("tuple variable takes own-line leading comment", text.indexOf("# pair"), pair.range.start)

        val count = tree.topLevel.first { it.name == "count" }
        assertEquals("annotated variable takes own-line leading comment", text.indexOf("# count"), count.range.start)

        val x = tree.topLevel.first { it.name == "x" }
        val y = tree.topLevel.first { it.name == "y" }
        assertEquals("first target of chained assignment takes own-line leading comment", text.indexOf("# chained"), x.range.start)
        assertEquals("later target of chained assignment does not take leading comment", text.indexOf("y = 0"), y.range.start)
    }

    // languages/04-python R10
    fun `test R10 adding removing or changing a decorator is a change`() {
        val old = "@a\ndef f():\n    pass\n"
        val added = "@a\n@b\ndef f():\n    pass\n"
        val removed = "def f():\n    pass\n"
        val changed = "@b\ndef f():\n    pass\n"

        assertEquals(Content.CHANGED, compare(old, added).changes.single { it.isReported }.content)
        assertEquals(Content.CHANGED, compare(old, removed).changes.single { it.isReported }.content)
        assertEquals(Content.CHANGED, compare(old, changed).changes.single { it.isReported }.content)
    }

    // languages/04-python R11
    fun `test R11 function signature is parameter names with markers`() {
        val text = """
            def f(self, a: int = 1, /, b, *args, key=None, **kw):
                pass

            def g(x, *, y):
                pass

            def h():
                pass

            class C:
                @staticmethod
                def s(a):
                    pass
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        val f = tree.topLevel.first { it.name == "f" }
        assertEquals("self,a,/,b,*args,key,**kw", f.signature)
        assertEquals("self, a, /, b, *args, key, **kw", f.displaySignature)
        val g = tree.topLevel.first { it.name == "g" }
        assertEquals("x,*,y", g.signature)
        assertEquals("", tree.topLevel.first { it.name == "h" }.signature)
        val s = tree.topLevel.first { it.name == "C" }.children.single()
        assertEquals("a", s.signature)
    }

    // languages/04-python R12
    fun `test R12 overloads and property getter setter are separate declarations`() {
        val text = """
            from typing import overload

            @overload
            def f(x: int) -> int: ...
            @overload
            def f(x: str) -> str: ...
            def f(x):
                return x

            class C:
                @property
                def value(self):
                    return self._v

                @value.setter
                def value(self, v):
                    self._v = v
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        val fs = tree.topLevel.filter { it.name == "f" }
        assertEquals(3, fs.size)
        assertEquals(DeclarationKind.FUNCTION, fs.first().kind)
        val values = tree.topLevel.first { it.name == "C" }.children
        assertEquals(2, values.size)
        assertTrue(values.all { it.name == "value" && it.kind == DeclarationKind.FUNCTION })
    }

    // languages/04-python R13
    fun `test R13 indentation change is never formatting-only`() {
        val old = "def f():\n    a()\n    b()\n"
        val new = "def f():\n  a()\n  b()\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single { it.isReported }.content)
    }

    // languages/04-python R14
    fun `test R14 line break inside a declaration is a change`() {
        val old = "def f():\n    g(a, b)\n"
        val new = "def f():\n    g(a,\n      b)\n"
        val result = compare(old, new)
        assertEquals(Content.CHANGED, result.changes.single { it.isReported }.content)
    }

    // languages/04-python R15
    fun `test R15 intra-line spaces and blank lines are formatting-only`() {
        val old = "def f():\n    x = a+b\n"
        val new = "def f():\n\n    x  =  a + b\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single { it.isReported }.content)
    }

    // languages/04-python R16
    fun `test R16 docstring change is a content change`() {
        val old = "def f():\n    \"\"\"one\"\"\"\n    return 1\n"
        val new = "def f():\n    \"\"\"two\"\"\"\n    return 1\n"
        val result = compare(old, new)
        assertEquals(Content.CHANGED, result.changes.single { it.isReported }.content)
    }

    // languages/04-python R17
    fun `test R17 parses at latest Python level`() {
        val text = """
            type Alias = int

            class C[T]:
                value: int = 0

                def f(x):
                    match x:
                        case 1:
                            return x
                        case _:
                            return x
        """.trimIndent() + "\n"

        val tree = build("Python", text)
        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
        val level = ReadAction.compute<LanguageLevel, RuntimeException> {
            val file = ApplicationManager.getApplication().runWriteAction(Computable<PsiFile> {
                PsiFileFactory.getInstance(project).createFileFromText("l.py", PythonLanguage.getInstance(), text, false, false)
            })
            LanguageLevel.forElement(file)
        }
        assertEquals(LanguageLevel.getLatest(), level)
    }

    // languages/04-python R4: several targets a = b = 0
    fun `test several assignment targets give separate variables`() {
        val text = "a = b = 0\n"
        val tree = build("Python", text)
        assertEquals(listOf("a", "b"), tree.topLevel.map { it.name })
        assertEquals(2, tree.topLevel.size)
    }

    private fun compare(old: String, new: String) =
        DeclarationEngine(null).compare(ComparisonRequest(old, new, "Python", "a.py"), Cancellation.NONE)
}
