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
    // pluginModule(runtimeOnly(project(":sql"))) so compileOnly avoids a root-compile <-> :sql-jar task cycle
    // while the packaged plugin still contains these classes once (languages/20-sql-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        // IntelliJ IDEA Ultimate bundles the Database Tools and SQL plugin (languages/20-sql-details §1).
        bundledPlugin("com.intellij.database")
        testBundledPlugin("com.intellij.database")
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
    inputs.dir(rootProject.file("src/test/testData/sql")).withPropertyName("sqlTestData").optional()
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    systemProperty(
        "smartdiff.validation.sql.corpus",
        providers.gradleProperty("smartdiff.validation.sql.corpus").getOrElse(""),
    )
    systemProperty("smartdiff.spike.out", rootProject.layout.buildDirectory.dir("sql-spike").get().asFile.absolutePath)
    // Spike S16 prints "S16 ..." lines; golden fixtures print "GOLDEN ..." lines; validation prints "SQL VALIDATION ...".
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("S16 ") || message.startsWith("GOLDEN ") || message.startsWith("SQL VALIDATION ")) {
            logger.lifecycle(message)
        }
    })
}
