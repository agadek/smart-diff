package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContract
import io.github.agadek.smartdiff.engine.lines.LineEngine
import io.github.agadek.smartdiff.engine.lines.LineEngineFactory

/** The registry over the real extension point (technical specification §3.2, §4, §12 abstraction test). */
class EngineRegistryTest : BasePlatformTestCase() {

    private val registry get() = EngineRegistry.getInstance(project)

    override fun tearDown() {
        try {
            listOf("JAVA", "kotlin", "TEXT").forEach { EngineDefaults.getInstance().set(it, null) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun register(vararg factories: ComparisonEngineFactory) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, factories.toList(), testRootDisposable)
    }

    fun `test plugin declares the comparison engine extension point`() {
        val name = ComparisonEngineFactory.EP_NAME.name
        assertNotNull("extension point $name is not declared", ApplicationManager.getApplication().extensionArea.getExtensionPointIfRegistered<Any>(name))
    }

    fun `test engines come from the extension point in order`() {
        register(LineEngineFactory(), FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA") })

        assertEquals(listOf(LineEngine.ID, EngineId("smartdiff.declarations")), registry.engines().map { it.id })
        assertEquals(EngineId("smartdiff.declarations"), registry.findEngine(EngineId("smartdiff.declarations"))?.id)
        assertNull(registry.findEngine(EngineId("smartdiff.missing")))
    }

    fun `test the factory gets the project`() {
        val factory = FakeEngineFactory { FakeEngine("a", "JAVA") }
        register(factory)

        registry.engines()

        assertEquals(listOf(project), factory.projects)
    }

    fun `test the default project gives factories no project`() {
        val factory = FakeEngineFactory { FakeEngine("a", "JAVA") }
        register(factory)

        EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()

        assertEquals(listOf(null), factory.projects)
    }

    fun `test engines are created once and recreated when the extensions change`() {
        val factory = FakeEngineFactory { FakeEngine("a", "JAVA") }
        register(factory)
        val first = registry.engines()
        assertSame(first, registry.engines())
        assertEquals(1, factory.projects.size)

        register(factory, FakeEngineFactory { FakeEngine("b", "JAVA") })

        assertEquals(listOf(EngineId("a"), EngineId("b")), registry.engines().map { it.id })
    }

    fun `test a duplicate engine id is skipped`() {
        val duplicate: ComparisonEngine = FakeEngine("a", "TEXT")
        register(FakeEngineFactory { FakeEngine("a", "JAVA") }, FakeEngineFactory { duplicate })

        val engines = registry.engines()

        assertEquals(1, engines.size)
        assertNotSame(duplicate, engines.single())
    }

    fun `test a test-only engine is selectable next to another engine`() {
        register(FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA", "kotlin") }, LineEngineFactory())

        val java = registry.select("JAVA")
        assertEquals(EngineId("smartdiff.declarations"), java.selected?.id)
        assertTrue(java.offersChoice)

        registry.choose("JAVA", LineEngine.ID)

        assertEquals(LineEngine.ID, registry.select("JAVA").selected?.id)
        assertEquals("the choice applies to its language only", EngineId("smartdiff.declarations"), registry.select("kotlin").selected?.id)
    }

    fun `test a stored default survives while its engine is not registered`() {
        register(FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA") }, LineEngineFactory())
        registry.choose("JAVA", LineEngine.ID)

        register(FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA") })
        assertEquals(EngineId("smartdiff.declarations"), registry.select("JAVA").selected?.id)
        assertEquals(LineEngine.ID, EngineDefaults.getInstance().get("JAVA"))

        register(FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA") }, LineEngineFactory())
        assertEquals(LineEngine.ID, registry.select("JAVA").selected?.id)
    }

    fun `test no supporting engine gives no selection`() {
        register(FakeEngineFactory { FakeEngine("smartdiff.declarations", "JAVA") })

        assertNull(registry.select("Python").selected)
    }

    fun `test the registered line engine passes the contract suite`() {
        register(LineEngineFactory())

        ComparisonEngineContract { EngineRegistry.getInstance(project).findEngine(LineEngine.ID)!! }.checkAll()
    }
}
