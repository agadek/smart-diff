package io.github.agadek.smartdiff.references

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.ContractCase
import io.github.agadek.smartdiff.engine.api.contract.RelationshipChecks
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** The reference pass over the Java fixtures: grouping, purity, pairing, confidence, cancellation and the time budget. */
class ReferencePassTest : BasePlatformTestCase() {

    fun `test R1 R5 a pure usage is added as reference evidence without an endpoint`() {
        val (engineResult, outcome) = run(ONE_OLD, ONE_NEW)

        assertEquals(ReferencePass.Status.GROUPED, outcome.status)
        assertEquals(ReferencePass.VERSION, outcome.version)
        val before = engineResult.relationships.single()
        val after = outcome.result.relationships.single()
        assertEquals("the endpoints stay the renamed declarations", before.old, after.old)
        assertEquals(before.new, after.new)
        val reference = after.evidence.filterIsInstance<Evidence.Reference>().single()
        assertEquals(after.new.single().subject, reference.symbol)
        assertEquals(TextRange(69, 74), reference.oldRange)
        assertEquals(TextRange(67, 70), reference.newRange)
        assertEquals("references sort after position and before score", after.evidence.map { it::class }, listOf(
            Evidence.Name::class, Evidence.Position::class, Evidence.Reference::class, Evidence.Score::class,
        ))
        RelationshipChecks.rangesInsideText(case(ONE_OLD, ONE_NEW), outcome.result)
        RelationshipChecks.referencesNameNewEndpoint(case(ONE_OLD, ONE_NEW), outcome.result)
        RelationshipChecks.fixedOrder(case(ONE_OLD, ONE_NEW), outcome.result)
        RelationshipChecks.endpointsInResultTree(case(ONE_OLD, ONE_NEW), outcome.result)
        assertNoChangeNodeOrDetailsChanged(engineResult, outcome.result)
    }

    fun `test R12 R21 a PARTIAL result never adds evidence to an unanalyzed endpoint`() {
        // relationships/04-rename-grouping R12; relationships/02-relationship-model R21
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val endpoint = result.relationships.single().new.single()
        val unanalysed = result.copy(
            status = Status.PARTIAL,
            changes = result.changes.map { markUnanalysed(it, endpoint.range) },
            unanalyzed = listOf(io.github.agadek.smartdiff.engine.api.RangePair(endpoint.range, endpoint.range)),
        )

        val outcome = ReferencePass.runForBenchmark(project, request, unanalysed, Cancellation.NONE)

        assertEquals(ReferencePass.Status.NOT_GROUPED, outcome.status)
        assertSame(unanalysed, outcome.result)
        assertTrue(outcome.result.relationships.flatMap { it.evidence }.none { it is Evidence.Reference })
    }

    fun `test R12 a usage inside an unanalyzed method never attaches to its analyzed class`() {
        // relationships/04-rename-grouping R12; relationships/02-relationship-model R21
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val unanalysed = result.copy(
            status = Status.PARTIAL,
            changes = result.changes.map { markNamed(it, "twice", io.github.agadek.smartdiff.engine.api.Content.UNANALYZED) },
            unanalyzed = listOf(io.github.agadek.smartdiff.engine.api.RangePair(TextRange(43, 48), TextRange(41, 46))),
        )

        val outcome = ReferencePass.runForBenchmark(project, request, unanalysed, Cancellation.NONE)

        assertEquals(ReferencePass.Status.NOT_GROUPED, outcome.status)
        assertTrue(outcome.result.relationships.flatMap { it.evidence }.none { it is Evidence.Reference })
    }

    private fun markNamed(node: ChangeNode, name: String, content: io.github.agadek.smartdiff.engine.api.Content): ChangeNode {
        val subject = node.subject as? Subject.Declaration
        val marked = if (subject?.name == name) node.copy(content = content) else node
        return marked.copy(children = marked.children.map { markNamed(it, name, content) })
    }

    private fun markUnanalysed(node: ChangeNode, range: TextRange): ChangeNode {
        val marked = if (node.newRange == range || node.oldRange == range) {
            node.copy(content = io.github.agadek.smartdiff.engine.api.Content.UNANALYZED)
        } else {
            node
        }
        return marked.copy(children = marked.children.map { markUnanalysed(it, range) })
    }

    fun `test R12 the pass adds no endpoint and writes no change detail`() {
        val (engineResult, outcome) = run(ONE_OLD, ONE_NEW)
        assertNoChangeNodeOrDetailsChanged(engineResult, outcome.result)
    }

    fun `test R3 no file or a file outside the project keeps the engine result`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)

        val noFile = ReferencePass.run(project, null, request, result, Cancellation.NONE)
        assertEquals(ReferencePass.Status.NOT_GROUPED, noFile.status)
        assertSame(result, noFile.result)

        val file = Files.createTempFile("outside-project", ".java").toFile()
        try {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            val outside = ReferencePass.run(project, virtualFile, request, result, Cancellation.NONE)
            assertEquals(ReferencePass.Status.NOT_GROUPED, outside.status)
            assertSame(result, outside.result)
        } finally {
            file.delete()
        }
    }

    fun `test R4 only a high rename groups`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val medium = result.copy(relationships = result.relationships.map { it.copy(confidence = Confidence.MEDIUM) })

        val outcome = ReferencePass.runForBenchmark(project, request, medium, Cancellation.NONE)

        assertEquals(ReferencePass.Status.NOT_GROUPED, outcome.status)
        assertSame(medium, outcome.result)
    }

    fun `test R4 a raised high threshold stops grouping on the next pass`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java", confidence = ConfidencePolicy(high = 1.0, medium = 0.7))
        val result = compare(request)

        assertEquals(Confidence.MEDIUM, result.relationships.single().confidence)
        assertEquals(ReferencePass.Status.NOT_GROUPED, ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE).status)
    }

    fun `test R6 another overload and an unresolved call stay ordinary`() {
        val (_, outcome) = run(THREE_OLD, THREE_NEW)

        val references = outcome.result.relationships.single().evidence.filterIsInstance<Evidence.Reference>()
        assertEquals(1, references.size)
        assertEquals(TextRange(119, 124), references.single().oldRange)
        assertEquals(TextRange(117, 120), references.single().newRange)
    }

    fun `test R7 an impure usage stays ordinary and its declaration keeps changed`() {
        val (_, outcome) = run(FOUR_OLD, FOUR_NEW)

        val references = outcome.result.relationships.single().evidence.filterIsInstance<Evidence.Reference>()
        assertEquals(1, references.size)
        assertEquals(TextRange(92, 97), references.single().oldRange)
        assertEquals(TextRange(90, 93), references.single().newRange)
        val use = nodes(outcome.result).single { (it.subject as? Subject.Declaration)?.name == "use" }
        assertTrue("the declaration keeps its engine content", use.content == io.github.agadek.smartdiff.engine.api.Content.CHANGED)
    }

    fun `test R8 R12 ChangeNode details stay as the engine set them`() {
        val (engineResult, outcome) = run(ONE_OLD, ONE_NEW)
        assertNoChangeNodeOrDetailsChanged(engineResult, outcome.result)
    }

    fun `test R13 a target outside the light copy stays ordinary`() {
        val old = "class Order {\n    int total() { return 1; }\n    int use(String s) { return s.length() + total(); }\n}\n"
        val new = "class Order {\n    int sum() { return 1; }\n    int use(String s) { return s.length() + sum(); }\n}\n"
        val (_, outcome) = run(old, new)

        val references = outcome.result.relationships.single().evidence.filterIsInstance<Evidence.Reference>()
        assertEquals("s.length() is not a usage of the renamed method", 1, references.size)
    }

    fun `test R16 an external cancellation after the first resolve propagates and stops the next`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val spy = SpyResolver()
        val external = Cancellation { if (spy.cancelAfterResolve) throw ProcessCanceledException() }

        assertThrows(ProcessCanceledException::class.java) {
            ReferencePass.runWith(project, request, result, external, listOf(spy), ReferencePass.BUDGET_MILLIS)
        }
        assertEquals("the resolver saw one resolve and no second", 1, spy.resolves)
    }

    fun `test R16 a platform indicator cancellation during resolve retries without a partial group`() {
        // relationships/04-rename-grouping R16
        val old = "class Order {\n    int total() { return 1; }\n    int use() { return total() + total(); }\n}\n"
        val new = "class Order {\n    int sum() { return 1; }\n    int use() { return sum() + sum(); }\n}\n"
        val request = ComparisonRequest(old, new, "JAVA", "Order.java")
        val result = compare(request)
        val resolver = PlatformCancelResolver(old, new)

        val outcome = ApplicationManager.getApplication().executeOnPooledThread(
            Callable { ReferencePass.runWith(project, request, result, Cancellation.NONE, listOf(resolver), ReferencePass.BUDGET_MILLIS) },
        ).get(30, TimeUnit.SECONDS)

        assertEquals("the canceled read is retried, not returned as a partial group", ReferencePass.Status.GROUPED, outcome.status)
        assertEquals(2, outcome.result.relationships.single().evidence.filterIsInstance<Evidence.Reference>().size)
    }

    fun `test R17 a pass over its budget returns the engine result with no grouped usages`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val slow = SlowResolver()

        val outcome = ReferencePass.runWith(project, request, result, Cancellation.NONE, listOf(slow), 10)

        assertEquals(ReferencePass.Status.TIMED_OUT, outcome.status)
        assertSame(result, outcome.result)
        assertTrue(outcome.notGrouped)
        assertTrue("no reference evidence is kept", outcome.result.relationships.none { relationship ->
            relationship.evidence.any { it is Evidence.Reference }
        })
    }

    fun `test the benchmark and outline entry points give equal evidence`() {
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)
        val file = myFixture.addFileToProject("usage/Order.java", ONE_NEW).virtualFile

        val outline = ReferencePass.run(project, file, request, result, Cancellation.NONE)
        val benchmark = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

        assertEquals(outline.result.relationships, benchmark.result.relationships)
    }

    fun `test R3 R19 the benchmark entry point needs no diff file`() {
        // relationships/04-rename-grouping R3, R19
        val request = ComparisonRequest(ONE_OLD, ONE_NEW, "JAVA", "Order.java")
        val result = compare(request)

        val outcome = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

        assertEquals(ReferencePass.Status.GROUPED, outcome.status)
        assertFalse(outcome.dumb)
    }

    private fun assertNoChangeNodeOrDetailsChanged(before: ComparisonResult, after: ComparisonResult) {
        assertEquals(before.fileRegion, after.fileRegion)
        assertEquals(before.changes, after.changes)
        assertEquals(nodes(before).map(ChangeNode::details), nodes(after).map(ChangeNode::details))
    }

    private fun run(old: String, new: String): Pair<ComparisonResult, ReferencePass.Outcome> {
        val request = ComparisonRequest(old, new, "JAVA", "Order.java")
        val result = compare(request)
        return result to ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)
    }

    private fun compare(request: ComparisonRequest): ComparisonResult =
        compareSafely(DeclarationEngine(project), request, Cancellation.NONE)

    private fun case(old: String, new: String) = ContractCase("reference", "JAVA", old, new)

    private fun nodes(result: ComparisonResult): List<ChangeNode> {
        val nodes = mutableListOf<ChangeNode>()
        fun visit(node: ChangeNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
        return nodes
    }

    /** Resolves one usage, then asks the external cancellation to cancel; the pass must not resolve a second. */
    private class SpyResolver : ReferenceResolver {
        var resolves = 0

        @Volatile
        var cancelAfterResolve = false

        override fun supports(languageId: String) = languageId == "JAVA"
        override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement = file
        override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> {
            resolves++
            cancellation.checkCanceled()
            cancelAfterResolve = true
            cancellation.checkCanceled()
            return listOf(TextRange(0, 1))
        }
        override fun context(file: PsiFile, usage: TextRange): TextRange = TextRange(0, 10)
        override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT
    }

    /** Cancels the platform indicator during the first resolve and returns only one usage, as a write action would. */
    private class PlatformCancelResolver(private val old: String, private val new: String) : ReferenceResolver {
        private var canceled = false
        private val oldUsages = occurrences(old, "total()", old.indexOf("use()"))
        private val newUsages = occurrences(new, "sum()", new.indexOf("use()"))

        override fun supports(languageId: String) = languageId == "JAVA"
        override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement = file

        override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> {
            cancellation.checkCanceled()
            if (!canceled) {
                canceled = true
                val indicator = ProgressManager.getInstance().progressIndicator
                assertNotNull("the read action has a platform indicator", indicator)
                indicator!!.cancel()
                return listOf(oldUsages.first())
            }
            return if (file.text == old) oldUsages else newUsages
        }

        override fun context(file: PsiFile, usage: TextRange): TextRange = usage
        override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT

        private fun occurrences(text: String, token: String, after: Int): List<TextRange> {
            val first = text.indexOf(token, after)
            val second = text.indexOf(token, first + token.length)
            return listOf(TextRange(first, first + token.length), TextRange(second, second + token.length))
        }
    }

    private class SlowResolver : ReferenceResolver {
        override fun supports(languageId: String) = languageId == "JAVA"
        override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement = file
        override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> {
            Thread.sleep(30)
            return listOf(TextRange(0, 1))
        }
        override fun context(file: PsiFile, usage: TextRange): TextRange = TextRange(0, 10)
        override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT
    }

    private companion object {
        const val ONE_OLD = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        const val ONE_NEW = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
        const val THREE_OLD = "class Order {\n    int total() { return 1; }\n    int total(int value) { return value; }\n    int use() {\n        int a = total();\n        int b = total(2);\n        int c = unknown();\n        return a + b + c;\n    }\n}\n"
        const val THREE_NEW = "class Order {\n    int sum() { return 1; }\n    int total(int value) { return value; }\n    int use() {\n        int a = sum();\n        int b = total(2);\n        int c = unknown();\n        return a + b + c;\n    }\n}\n"
        const val FOUR_OLD = "class Order {\n    int total() { return 1; }\n    int use(Order other) {\n        int a = this.total();\n        int b = other.total() + 1;\n        return a + b;\n    }\n}\n"
        const val FOUR_NEW = "class Order {\n    int sum() { return 1; }\n    int use(Order other) {\n        int a = this.sum();\n        int b = this.sum() + 1;\n        return a + b;\n    }\n}\n"
    }
}
