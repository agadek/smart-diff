package io.github.agadek.smartdiff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.ui.changes.GitHubTreeAdapter
import io.github.agadek.smartdiff.ui.changes.GitLabTreeAdapter
import io.github.agadek.smartdiff.ui.changes.GitLogTreeAdapter
import io.github.agadek.smartdiff.ui.changes.ReviewTreeAdapter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Each review tree adapter loads only with the plugin that shows its tree, so the plugin loads, and the other trees are
 * unchanged, without Git, GitHub or GitLab (changes-tree/07 R21). Plugin Verifier checks the same on each target IDE.
 */
class OptionalDescriptorTest : BasePlatformTestCase() {

    private fun descriptor(name: String) = javaClass.classLoader.getResourceAsStream("META-INF/$name")!!.use {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }

    /** The optional descriptors of plugin.xml by config file, with the plugin each depends on. */
    private fun optionalDependencies(): Map<String, String> {
        val depends = descriptor("plugin.xml").getElementsByTagName("depends")
        return (0 until depends.length).map { depends.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("optional") == "true" }
            .associate { it.getAttribute("config-file") to it.textContent.trim() }
    }

    private fun adapters(file: String): List<String> {
        val elements = descriptor(file).getElementsByTagName("reviewTreeAdapter")
        return (0 until elements.length).map { (elements.item(it) as org.w3c.dom.Element).getAttribute("implementation") }
    }

    fun `test each review tree adapter has its own optional descriptor`() {
        // changes-tree/07 R21
        val dependencies = optionalDependencies()
        assertEquals("Git4Idea", dependencies["smartdiff-vcs-log.xml"])
        assertEquals("org.jetbrains.plugins.github", dependencies["smartdiff-github.xml"])
        assertEquals("org.jetbrains.plugins.gitlab", dependencies["smartdiff-gitlab.xml"])

        assertEquals(listOf(GitLogTreeAdapter::class.java.name), adapters("smartdiff-vcs-log.xml"))
        assertEquals(listOf(GitHubTreeAdapter::class.java.name), adapters("smartdiff-github.xml"))
        assertEquals(listOf(GitLabTreeAdapter::class.java.name), adapters("smartdiff-gitlab.xml"))
    }

    fun `test no other descriptor registers a review tree adapter`() {
        // changes-tree/07 R21
        val others = optionalDependencies().keys - setOf("smartdiff-vcs-log.xml", "smartdiff-github.xml", "smartdiff-gitlab.xml")
        for (file in others + "plugin.xml") assertEquals(file, emptyList<String>(), adapters(file))
    }

    fun `test the adapters of the installed plugins are loaded`() {
        // changes-tree/07 R21: the test IDE bundles Git, GitHub and GitLab
        val loaded: List<Class<*>> = ReviewTreeAdapter.EP_NAME.extensionList.map { it.javaClass }
        assertTrue(GitLogTreeAdapter::class.java in loaded)
        assertTrue(GitHubTreeAdapter::class.java in loaded)
        assertTrue(GitLabTreeAdapter::class.java in loaded)
    }
}
