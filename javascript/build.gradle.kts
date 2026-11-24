import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Compile against the root plugin's engine API and PSI base; the root packages this module as
    // pluginModule(runtimeOnly(project(":javascript"))), as it does :rust (languages/16-javascript-typescript-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        // Ultimate bundles the JavaScript plugin, so there is no Marketplace pin (ADR 0011, amendment of 2026-11-24).
        bundledPlugin("JavaScript")
        // The JavaScript plugin declares <depends>com.intellij.modules.json</depends>; the test sandbox needs it explicitly.
        bundledPlugin("com.intellij.modules.json")
        testBundledPlugin("JavaScript")
        testBundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(project(":"))
    testImplementation(testFixtures(project(":")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    // The fixtures live in the root's test data, so a fixture change reruns these tests.
    inputs.dir(rootProject.file("src/test/testData/javascript")).withPropertyName("javascriptFixtures")
    inputs.dir(rootProject.file("src/test/testData/typescript")).withPropertyName("typescriptFixtures")
    inputs.property("updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    // Golden fixtures print "GOLDEN ..." lines; the PSI dump prints "JSPSI ..." lines.
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("GOLDEN ") || message.startsWith("JSPSI")) {
            logger.lifecycle(message)
        }
    })
}
