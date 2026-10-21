package io.github.agadek.smartdiff

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginLoadingTest : BasePlatformTestCase() {

    fun `test plugin and its language dependencies are loaded`() {
        val loaded = PluginManagerCore.loadedPlugins.map { it.pluginId }.toSet()
        for (id in listOf("io.github.agadek.smartdiff", "com.intellij.java", "org.jetbrains.kotlin")) {
            assertTrue("plugin $id is not loaded", PluginId.getId(id) in loaded)
        }
    }
}
