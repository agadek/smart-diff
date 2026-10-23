package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy

/** The Java provider's declarations, names, signatures and ranges (technical specification §6.4, §6.6, §6.7). */
class JavaDeclarationProviderTest : ProviderTestCase() {

    fun `test bodies of methods and constructors are inside their braces`() {
        val text = """
            abstract class A {
                A(int x) { this.x = x; }
                int twice(int x) {
                    return 2 * x;
                }
                abstract void none();
                int field = 1;
            }
            interface I { void f(); default int g() { return 1; } }
        """.trimIndent() + "\n"

        assertBodies(
            """
            constructor A(int): this.x = x;
            method twice(int): return 2 * x;
            method g(): return 1;
            """,
            build("JAVA", text), text,
        )
    }

    fun `test declarations of every kind, in source order`() {
        val text = """
            package demo;

            import java.util.List;

            /** Shapes. */
            @Deprecated
            public class Shapes {
                static { System.out.println(); }

                { count = 1; }

                private int a, b;

                int count;

                public Shapes(int a) { this.a = a; }

                <T> List<T> list(final java.util.Map<String, /* keys */ List<Integer>> m, @Deprecated int... xs) { return null; }

                int legacy(int values[], @SuppressWarnings("x") String s) { return 0; }

                Runnable task = new Runnable() { public void run() { class Local {} } };

                enum Kind { CIRCLE, SQUARE { int sides() { return 4; } } }

                record Point(int x, int y) { Point { } static Point origin() { return new Point(0, 0); } }

                sealed interface Shape permits Circle {}

                final class Circle implements Shape {}

                @interface Marker { String value() default ""; }
            }

            class Second {}
        """.trimIndent() + "\n"

        val tree = build("JAVA", text)

        assertTree(
            """
            class Shapes  "/** Shapes. */"
              initializer static  "static { System.out.println(); }"
              initializer  "{ count = 1; }"
              field a  "private int a"
              field b  "b;"
              field count  "int count;"
              constructor Shapes(int)  "public Shapes(int a) { this.a = a; }"
              method list(java.util.Map<String,List<Integer>>,int...)  "<T> List<T> list(final java.util.Map<String, /* keys */ List<Integer>> m, @Deprecated int... xs) { return null; }"
              method legacy(int[],String)  "int legacy(int values[], @SuppressWarnings("x") String s) { return 0; }"
              field task  "Runnable task = new Runnable() { public void run() { class Local {} } };"
              enum Kind  "enum Kind { CIRCLE, SQUARE { int sides() { return 4; } } }"
                enum constant CIRCLE  "CIRCLE"
                enum constant SQUARE  "SQUARE { int sides() { return 4; } }"
              record Point  "record Point(int x, int y) { Point { } static Point origin() { return new Point(0, 0); } }"
                constructor Point()  "Point { }"
                method origin()  "static Point origin() { return new Point(0, 0); }"
              interface Shape  "sealed interface Shape permits Circle {}"
              class Circle  "final class Circle implements Shape {}"
              annotation type Marker  "@interface Marker { String value() default ""; }"
                method value()  "String value() default "";"
            class Second  "class Second {}"
            """,
            tree, text,
        )
        assertEquals(WhitespacePolicy.INSIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test tokens come from the Java lexer`() {
        val text = "/** Doc. */\nclass A {\n    // note\n    int x = 1; /* block */\n}\n"

        val tree = build("JAVA", text)

        val tokens = tree.fileRegion.tokens + tree.all.flatMap { it.ownTokens }
        assertEquals(listOf("/* block */", "/** Doc. */", "// note"), tokens.filter { it.kind == TokenKind.COMMENT }.map { it.text }.sorted())
        assertTrue(tokens.filter { it.kind == TokenKind.WHITESPACE }.all { it.text.isBlank() })
        assertEquals(listOf("class", "A", "{", "}"), tree.topLevel.single().ownTokens.filter { it.kind == TokenKind.OTHER }.map { it.text })
    }

    fun `test a parse error marks the innermost declaration and keeps the others`() {
        val text = "class Broken {\n    void ok() {}\n    void bad(int x {\n    }\n    int after;\n}\n"

        val tree = build("JAVA", text)

        val broken = tree.topLevel.single()
        assertEquals("Broken", broken.name)
        val ok = broken.children.first { it.name == "ok" }
        assertFalse("ok() has no parse error", ok.hasParseError)
        val bad = broken.children.first { it.name == "bad" }
        assertTrue("bad() has the parse error: ${Rendering.tree(tree, text)}", bad.hasParseError)
        assertFalse(broken.hasParseError)
        assertFalse(tree.fileRegion.hasParseError)
    }

    fun `test an error outside all declarations marks the file region`() {
        val text = "package demo\n\nclass A {}\n"

        val tree = build("JAVA", text)

        assertTrue(tree.fileRegion.hasParseError)
        assertFalse(tree.topLevel.single().hasParseError)
    }

    fun `test a class whose name was not parsed belongs to its container`() {
        val text = "class Outer {\n    class {\n        void inner() {}\n    }\n}\n"

        val tree = build("JAVA", text)

        val outer = tree.topLevel.single()
        assertTrue("the nameless class is not a declaration: ${Rendering.tree(tree, text)}", outer.children.none { it.kind.name == "CLASS" })
        assertTrue("its parse error belongs to the container", outer.hasParseError)
    }

    fun `test modern syntax parses without errors whatever the default project's language level`() {
        val text = """
            sealed interface Shape permits Circle {}
            record Circle(int r) implements Shape {}
            class Use {
                String describe(Object o) {
                    return switch (o) {
                        case Circle(int r) when r > 0 -> ""${'"'}
                            text block
                            ""${'"'};
                        default -> "";
                    };
                }
            }
        """.trimIndent() + "\n"

        val tree = build("JAVA", text)

        assertEquals(listOf("Shape", "Circle", "Use"), tree.topLevel.map { it.name })
        assertFalse(tree.fileRegion.hasParseError)
        assertTrue(tree.all.none { it.hasParseError })
    }

    fun `test top-level methods of an implicitly declared class are top-level declarations`() {
        val text = "void main() {\n    System.out.println(greeting());\n}\n\nString greeting() { return \"hi\"; }\n"

        val tree = build("JAVA", text)

        assertEquals(listOf("main", "greeting"), tree.topLevel.map { it.name })
    }

    fun `test empty and blank files have no declarations`() {
        for (text in listOf("", "\n\n   \n")) {
            val tree = build("JAVA", text)
            assertEmpty(tree.topLevel)
            assertFalse(tree.fileRegion.hasParseError)
        }
    }
}
