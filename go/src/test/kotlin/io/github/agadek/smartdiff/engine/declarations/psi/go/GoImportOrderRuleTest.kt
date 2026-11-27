package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Go import order rule on the Go lexer's tokens (declarations/11 R19). */
class GoImportOrderRuleTest : BasePlatformTestCase() {

    private val rule = GoEquivalenceRules.ImportOrder
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "package p\n\n$a\n", "package p\n\n$b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "package p\n\n$a\n", "package p\n\n$b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R19
    fun `test the specs of a block are a set`() {
        same("import (\n\t\"fmt\"\n\t\"os\"\n)", "import (\n\t\"os\"\n\t\"fmt\"\n)")
        same("import (\n\t\"fmt\"\n\n\t\"example.com/x\"\n)", "import (\n\t\"example.com/x\"\n\t\"fmt\"\n)")
        same("import (\"fmt\"; \"os\")", "import (\n\t\"os\"\n\t\"fmt\"\n)")
    }

    // declarations/11-javascript-python-go-equivalence-rules R19
    fun `test an alias stays with its path`() {
        same("import (\n\tf \"fmt\"\n\t\"os\"\n)", "import (\n\t\"os\"\n\tf \"fmt\"\n)")
        same("import (\n\t. \"fmt\"\n\t_ \"os\"\n)", "import (\n\t_ \"os\"\n\t. \"fmt\"\n)")
        differ("import (\n\tf \"fmt\"\n\to \"os\"\n)", "import (\n\to \"fmt\"\n\tf \"os\"\n)")
        differ("import (\n\tf \"fmt\"\n\t\"os\"\n)", "import (\n\tg \"fmt\"\n\t\"os\"\n)")
    }

    // declarations/11-javascript-python-go-equivalence-rules R19
    fun `test what stays a change`() {
        differ("import (\n\t\"fmt\" // print\n\t\"os\"\n)", "import (\n\t\"os\"\n\t\"fmt\" // print\n)")
        differ("import \"fmt\"\nimport \"os\"", "import \"os\"\nimport \"fmt\"")
        differ("import (\n\t\"fmt\"\n\t\"os\"\n)", "import (\n\t\"fmt\"\n\t\"io\"\n)")
    }
}
