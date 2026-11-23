package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContract
import io.github.agadek.smartdiff.engine.api.contract.ContractCorpus
import io.github.agadek.smartdiff.engine.api.contract.request
import io.github.agadek.smartdiff.engine.declarations.analysis.DeclarationAnalysis
import io.github.agadek.smartdiff.engine.declarations.analysis.tree
import io.github.agadek.smartdiff.engine.declarations.psi.java.JavaDeclarationProvider
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** The declaration engine over the engine contract suite (technical specification §12) and its own rules. */
class DeclarationEngineTest : BasePlatformTestCase() {

    private val contract by lazy { ComparisonEngineContract { DeclarationEngineFactory().create(null) } }

    fun `test engine supports at least one corpus language`() = contract.`engine supports at least one corpus language`()

    fun `test identical texts have no reported changes`() = contract.`identical texts have no reported changes`()

    fun `test empty and one-side-empty texts are analyzed`() = contract.`empty and one-side-empty texts are analyzed`()

    fun `test results are well-formed and hide nothing`() = contract.`results are well-formed and hide nothing`()

    fun `test malformed input gives a result, not an exception`() = contract.`malformed input gives a result, not an exception`()

    fun `test results are deterministic`() = contract.`results are deterministic`()

    fun `test relationships name declarations in the result`() = contract.`relationships name declarations in the result`()

    fun `test relationship ranges lie inside their side`() = contract.`relationship ranges lie inside their side`()

    fun `test extraction and inlining fields agree and no relationship repeats`() = contract.`extraction and inlining fields agree and no relationship repeats`()

    fun `test relationships are in a fixed order`() = contract.`relationships are in a fixed order`()

    fun `test relationships hide nothing`() = contract.`relationships hide nothing`()

    fun `test cancellation propagates promptly`() = contract.`cancellation propagates promptly`()

    // languages/04-python R18
    fun `test Python kind change class to function is detected`() {
        val engine = DeclarationEngine(null)
        val old = "class Foo:\n    pass\n"
        val new = "def Foo():\n    pass\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val rel = result.relationships.single { RelationshipKind.KIND_CHANGE in it.kinds }
        val oldSub = rel.old.single().subject as Subject.Declaration
        val newSub = rel.new.single().subject as Subject.Declaration
        assertEquals("Foo", oldSub.name)
        assertEquals(DeclarationKind.CLASS, oldSub.kind)
        assertEquals("Foo", newSub.name)
        assertEquals(DeclarationKind.FUNCTION, newSub.kind)
    }

    // languages/04-python R18
    fun `test Python class unchanged and new same-named function added`() {
        val engine = DeclarationEngine(null)
        val old = "class Foo:\n    def bar(self):\n        return 1\n"
        val new = "class Foo:\n    def bar(self):\n        return 1\n\ndef Foo():\n    pass\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val fooClassNodes = flatten(result).filter {
            it.subject is Subject.Declaration && (it.subject as Subject.Declaration).name == "Foo" && (it.subject as Subject.Declaration).kind == DeclarationKind.CLASS
        }
        assertTrue(fooClassNodes.isNotEmpty())
        fooClassNodes.forEach { node -> assertEquals(Presence.MATCHED, node.presence) }
        assertTrue(result.relationships.none { RelationshipKind.KIND_CHANGE in it.kinds && it.old.any { ep -> (ep.subject as? Subject.Declaration)?.name == "Foo" } })
        val fooFuncAdded = flatten(result).filter {
            it.subject is Subject.Declaration && (it.subject as Subject.Declaration).name == "Foo" && (it.subject as Subject.Declaration).kind == DeclarationKind.FUNCTION && it.presence == Presence.ADDED
        }
        assertTrue("expected added function Foo", fooFuncAdded.isNotEmpty())
    }

    // languages/04-python R18
    fun `test Python kind change function to variable is detected`() {
        val engine = DeclarationEngine(null)
        val old = "def bar():\n    return 1\n"
        val new = "bar = 1\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val rel = result.relationships.single { RelationshipKind.KIND_CHANGE in it.kinds }
        val oldSub = rel.old.single().subject as Subject.Declaration
        val newSub = rel.new.single().subject as Subject.Declaration
        assertEquals("bar", oldSub.name)
        assertEquals(DeclarationKind.FUNCTION, oldSub.kind)
        assertEquals("bar", newSub.name)
        assertEquals(DeclarationKind.VARIABLE, newSub.kind)
    }

    // languages/04-python R18
    fun `test Python rename candidate is detected`() {
        val engine = DeclarationEngine(null)
        val old = "def foo():\n    return 1\n"
        val new = "def bar():\n    return 1\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val rel = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        val oldSub = rel.old.single().subject as Subject.Declaration
        val newSub = rel.new.single().subject as Subject.Declaration
        assertEquals("foo", oldSub.name)
        assertEquals(DeclarationKind.FUNCTION, oldSub.kind)
        assertEquals("bar", newSub.name)
        assertEquals(DeclarationKind.FUNCTION, newSub.kind)
    }

    // languages/04-python R18
    fun `test Python extraction is detected`() {
        val engine = DeclarationEngine(null)
        val old = "def summary():\n    print('a')\n    print('b')\n    print('c')\n    print('d')\n    print('e')\n"
        val new = "def summary():\n    print('a')\n    extracted()\n    print('e')\n\ndef extracted():\n    print('b')\n    print('c')\n    print('d')\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val rel = result.relationships.single { RelationshipKind.EXTRACT in it.kinds }
        assertTrue(rel.new.any { (it.subject as? Subject.Declaration)?.name == "extracted" })
        assertTrue(rel.old.any { (it.subject as? Subject.Declaration)?.name == "summary" })
    }

    // languages/04-python R18
    fun `test Python inlining is detected`() {
        val engine = DeclarationEngine(null)
        val old = "def summary():\n    print('a')\n    extracted()\n    print('e')\n\ndef extracted():\n    print('b')\n    print('c')\n    print('d')\n"
        val new = "def summary():\n    print('a')\n    print('b')\n    print('c')\n    print('d')\n    print('e')\n"
        val result = engine.compare(ComparisonRequest(old, new, "Python", "t.py"), Cancellation.NONE)
        val rel = result.relationships.single { RelationshipKind.INLINE in it.kinds }
        assertTrue(rel.old.any { (it.subject as? Subject.Declaration)?.name == "extracted" })
        assertTrue(rel.new.any { (it.subject as? Subject.Declaration)?.name == "summary" })
    }



    // languages/04-python R22
    fun `test Python decorated class unchanged after inserted indented block above it`() {
        val old = """import attrs

C = attrs.make_class("C", [])
C(a=1)

@attrs.define
class D:
    x: list[int] = attrs.field()
"""
        val new = """import attrs
import copy
import sys

C = attrs.make_class("C", [])
C(a=1)

if sys.version_info >= (3, 13):
    cc = copy.replace(cc, x=1)

@attrs.define
class D:
    x: list[int] = attrs.field()
"""
        val result = DeclarationEngine(null).compare(
            ComparisonRequest(old, new, "Python", "demo.py"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val dNodes = flatten(result).filter {
            it.subject is Subject.Declaration &&
                (it.subject as Subject.Declaration).name == "D" &&
                (it.subject as Subject.Declaration).kind == DeclarationKind.CLASS
        }
        assertTrue("class D should be present", dNodes.isNotEmpty())
        dNodes.forEach { node ->
            assertEquals("class D matched despite inserted statements above decorator", Presence.MATCHED, node.presence)
            assertEquals("class D unchanged when its text is identical", Content.UNCHANGED, node.content)
        }
    }

    // languages/04-python R22
    fun `test Python undecorated function unchanged after inserted indented block above it`() {
        val old = """x = 1

def target():
    return 42
"""
        val new = """x = 1

if True:
    y = 2

def target():
    return 42
"""
        val result = DeclarationEngine(null).compare(
            ComparisonRequest(old, new, "Python", "demo.py"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val target = flatten(result).single {
            it.subject is Subject.Declaration &&
                (it.subject as Subject.Declaration).name == "target" &&
                (it.subject as Subject.Declaration).kind == DeclarationKind.FUNCTION
        }
        assertEquals(Presence.MATCHED, target.presence)
        assertEquals(Content.UNCHANGED, target.content)
    }

    // languages/04-python R22
    fun `test Python nested method unchanged and file region unaffected by structural tokens inside class`() {
        val old = """class C:
    x = 1

    def b(self):
        return 2
"""
        val new = """class C:
    def a(self):
        return 1

    def b(self):
        return 2
"""
        val result = DeclarationEngine(null).compare(
            ComparisonRequest(old, new, "Python", "demo.py"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val b = flatten(result).single {
            it.subject is Subject.Declaration &&
                (it.subject as Subject.Declaration).name == "b" &&
                (it.subject as Subject.Declaration).kind == DeclarationKind.FUNCTION
        }
        assertEquals(Presence.MATCHED, b.presence)
        assertEquals(Content.UNCHANGED, b.content)
        assertTrue(
            "file region must not be changed by structural tokens inside the class",
            result.fileRegion == null || result.fileRegion.content == Content.UNCHANGED,
        )
    }

    // languages/04-python R22
    fun `test Python adding a method leaves the class and file region unchanged`() {
        val old = """class C:
    def a(self):
        return 1
"""
        val new = """class C:
    def a(self):
        return 1

    def b(self):
        return 2
"""
        val result = DeclarationEngine(null).compare(
            ComparisonRequest(old, new, "Python", "demo.py"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val c = flatten(result).first {
            it.subject is Subject.Declaration &&
                (it.subject as Subject.Declaration).name == "C" &&
                (it.subject as Subject.Declaration).kind == DeclarationKind.CLASS
        }
        assertEquals(Presence.MATCHED, c.presence)
        assertEquals(Content.UNCHANGED, c.content)
        val b = flatten(result).single {
            it.subject is Subject.Declaration &&
                (it.subject as Subject.Declaration).name == "b" &&
                (it.subject as Subject.Declaration).kind == DeclarationKind.FUNCTION
        }
        assertEquals(Presence.ADDED, b.presence)
        assertTrue(
            "file region must not be changed when a method is added to a class",
            result.fileRegion == null || result.fileRegion.content == Content.UNCHANGED,
        )
    }

    fun `test R7 R8 R18 the computed fields are read from the relationships over the golden fixtures`() {
        // relationships/02-relationship-model R7, R8, R18. GoldenFixturesTest proves these fields are the ones the
        // engine set before the move, since it compares the rendered outline with the checked-in expected files.
        val engine = DeclarationEngine(null)
        var checked = 0
        for ((directory, languageId, extension) in listOf(
            Triple("java", "JAVA", "java"),
            Triple("kotlin", "kotlin", "kt"),
            Triple("scala2", "Scala", "scala"),
            Triple("scala3", "Scala", "scala"),
            Triple("python", "Python", "py"),
        )) {
            val cases = File("src/test/testData", directory)
                .listFiles { file -> file.isDirectory && file.name != "merge" && file.name != "relationships" }
                .orEmpty()
                .sortedBy { it.name }
            for (case in cases) {
                val ext = case.listFiles()?.firstOrNull { it.name.startsWith("old.") }?.name?.removePrefix("old.") ?: extension
                val old = File(case, "old.$ext").readText().replace("\r\n", "\n")
                val new = File(case, "new.$ext").readText().replace("\r\n", "\n")
                val result = engine.compare(ComparisonRequest(old, new, languageId, "${case.name}.$ext"), Cancellation.NONE)
                val nodes = flatten(result)
                val matchLinks = result.relationships.filter { RelationshipKind.EXTRACT !in it.kinds && it.confidence != Confidence.LOW }
                for (node in nodes) {
                    if (node.oldRange == null || node.newRange == null) {
                        assertNull("$case ${node.subject} has no match relationship", node.renamedFrom)
                        assertFalse("$case ${node.subject} is not moved", node.moved)
                        assertNull("$case ${node.subject} has no kind change", node.kindChangedFrom)
                        continue
                    }
                    val link = matchLinks.singleOrNull { it.old.single().range == node.oldRange && it.new.single().range == node.newRange }
                    assertEquals("$case ${node.subject} renamedFrom", node.renamedFrom, link?.takeIf { RelationshipKind.RENAME in it.kinds }?.old?.single()?.subject)
                    assertEquals("$case ${node.subject} moved", node.moved, link != null && RelationshipKind.MOVE in link.kinds)
                    assertEquals(
                        "$case ${node.subject} kindChangedFrom",
                        node.kindChangedFrom,
                        link?.takeIf { RelationshipKind.KIND_CHANGE in it.kinds }?.old?.single()?.subject?.let { (it as? Subject.Declaration)?.kind },
                    )
                }
                for (node in nodes) {
                    for (origin in node.extractedFrom) {
                        val links = result.relationships.filter { link ->
                            RelationshipKind.EXTRACT in link.kinds && link.old.single().range == origin.declarationRange && link.new.single().range == node.newRange
                        }
                        assertEquals("$case ${node.subject} has one extraction link", 1, links.size)
                        assertEquals(origin.range, links.single().evidence.filterIsInstance<Evidence.Content>().single().oldRange)
                        val source = nodes.single { it.oldRange == origin.declarationRange }
                        assertTrue("$case the source lists its target", source.extractedTo.contains(node.subject))
                    }
                    for (target in node.extractedTo) {
                        assertTrue(
                            "$case ${node.subject} extractedTo $target has an EXTRACT relationship",
                            result.relationships.any { RelationshipKind.EXTRACT in it.kinds && it.old.single().range == node.oldRange && it.new.single().subject == target },
                        )
                    }
                }
                checked++
            }
        }
        assertTrue("the golden fixtures were read", checked > 20)
    }

    fun `test R21 a partial result links only analyzed declarations and a fallback has no relationships`() {
        // relationships/02-relationship-model R21
        val body = "{ int x = compute(a, b); return x * 2; }"
        val old = tree { cls("A") { method("total", "int", body); method("other", "", "{ return 1; }") } }
        val new = tree { cls("A") { method("sum", "int", "$body "); method("other", "", "{ return", parseError = true) } }
        val partial = DeclarationAnalysis(DeclarationEngine.ID, DeclarationEngine.VERSION).compare(old, new, 5_000, Cancellation.NONE)

        assertEquals(Status.PARTIAL, partial.status)
        assertTrue("the rename is still linked", partial.relationships.isNotEmpty())
        assertEquals("the parse error sets UNANALYZED", Content.UNANALYZED, flatten(partial).single { (it.subject as Subject.Declaration).name == "other" }.content)
        partial.relationships.forEach { link ->
            assertTrue("${link.old.single().range} is a declaration of the old side", old.all.any { it.range == link.old.single().range })
            assertTrue("${link.new.single().range} is a declaration of the new side", new.all.any { it.range == link.new.single().range })
        }

        val engine = DeclarationEngine(null)
        assertTrue("UNSUPPORTED", engine.compare(ComparisonRequest("a", "b", "TEXT"), Cancellation.NONE).relationships.isEmpty())
        assertTrue("FAILED", DeclarationEngine(project).compare(brokenRequest(), Cancellation.NONE).relationships.isEmpty())
        assertTrue(
            "TOO_LARGE",
            DeclarationEngine(project).compare(
                ContractCorpus.large(ContractCorpus.JAVA, size = 20).request().copy(limits = Limits.DEFAULT.copy(maxDeclarations = 10)),
                Cancellation.NONE,
            ).relationships.isEmpty(),
        )
    }

    fun `test R24 the Scala plugin version is read from the Scala descriptor only`() {
        // relationships/02-relationship-model R24
        val descriptor = """
            <?xml version="1.0" encoding="iso-8859-1"?>
            <idea-plugin>
                <id>org.intellij.scala</id>
                <name>Scala</name>
                <version>2025.1.32</version>
            </idea-plugin>
        """.trimIndent()

        assertEquals("2025.1.32", DeclarationEngine.parseScalaPluginVersion(descriptor))
        assertEquals(
            "2025.1.32",
            DeclarationEngine.parseScalaPluginVersion(descriptor.replace("<version>2025.1.32</version>", "<version>\n 2025.1.32 \n</version>")),
        )
        assertNull(
            "another plugin's descriptor names no Scala version",
            DeclarationEngine.parseScalaPluginVersion(descriptor.replace("org.intellij.scala", "org.jetbrains.kotlin")),
        )
        assertNull(
            "a descriptor without a version names no Scala version",
            DeclarationEngine.parseScalaPluginVersion(descriptor.replace("<version>2025.1.32</version>", "")),
        )
    }

    fun `test R24 the engine version names its own version, the IDE build and the Scala plugin`() {
        // relationships/02-relationship-model R24; relationships/03-relationship-matching R18; languages/08-groovy R11-R20;
        // languages/14-markdown §7
        assertEquals("25", DeclarationEngine.VERSION)
        assertEquals("24 (251.26927.53)", DeclarationEngine.formatVersion("251.26927.53", null))
        assertEquals("24 (251.26927.53; Scala 2025.1.32)", DeclarationEngine.formatVersion("251.26927.53", "2025.1.32"))

        // This IDE loads the Scala plugin, so its parser is named: it can change a result without a platform update.
        // The plugin manager is the independent source of truth; reading the descriptor through the shared test
        // classloader must still find the Scala one, and never fall back to "unknown".
        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId("org.intellij.scala"))?.version
        assertNotNull("the test sandbox loads the Scala plugin", pluginVersion)
        val version = DeclarationEngine(null).version

        assertEquals(
            DeclarationEngine.formatVersion(ApplicationInfo.getInstance().build.asStringWithoutProductCode(), pluginVersion),
            version,
        )
        assertFalse("the Scala plugin version is unknown: $version", version.contains("Scala unknown"))
    }

    fun `test R24 the version omits the Scala plugin when no provider handles it`() {
        // relationships/02-relationship-model R24; languages/14-markdown §7
        ExtensionTestUtil.maskExtensions(DeclarationProvider.EP_NAME, listOf(JavaDeclarationProvider()), testRootDisposable)
        val version = DeclarationEngine(null).version

        assertTrue(version, version.startsWith("${DeclarationEngine.VERSION} ("))
        assertFalse("no Scala plugin is named without its provider: $version", version.contains("Scala"))
    }

    fun `test the engine is registered first and supports Java and Kotlin`() {
        val engines = EngineRegistry.getInstance(project).engines()
        assertEquals(DeclarationEngine.ID, engines.first().id)
        val engine = engines.first()
        assertTrue(engine.supports("JAVA"))
        assertTrue(engine.supports("kotlin"))
        // A language without a structure view stays unsupported (languages/06 R12).
        assertFalse(engine.supports("TEXT"))
    }

    fun `test an unsupported language gives UNSUPPORTED`() {
        val result = DeclarationEngine(null).compare(ComparisonRequest("a", "b", "TEXT"), Cancellation.NONE)

        assertEquals(Status.UNSUPPORTED, result.status)
        assertNotNull(result.fallbackReason)
    }

    fun `test too many declarations give TOO_LARGE`() {
        val case = ContractCorpus.large(ContractCorpus.JAVA, size = 20)
        val request = case.request().copy(limits = Limits.DEFAULT.copy(maxDeclarations = 10))

        val result = DeclarationEngine(project).compare(request, Cancellation.NONE)

        assertEquals(Status.TOO_LARGE, result.status)
    }

    fun `test a background thread gives the same result as the test thread`() {
        val engine = DeclarationEngine(null)
        for (case in ContractCorpus.cases.filter { engine.supports(it.languageId) }) {
            val here = engine.compare(case.request(), Cancellation.NONE)
            assertEquals(case.toString(), here, onPooledThread { engine.compare(case.request(), Cancellation.NONE) })
        }
    }

    fun `test cancellation on a background thread reaches the caller unchanged`() {
        val engine = DeclarationEngine(null)
        val canceled = CancellationException("canceled by the test")
        // Caught on the pooled thread, so the platform does not log it as an uncaught exception.
        val outcome = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching { engine.compare(ContractCorpus.large(ContractCorpus.KOTLIN).request(), Cancellation { throw canceled }) }
        }).get(30, TimeUnit.SECONDS)
        assertSame(canceled, outcome.exceptionOrNull())
    }

    private fun brokenRequest(): ComparisonRequest =
        ComparisonRequest("class A {\n  int x() { return 1; }\n}\n", "}}} class { ( \"unterminated\n", ContractCorpus.JAVA)

    private fun flatten(result: ComparisonResult): List<ChangeNode> {
        val nodes = mutableListOf<ChangeNode>()
        fun walk(node: ChangeNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        result.fileRegion?.let(::walk)
        result.changes.forEach(::walk)
        return nodes
    }

    /** Runs [compare] on a pooled thread, where the engine takes read actions that give way to write actions. */
    private fun onPooledThread(compare: () -> ComparisonResult): ComparisonResult =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { compare() }).get(30, TimeUnit.SECONDS)
}
