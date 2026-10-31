package archfixture.violating.merge

import archfixture.violating.engine.registry.Registry
import com.intellij.openapi.project.Project

// Violates: the merge join depends on the registry, and on IntelliJ.
class Join(val registry: Registry, val project: Project)
