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
    // pluginModule(runtimeOnly(project(":php"))) so compileOnly avoids a root-compile <-> :php-jar task cycle
    // while the packaged plugin still contains these classes once (languages/19-php-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        // PHP plugin 251.26927.70 (Marketplace plugin 6610, since 251.26927, until 251.*). It declares
        // com.intellij.modules.json, com.intellij.modules.php-capable, com.intellij.modules.ultimate and
        // com.intellij.modules.xml; IU bundles the JSON plugin, so the test sandbox needs it explicitly.
        plugin("com.jetbrains.php:251.26927.70")
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
    inputs.dir(rootProject.file("src/test/testData/php")).withPropertyName("phpTestData")
    inputs.file(rootProject.file("src/test/testData/relationships-corpus-php.txt")).withPropertyName("phpRelationshipCorpus")
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    systemProperty(
        "smartdiff.validation.php.corpus",
        providers.gradleProperty("smartdiff.validation.php.corpus").getOrElse(""),
    )
    systemProperty("smartdiff.spike.out", rootProject.layout.buildDirectory.dir("php-spike").get().asFile.absolutePath)
    // Spike S15 prints "S15 ..." lines; golden fixtures print "GOLDEN ..." lines; validation prints "PHP VALIDATION ...".
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("S15 ") || message.startsWith("GOLDEN ") || message.startsWith("PHP VALIDATION ")) {
            logger.lifecycle(message)
        }
    })
}
