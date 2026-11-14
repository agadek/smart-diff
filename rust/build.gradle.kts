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
    // pluginModule(runtimeOnly(project(":rust"))) so compileOnly avoids a root-compile <-> :rust-jar task cycle
    // while the packaged plugin still contains these classes once (languages/05-rust-details §2).
    compileOnly(project(":"))

    intellijPlatform {
        intellijIdeaUltimate("2025.1.3")
        plugin("com.jetbrains.rust:251.26927.79")
        // The Rust plugin declares <plugin id="org.toml.lang"/> and <plugin id="com.intellij.modules.json"/>;
        // IU bundles both, so the test sandbox needs them explicitly.
        bundledPlugin("org.toml.lang")
        bundledPlugin("com.intellij.modules.json")
        testBundledPlugin("org.toml.lang")
        testBundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(project(":"))
    testImplementation(testFixtures(project(":")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    // Spike S8 prints "S8 ..." lines; golden fixtures print "GOLDEN ..." lines; copy them into the build log.
    addTestOutputListener(TestOutputListener { _, event ->
        val message = event.message.trimEnd()
        if (message.startsWith("S8 ") || message.startsWith("GOLDEN ")) logger.lifecycle(message)
    })
}
