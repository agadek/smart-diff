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
    // pluginModule(runtimeOnly(project(":css"))), as it does :go (languages/18-css-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        // Ultimate bundles the CSS plugin and its Sass and Less dialect plugins, so there is no Marketplace pin. The CSS
        // plugin declares <depends>com.intellij.modules.xml</depends> and com.intellij.platform.images.
        bundledPlugin("com.intellij.css")
        bundledPlugin("org.jetbrains.plugins.sass")
        bundledPlugin("org.jetbrains.plugins.less")
        testBundledPlugin("com.intellij.css")
        testBundledPlugin("org.jetbrains.plugins.sass")
        testBundledPlugin("org.jetbrains.plugins.less")
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
    // The fixtures live in the root's test data, so a change to them reruns these tests.
    inputs.dir(rootProject.file("src/test/testData/css")).withPropertyName("cssTestData")
    inputs.dir(rootProject.file("src/test/testData/scss")).withPropertyName("scssTestData")
    inputs.dir(rootProject.file("src/test/testData/less")).withPropertyName("lessTestData")
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    // Golden fixtures print "GOLDEN ..." lines.
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("GOLDEN ")) {
            logger.lifecycle(message)
        }
    })
}
