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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    // Spike S8 prints "S8 ..." lines; copy them into the build log.
    addTestOutputListener(TestOutputListener { _, event ->
        if (event.message.startsWith("S8 ")) logger.lifecycle(event.message.trimEnd())
    })
}
