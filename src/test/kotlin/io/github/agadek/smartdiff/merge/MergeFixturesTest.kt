package io.github.agadek.smartdiff.merge

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.ui.merge.MergeOutlineItem
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout
import io.github.agadek.smartdiff.ui.merge.MergeOutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeSideName
import org.junit.Assert.assertNotEquals
import java.io.File
import java.util.concurrent.Executor

/**
 * Golden merge fixtures (merge/05-scala-merges): `src/test/testData/<language>/merge/<case>/{base,left,right}.<ext>`
 * and `expected-smartdiff.declarations.txt`, the joined rows as [MergeRendering] writes them.
 *
 * Every fixture also hides nothing on either side. Run with `-Psmartdiff.updateGolden=true` to rewrite the expected
 * files from the current results; review every change before committing.
 */
class MergeFixturesTest : BasePlatformTestCase() {

    private val engine by lazy { DeclarationEngine(project) }
    private val scala by lazy { DeclarationProvider.EP_NAME.extensionList.first { it.supports("Scala") } }
    private val executor = Executor { it.run() }

    fun `test Java merge fixtures`() = checkFixtures("java", "JAVA", "java")

    fun `test Kotlin merge fixtures`() = checkFixtures("kotlin", "kotlin", "kt")

    fun `test Scala 2 merge fixtures`() = checkFixtures("scala2", "Scala", "scala")

    fun `test Scala 3 merge fixtures`() = checkFixtures("scala3", "Scala", "scala")

    fun `test merge fixture files live in the language merge folders`() {
        // merge/05-scala-merges R9
        for ((directory, extension) in LANGUAGE_EXTENSIONS) {
            val cases = mergeCases(directory)
            assertTrue("no $directory merge fixtures", cases.isNotEmpty())
            for (case in cases) {
                for (name in listOf("base", "left", "right")) {
                    assertTrue("${case.path}/$name.$extension", File(case, "$name.$extension").isFile)
                }
            }
        }
    }

    fun `test every language covers the required merge scenarios`() {
        // merge/05-scala-merges R11
        for ((directory, _) in LANGUAGE_EXTENSIONS) {
            val present = mergeCases(directory).map { it.name }.toSet()
            for (scenario in REQUIRED_SCENARIOS) {
                assertTrue("$directory merge fixtures: $scenario", scenario in present)
            }
        }
    }

    fun `test Scala 3 covers braces to indentation and a given on both sides`() {
        // merge/05-scala-merges R12
        assertTrue(File(TEST_DATA, "scala3/merge/braces-to-indentation").isDirectory)
        assertTrue(File(TEST_DATA, "scala3/merge/given-and-extension").isDirectory)

        val joined = joinFixture("scala3", "given-and-extension", "Scala", "scala")
        val given = joined.nodes.firstOrNull { it.subject is Subject.Declaration && it.subject.kind == DeclarationKind.GIVEN }
        assertNotNull("a given row", given)
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, given!!.group)
    }

    fun `test Scala 2 and Scala 3 cover a three-text dialect choice`() {
        // merge/05-scala-merges R13
        for (directory in listOf("scala2", "scala3")) {
            val case = File(TEST_DATA, "$directory/merge/dialect-mix")
            val base = read(File(case, "base.scala"))
            val left = read(File(case, "left.scala"))
            val right = read(File(case, "right.scala"))
            val all = dialect(base, left, right)
            assertEquals("$directory all three", if (directory == "scala2") "Scala" else "Scala 3", all)
            assertEquals("$directory all three", all, dialect(base, right, left))
            assertTrue(
                "$directory base + one side should differ from all three: $all",
                listOf(dialect(base, left), dialect(base, right)).any { it != all },
            )
        }
    }

    fun `test Scala merge reads the base and both sides in one dialect`() {
        // merge/05-scala-merges R1
        for (directory in listOf("scala2", "scala3")) {
            for (case in mergeCases(directory)) {
                val base = read(File(case, "base.scala"))
                val left = read(File(case, "left.scala"))
                val right = read(File(case, "right.scala"))
                val leftRun = engine.trees(ComparisonRequest(base, left, "Scala", "${case.name}.scala", relatedTexts = listOf(right)), Cancellation.NONE)!!
                val rightRun = engine.trees(ComparisonRequest(base, right, "Scala", "${case.name}.scala", relatedTexts = listOf(left)), Cancellation.NONE)!!
                assertEquals("$directory/${case.name} left base and side", leftRun.first.whitespacePolicy, leftRun.second.whitespacePolicy)
                assertEquals("$directory/${case.name} right base and side", rightRun.first.whitespacePolicy, rightRun.second.whitespacePolicy)
                assertEquals("$directory/${case.name} base", leftRun.first.whitespacePolicy, rightRun.first.whitespacePolicy)
                assertEquals("$directory/${case.name} side", leftRun.second.whitespacePolicy, rightRun.second.whitespacePolicy)
            }
        }
    }

    fun `test Scala dialect is chosen from all three texts`() {
        // merge/05-scala-merges R2
        for (directory in listOf("scala2", "scala3")) {
            val case = File(TEST_DATA, "$directory/merge/dialect-mix")
            val base = read(File(case, "base.scala"))
            val left = read(File(case, "left.scala"))
            val right = read(File(case, "right.scala"))
            val all = dialect(base, left, right)
            assertEquals(all, dialect(base, right, left))
            assertTrue(listOf(dialect(base, left), dialect(base, right)).any { it != all })
        }
    }

    fun `test Scala 2 code without Scala 3 syntax is read as Scala 2`() {
        // merge/05-scala-merges R3
        val case = File(TEST_DATA, "scala2/merge/changed-on-one-side")
        assertEquals(
            "Scala",
            dialect(read(File(case, "base.scala")), read(File(case, "left.scala")), read(File(case, "right.scala"))),
        )
    }

    fun `test a Scala 3 braces to indentation change is a change on that side`() {
        // merge/05-scala-merges R4
        val joined = joinFixture("scala3", "braces-to-indentation", "Scala", "scala")
        val classRow = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "OrderService" }
        val left = classRow.left ?: error("no left class node")
        assertEquals(Content.CHANGED, left.content)
    }

    fun `test a Scala 2 re-indent is formatting-only on one side`() {
        // merge/05-scala-merges R5
        val joined = joinFixture("scala2", "formatting-versus-change", "Scala", "scala")
        val classRow = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "OrderService" }
        val total = classRow.children.first { it.subject is Subject.Declaration && it.subject.name == "total" }
        assertEquals(Content.FORMATTING_ONLY, total.left!!.content)
    }

    fun `test a Scala class and its companion object are separate rows`() {
        // merge/05-scala-merges R6
        val joined = joinFixture("scala3", "companion-object", "Scala", "scala")
        val cartClass = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "Cart" && it.subject.kind == DeclarationKind.CLASS }
        val cartObject = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "Cart" && it.subject.kind == DeclarationKind.OBJECT }
        assertNotEquals(cartClass.baseRange, cartObject.baseRange)
        assertNull(cartClass.left?.renamedFrom)
        assertNull(cartObject.left?.renamedFrom)
        assertNull(cartObject.right?.renamedFrom)
    }

    fun `test a Scala given and an extension block have their own rows`() {
        // merge/05-scala-merges R7
        val joined = joinFixture("scala3", "given-and-extension", "Scala", "scala")
        val items = outline(joined, "scala3", "given-and-extension")
        val given = items.firstOrNull { it.title.startsWith("given ") }
        val extension = items.firstOrNull { it.title.startsWith("extension ") }
        assertNotNull("a given row", given)
        assertNotNull("an extension block row", extension)
        assertTrue("an extension method row", extension!!.children.any { it.title.contains("isFull") })
    }

    fun `test a Scala kind change on one side leaves a member changed on the other in place`() {
        // merge/05-scala-merges R8
        val joined = joinFixture("scala3", "kind-change", "Scala", "scala")
        val receipt = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "Receipt" }
        assertNotNull("kind changed from class", receipt.left?.kindChangedFrom)
        assertEquals(MergeGroup.LEFT_ONLY, receipt.group)
        val header = receipt.children.first { it.subject is Subject.Declaration && it.subject.name == "header" }
        assertEquals(MergeGroup.RIGHT_ONLY, header.group)
    }

    fun `test Scala companion object change is covered`() {
        // merge/05-scala-merges R14
        val joined = joinFixture("scala3", "companion-object", "Scala", "scala")
        val objectRow = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "Cart" && it.subject.kind == DeclarationKind.OBJECT }
        val limit = objectRow.children.first { it.subject is Subject.Declaration && it.subject.name == "Limit" }
        assertEquals(MergeGroup.LEFT_ONLY, limit.group)
    }

    fun `test Scala extension method change is covered`() {
        // merge/05-scala-merges R14
        val joined = joinFixture("scala3", "given-and-extension", "Scala", "scala")
        val extension = outline(joined, "scala3", "given-and-extension").first { it.title.startsWith("extension ") }
        val isFull = extension.children.first { it.title.contains("isFull") }
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, isFull.group)
    }

    fun `test Scala kind change on one side is covered`() {
        // merge/05-scala-merges R14
        val joined = joinFixture("scala3", "kind-change", "Scala", "scala")
        val receipt = joined.nodes.first { it.subject is Subject.Declaration && it.subject.name == "Receipt" }
        assertNotNull(receipt.left?.kindChangedFrom)
    }

    private fun checkFixtures(directory: String, languageId: String, extension: String) {
        val cases = mergeCases(directory)
        assertTrue("no $directory merge fixtures", cases.isNotEmpty())
        val failures = mutableListOf<String>()
        for (case in cases) {
            val base = read(File(case, "base.$extension"))
            val left = read(File(case, "left.$extension"))
            val right = read(File(case, "right.$extension"))
            val joined = MergeComparison.compare(
                engine,
                MergeTexts(base, left, right, languageId, "${case.name}.$extension"),
                Cancellation.NONE,
                executor,
            )

            // merge/05-scala-merges R15: both comparisons keep the nothing-hidden rule.
            if (joined.left.status == Status.ANALYZED || joined.left.status == Status.PARTIAL) {
                NothingHidden.violation(base, left, joined.left)?.let { failures += "${case.name} left: $it" }
            }
            if (joined.right.status == Status.ANALYZED || joined.right.status == Status.PARTIAL) {
                NothingHidden.violation(base, right, joined.right)?.let { failures += "${case.name} right: $it" }
            }

            // merge/05-scala-merges R10: one row per line with group, labels and all three line spans.
            val actual = MergeRendering.joined(joined, base, left, right)
            val expectedFile = File(case, "expected-${engine.id}.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            // merge/05-scala-merges R16: the same property as the two-sided golden fixtures rewrites the expected file.
            if (UPDATE) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} $directory merge fixtures failed:\n\n" + failures.joinToString("\n"))
    }

    private fun joinFixture(directory: String, case: String, languageId: String, extension: String): JoinedResult {
        val root = File(TEST_DATA, "$directory/merge/$case")
        return MergeComparison.compare(
            engine,
            MergeTexts(
                read(File(root, "base.$extension")),
                read(File(root, "left.$extension")),
                read(File(root, "right.$extension")),
                languageId,
                "$case.$extension",
            ),
            Cancellation.NONE,
            executor,
        )
    }

    private fun outline(joined: JoinedResult, directory: String, case: String): List<MergeOutlineItem> {
        val root = File(TEST_DATA, "$directory/merge/$case")
        val base = read(File(root, "base.scala"))
        val left = read(File(root, "left.scala"))
        val right = read(File(root, "right.scala"))
        val model = MergeOutlineModel.build(
            joined, base, left, right,
            MergeSideName("left", "left"), MergeSideName("right", "right"),
            MergeOutlineLayout.CONTAINER_TREE,
        )
        fun flatten(items: List<MergeOutlineItem>): List<MergeOutlineItem> = items + items.flatMap { flatten(it.children) }
        return flatten(model.items)
    }

    private fun mergeCases(directory: String): List<File> =
        File(TEST_DATA, "$directory/merge").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    private fun dialect(vararg texts: String): String? = ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
        scala.dialect(project, texts.toList(), null)?.id
    })

    /** Fixture texts are LF-separated like document snapshots, whatever the checkout's line ends. */
    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val TEST_DATA = File("src/test/testData")
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
        val LANGUAGE_EXTENSIONS = listOf(
            "java" to "java",
            "kotlin" to "kt",
            "scala2" to "scala",
            "scala3" to "scala",
        )
        val REQUIRED_SCENARIOS = listOf(
            "changed-on-both-sides",
            "changed-on-one-side",
            "rename-versus-change",
            "remove-versus-change",
            "add-on-both-sides",
            "move-versus-change",
            "formatting-versus-change",
            "same-change",
            "imports-both-sides",
        )
    }
}
