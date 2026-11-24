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
    // pluginModule(runtimeOnly(project(":go"))) so compileOnly avoids a root-compile <-> :go-jar task cycle
    // while the packaged plugin still contains these classes once (languages/17-go-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        // Go plugin 251.26927.53 (Marketplace update 780515, since 251.26927, until 251.*). It declares
        // com.intellij.modules.lang, com.intellij.modules.ultimate, com.intellij.modules.go-capable and
        // com.intellij.modules.json; IU bundles the JSON plugin, so the test sandbox needs it explicitly.
        plugin("org.jetbrains.plugins.go:251.26927.53")
        bundledPlugin("com.intellij.modules.json")
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
    // The fixtures live in the root's test data, so a change to them reruns these tests.
    inputs.dir(rootProject.file("src/test/testData/go")).withPropertyName("goTestData")
    inputs.file(rootProject.file("src/test/testData/relationships-corpus-go.txt")).withPropertyName("goRelationshipCorpus")
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    systemProperty(
        "smartdiff.validation.go.corpus",
        providers.gradleProperty("smartdiff.validation.go.corpus").getOrElse(""),
    )
    systemProperty("smartdiff.spike.out", rootProject.layout.buildDirectory.dir("go-spike").get().asFile.absolutePath)
    // Spike S13 prints "S13 ..." lines; golden fixtures print "GOLDEN ..." lines; validation prints "GO VALIDATION ...".
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("S13 ") || message.startsWith("GOLDEN ") || message.startsWith("GO VALIDATION ")) {
            logger.lifecycle(message)
        }
    })
}
