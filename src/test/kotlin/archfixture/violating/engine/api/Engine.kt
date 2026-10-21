package archfixture.violating.engine.api

import archfixture.violating.engine.registry.Registry
import com.intellij.openapi.project.Project

// Violates: api depends on the plugin's registry, and on IntelliJ.
interface Engine {
    fun create(project: Project, registry: Registry)
}
