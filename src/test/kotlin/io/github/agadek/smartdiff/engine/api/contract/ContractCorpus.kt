package io.github.agadek.smartdiff.engine.api.contract

/** One text pair of the contract corpus. Texts are LF-separated, like the document snapshots engines receive. */
data class ContractCase(
    val name: String,
    val languageId: String,
    val oldText: String,
    val newText: String,
    /** At least one side does not parse; the engine may give any status but must not throw. */
    val malformed: Boolean = false,
) {
    override fun toString(): String = "$name ($languageId)"
}

/** The shared text pairs every engine is checked on (technical specification §12). */
object ContractCorpus {
    const val JAVA = "JAVA"
    const val KOTLIN = "kotlin"
    const val TEXT = "TEXT"

    val languages: List<String> = listOf(JAVA, KOTLIN, TEXT)

    private val javaBase = """
        package shapes;

        import java.util.List;

        /** Shapes. */
        public class Shapes {
            private int count;

            public Shapes(int count) {
                this.count = count;
            }

            public int area(int side) {
                return side * side;
            }

            public int area(int width, int height) {
                return width * height;
            }

            public String name() {
                return "shapes";
            }

            static class Inner {
                void helper() {}
            }
        }

    """.trimIndent()

    private val kotlinBase = """
        package shapes

        import kotlin.math.max

        /** Shapes. */
        class Shapes(private val count: Int) {
            val size: Int get() = count

            fun area(side: Int): Int = side * side

            fun area(width: Int, height: Int): Int {
                return width * height
            }

            fun name(): String = "shapes"

            companion object {
                const val LIMIT = 10
            }
        }

        fun top(a: Int, b: Int) = max(a, b)

    """.trimIndent()

    private const val NAME_BODY =
        "StringBuilder text = new StringBuilder(\"shapes\"); for (int i = 0; i < count; i++) { text.append(i).append(','); } return text.toString();"

    private val textBase = "first line\nsecond line\nthird line\n"

    val cases: List<ContractCase> = listOf(
        java("method added", javaBase.replace("    static class Inner", "    public void reset() {\n        count = 0;\n    }\n\n    static class Inner")),
        java("method removed", javaBase.replace("    public String name() {\n        return \"shapes\";\n    }\n\n", "")),
        java("method body changed", javaBase.replace("return side * side;", "return side * side * 1;")),
        java("overload signature changed", javaBase.replace("area(int width, int height)", "area(long width, int height)")),
        java("methods reordered", javaBase.replace(
            "    public int area(int side) {\n        return side * side;\n    }\n\n    public int area(int width, int height) {\n        return width * height;\n    }\n",
            "    public int area(int width, int height) {\n        return width * height;\n    }\n\n    public int area(int side) {\n        return side * side;\n    }\n",
        )),
        java("method moved to nested class", javaBase
            .replace("    public String name() {\n        return \"shapes\";\n    }\n\n", "")
            .replace("        void helper() {}\n", "        void helper() {}\n\n        public String name() {\n            return \"shapes\";\n        }\n")),
        java("class renamed", javaBase.replace("class Shapes", "class Figures").replace("public Shapes(", "public Figures(")),
        java("formatting only", javaBase.replace("return width * height;", "return   width*height ;").replace("    private int count;", "    private   int count ;")),
        java("comment only", javaBase.replace("/** Shapes. */", "/** All the shapes. */")),
        java("string literal changed", javaBase.replace("\"shapes\"", "\"figures\"")),
        java("import and package changed", javaBase.replace("import java.util.List;", "import java.util.Map;").replace("package shapes;", "package figures;")),
        java("parse error on new side", javaBase.replace("public int area(int side) {", "public int area(int side {"), malformed = true),
        ContractCase(
            "parse error on both sides", JAVA,
            javaBase.replace("private int count;", "private int count"),
            javaBase.replace("return side * side;", "return side * ;"),
            malformed = true,
        ),
        ContractCase(
            "method extracted", JAVA,
            javaBase.replace("        return \"shapes\";\n", "        $NAME_BODY\n"),
            javaBase.replace("        return \"shapes\";\n    }\n", "        return describe();\n    }\n\n    private String describe() {\n        $NAME_BODY\n    }\n"),
        ),
        java("unbalanced garbage", "}}} class { ( \"unterminated\n", malformed = true),
        kotlin("function added", kotlinBase.replace("    companion object", "    fun reset() = Unit\n\n    companion object")),
        kotlin("property changed", kotlinBase.replace("get() = count", "get() = count + 1")),
        kotlin("line break only", kotlinBase.replace("fun name(): String = \"shapes\"", "fun name(): String =\n        \"shapes\"")),
        kotlin("functions reordered", kotlinBase.replace(
            "fun top(a: Int, b: Int) = max(a, b)\n",
            "",
        ).replace("/** Shapes. */", "fun top(a: Int, b: Int) = max(a, b)\n\n/** Shapes. */")),
        ContractCase("parse error on old side", KOTLIN, kotlinBase.replace("fun area(side: Int)", "fun area(side: Int"), kotlinBase, malformed = true),
        text("one line changed", textBase.replace("second", "2nd")),
        text("lines added and removed", "zeroth line\nfirst line\nthird line\nfourth line\n"),
        text("missing final line break", textBase.removeSuffix("\n")),
        text("whitespace only", textBase.replace("second line", "second\tline ")),
        text("surrogate pairs", textBase.replace("second", "sec😀ond")),
    )

    /** Pairs with an empty side, for every language. */
    val emptyCases: List<ContractCase> = listOf(JAVA to javaBase, KOTLIN to kotlinBase, TEXT to textBase).flatMap { (language, text) ->
        listOf(
            ContractCase("both empty", language, "", ""),
            ContractCase("old side empty", language, "", text),
            ContractCase("new side empty", language, text, ""),
            ContractCase("blank and empty", language, "\n\n  \n", ""),
        )
    }

    /** Each well-formed side of the corpus compared with itself. */
    val identicalCases: List<ContractCase> =
        (listOf(java("base", javaBase), kotlin("base", kotlinBase), text("base", textBase)) + cases.filterNot { it.malformed })
            .map { ContractCase("identical: ${it.name}", it.languageId, it.newText, it.newText) }

    /** A large pair in [languageId], for cancellation: [size] members, every tenth changed on the new side. */
    fun large(languageId: String, size: Int = 300): ContractCase {
        fun side(changed: Boolean): String = buildString {
            append(if (languageId == KOTLIN) "class Large {\n" else "public class Large {\n")
            for (i in 0 until size) {
                val body = if (changed && i % 10 == 0) "i + $i" else "i * $i"
                if (languageId == KOTLIN) append("    fun m$i(i: Int): Int = $body\n\n")
                else append("    int m$i(int i) {\n        return $body;\n    }\n\n")
            }
            append("}\n")
        }
        return ContractCase("large", languageId, side(changed = false), side(changed = true))
    }

    private fun java(name: String, newText: String, malformed: Boolean = false) = ContractCase(name, JAVA, javaBase, newText, malformed)
    private fun kotlin(name: String, newText: String, malformed: Boolean = false) = ContractCase(name, KOTLIN, kotlinBase, newText, malformed)
    private fun text(name: String, newText: String) = ContractCase(name, TEXT, textBase, newText)
}
