package io.github.agadek.smartdiff

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginLoadingTest : BasePlatformTestCase() {

    fun `test plugin and its language dependencies are loaded`() {
        for (id in listOf("io.github.agadek.smartdiff", "com.intellij.java", "org.jetbrains.kotlin")) {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(id))
            assertNotNull("plugin $id is not loaded", plugin)
            assertTrue("plugin $id is disabled", PluginManagerCore.isLoaded(PluginId.getId(id)))
        }
    }
}
