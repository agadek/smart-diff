package archfixture.allowed.engine.registry

import archfixture.allowed.engine.api.Engine
import com.intellij.openapi.project.Project

class Registry(val project: Project?, val engines: List<Engine>)
