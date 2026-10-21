import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    // 2.16.0 to 2.19.0 can fail to find bundled plugins such as com.intellij.java (ClosedFileSystemException
    // while indexing the IDE, JetBrains/intellij-platform-gradle-plugin#2192). Move up once a release has the fix.
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "io.github.agadek.smartdiff"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            // Supported on 2025.1 and every later release.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            // IntelliJ IDEA is a single product from 2025.3, so the latest release is of the unified type.
            latest {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    // K2 is the Kotlin plugin's default mode from 2025.1; set it explicitly so the Kotlin tests always run in it.
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // Spike tests print their findings as "SPIKE ..." lines; copy them into the build log.
    addTestOutputListener(TestOutputListener { _, event ->
        if (event.message.startsWith("SPIKE ")) logger.lifecycle(event.message.trimEnd())
    })
}
