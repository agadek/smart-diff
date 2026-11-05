package io.github.agadek.smartdiff.ui

import com.intellij.diff.FrameDiffTool
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FilteringDiffToolTest : BasePlatformTestCase() {

    fun `test the viewer keeps its name and has its own 16px light and dark icon`() {
        // outline/04-smart-diff-viewer R10
        val tool = FilteringDiffTool()
        assertEquals(FilteringDiffTool.NAME, tool.name)

        val icon = tool.icon
        assertEquals(16, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        assertNotNull(IconLoader.getIcon(FilteringDiffTool.ICON_PATH, FilteringDiffTool::class.java))

        val resources = FilteringDiffTool::class.java
        assertNotNull("light icon resource", resources.getResource(FilteringDiffTool.ICON_PATH))
        assertNotNull("dark icon resource", resources.getResource(FilteringDiffTool.ICON_PATH.replace(".svg", "_dark.svg")))

        val platformGetIcon = FrameDiffTool::class.java.methods.find { it.name == "getIcon" && it.parameterCount == 0 }
        if (platformGetIcon != null) {
            assertSame("the platform switcher reads the viewer's own icon", icon, platformGetIcon.invoke(tool))
        }
    }
}
