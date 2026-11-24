package io.github.agadek.smartdiff

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * The package dependency rules of technical specification §2, for the packages under [root].
 *
 * Empty packages pass, so the rules can exist before the code they guard.
 */
class ArchitectureRules(private val root: String) {

    private val api = "$root.engine.api.."
    private val registry = "$root.engine.registry.."
    private val merge = "$root.merge.."

    val uiDependsOnlyOnApiAndRegistry: ArchRule =
        noClasses().that().resideInAPackage("$root.ui..")
            .should().dependOnClassesThat(
                resideInAPackage("$root.engine..").and(not(resideInAnyPackage(api, registry))),
            )
            .allowEmptyShould(true)
            .because("ui may use engine.api, engine.registry and the reference pass, never a concrete engine")

    val apiDependsOnNothingInThePlugin: ArchRule =
        classes().that().resideInAPackage(api)
            .should().onlyDependOnClassesThat(resideInAPackage(api).or(not(resideInAPackage("$root.."))))
            .allowEmptyShould(true)
            .because("engine.api depends on nothing in the plugin")

    val mergeDependsOnlyOnApi: ArchRule =
        classes().that().resideInAPackage(merge)
            .should().onlyDependOnClassesThat(resideInAnyPackage(merge, api).or(not(resideInAPackage("$root.."))))
            .allowEmptyShould(true)
            .because("the merge join reads engine results through engine.api only")

    val enginesAreIndependent: ArchRule =
        slices().matching("$root.engine.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(api, registry))
            // engines/05-tree-sitter-details §4: the Tree-sitter engine builds declaration trees and compares them with
            // the declaration engine's IntelliJ-free model and analysis, never with its PSI providers.
            .ignoreDependency(
                resideInAPackage("$root.engine.treesitter.."),
                resideInAnyPackage("$root.engine.declarations.model..", "$root.engine.declarations.analysis.."),
            )
            .allowEmptyShould(true)
            .because("no engine depends on another engine, and the registry knows no concrete engine")

    val coreIsIntelliJFree: ArchRule =
        noClasses().that().resideInAnyPackage(
            api,
            "$root.engine.declarations.model..",
            "$root.engine.declarations.analysis..",
            merge,
        )
            .should().dependOnClassesThat().resideInAnyPackage("com.intellij..", "org.jetbrains.kotlin..", "org.jetbrains.plugins.scala..", "scala..")
            .allowEmptyShould(true)
            .because("the engine API, declaration model, analysis and merge join must run without the IntelliJ Platform")

    /**
     * platform/01 R7: each API of `docs/PLATFORM-API.md` (a class, or a package ending in `..`) and the adapters that
     * alone may use it (classes, or packages ending in `..`). The Rust, Go and CSS plugins' rows are checked by the `rust`, `go` and `css` subprojects.
     */
    val platformApiAdapters: Map<String, List<String>> = mapOf(
        "com.intellij.openapi.vcs.changes.ChangesViewNodeAction" to listOf("$root.ui.changes.DeclarationNodeAction"),
        "com.intellij.collaboration.util.RefComparisonChange" to listOf("$root.ui.changes.CodeReviewTreeAdapter"),
        "com.jetbrains.python.ast.PyAstClass" to listOf("$root.engine.declarations.psi.python.."),
        "com.jetbrains.python.ast.PyAstFunction" to listOf("$root.engine.declarations.psi.python.."),
        "com.jetbrains.python.ast.PyAstNamedParameter" to listOf("$root.engine.declarations.psi.python.."),
        "com.jetbrains.python.ast.PyAstTargetExpression" to listOf("$root.engine.declarations.psi.python..", "$root.references.PythonReferenceResolver"),
        "com.jetbrains.python.ast.PyAstReferenceExpression" to listOf("$root.references.PythonReferenceResolver"),
        "org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader" to listOf("$root.engine.declarations.psi.markdown.."),
        "org.jetbrains.plugins.scala.." to listOf("$root.engine.declarations.psi.scala..", "$root.references.ScalaReferenceResolver"),
        "com.jetbrains.python.." to listOf("$root.engine.declarations.psi.python..", "$root.references.PythonReferenceResolver"),
        "org.jetbrains.kotlin.analysis.." to listOf("$root.references.KotlinReferenceResolver"),
    )

    /** One rule per entry of [platformApiAdapters], by API (platform/01 R7). */
    val platformApi: Map<String, ArchRule> = platformApiAdapters.mapValues { (api, adapters) -> onlyAdaptersUse(root, api, adapters) }

    val all: List<ArchRule> = listOf(
        uiDependsOnlyOnApiAndRegistry,
        apiDependsOnNothingInThePlugin,
        mergeDependsOnlyOnApi,
        enginesAreIndependent,
        coreIsIntelliJFree,
    )
}

/** A class named [name] or nested in it, or a class in the package [name] when it ends in `..`. */
internal fun named(name: String): DescribedPredicate<JavaClass> =
    if (name.endsWith("..")) {
        resideInAPackage(name)
    } else {
        DescribedPredicate.describe(name) { it.name == name || it.name.startsWith("$name$") }
    }

/** No class under [root] outside [adapters] depends on [api] (platform/01 R7). */
fun onlyAdaptersUse(root: String, api: String, adapters: List<String>): ArchRule =
    noClasses().that(resideInAPackage("$root..").and(not(adapters.map(::named).reduce { a, b -> a.or(b) })))
        .should().dependOnClassesThat(named(api))
        .allowEmptyShould(true)
        .because("only ${adapters.joinToString(", ")} may use $api, as docs/PLATFORM-API.md lists (platform/01 R7)")
