package io.github.agadek.smartdiff

import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.base.DescribedPredicate.not
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
            .because("ui may use engine.api and engine.registry, never a concrete engine")

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

    val all: List<ArchRule> = listOf(
        uiDependsOnlyOnApiAndRegistry,
        apiDependsOnNothingInThePlugin,
        mergeDependsOnlyOnApi,
        enginesAreIndependent,
        coreIsIntelliJFree,
    )
}
