import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.ChangelogSectionUrlBuilder
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    // 2.16.0 to 2.19.0 can fail to find bundled plugins such as com.intellij.java (ClosedFileSystemException
    // while indexing the IDE, JetBrains/intellij-platform-gradle-plugin#2192). Move up once a release has the fix.
    id("org.jetbrains.intellij.platform") version "2.15.0"
    id("org.jetbrains.changelog") version "2.4.0"
    `java-test-fixtures`
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
        bundledPlugin("org.intellij.groovy")
        // PythonCore's descriptor requires these bundled language plugins; a test application loads only the plugins
        // and modules it declares, so without them PythonCore is rejected as an unmet dependency in tests.
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.toml.lang")
        bundledPlugin("com.jetbrains.sh")
        // Not bundled with the IDE: Marketplace releases, pinned in gradle.properties.
        plugin("org.intellij.scala", providers.gradleProperty("scalaPluginVersion").get())
        plugin("PythonCore", providers.gradleProperty("pythonPluginVersion").get())
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("com.intellij.properties")
        bundledPlugin("org.intellij.plugins.markdown")
        // Review tree summaries (changes-tree/07, ADR 0013): the Git log tree needs Git, the pull and merge request trees
        // need the GitHub and GitLab plugins and the collaboration tools module their changes come from.
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
        bundledPlugin("org.jetbrains.plugins.gitlab")
        bundledModule("intellij.platform.collaborationTools")
        // The Rust provider compiles in :rust against the root's main classes and is packaged here; runtimeOnly keeps
        // it off the root compile classpath so :rust can compileOnly-depend on :root without a task cycle (05-rust-details §2).
        pluginModule(runtimeOnly(project(":rust")))
        // The JavaScript and TypeScript provider, laid out like :rust (ADR 0011 amendment, languages/16-javascript-typescript-details §2).
        pluginModule(runtimeOnly(project(":javascript")))
        // The Go provider is built the same way in :go (languages/17-go-details §2).
        pluginModule(runtimeOnly(project(":go")))
        // The CSS, SCSS and Less provider is built the same way in :css (languages/18-css-details §2).
        pluginModule(runtimeOnly(project(":css")))
        // The PHP provider is built the same way in :php (languages/19-php-details §2).
        pluginModule(runtimeOnly(project(":php")))
        // S9 spike and structure-view fallback tests: bundled with IntelliJ IDEA, test-only (languages/06); JSON and
        // Markdown are already on the classpath above.
        testBundledPlugin("com.intellij.properties")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testFixturesImplementation("junit:junit:4.13.2")
    testFixturesImplementation("org.opentest4j:opentest4j:1.3.0")

    testImplementation(testFixtures(project(":")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    testImplementation("org.yaml:snakeyaml:2.3")
}

// languages/16-javascript-typescript-details §3, languages/17-go-details §3 and languages/19-php-details §3: the
// verifier's ignored problems for the requested families.
val verifierIgnoredProblems by tasks.registering(VerifierIgnoredProblems::class) {
    val families = verifierFamilies(providers.gradleProperty("smartdiff.verifier.ides").orNull)
    sources.from(verifierIgnoredProblemFiles(families).map { file("gradle/verifier/$it") })
    output = layout.buildDirectory.file("verifier/ignored-problems.txt")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            // Supported on 2025.1 and every later release.
            untilBuild = provider { null }
        }
        // The Marketplace "What's new" text: this version's section of CHANGELOG.md, or the unreleased one before it is patched.
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Secrets come from the environment, set by the release workflow (docs/RELEASING.md); local builds stay unsigned.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release suffix picks the Marketplace channel: 0.2.0-beta.1 goes to "beta", 0.1.0 to the default channel.
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        val verifierIdes = verifierFamilies(providers.gradleProperty("smartdiff.verifier.ides").orNull)
        // languages/16-javascript-typescript-details §3, languages/17-go-details §3, languages/19-php-details §3: each family's
        // false positives for an absent bundled-only
        // optional dependency, chosen by the requested families and still listed in the reports as ignored.
        if (verifierIgnoredProblemFiles(verifierIdes).isNotEmpty()) {
            ignoredProblemsFile = verifierIgnoredProblems.flatMap { it.output }
        }
        ides {
            if ("idea" in verifierIdes) {
                create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
                // IntelliJ IDEA is a single product from 2025.3, so the latest release is of the unified type.
                latest {
                    types = listOf(IntelliJPlatformType.IntellijIdea)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("pycharm" in verifierIdes) {
                create(IntelliJPlatformType.PyCharm, "2025.1.3")
                latest {
                    types = listOf(IntelliJPlatformType.PyCharm)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("rustrover" in verifierIdes) {
                create(IntelliJPlatformType.RustRover, "2025.1.3")
                latest {
                    types = listOf(IntelliJPlatformType.RustRover)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("webstorm" in verifierIdes) {
                // The JavaScript plugin is bundled in WebStorm (languages/16-javascript-typescript-details §3).
                create(IntelliJPlatformType.WebStorm, "2025.1.3")
                latest {
                    types = listOf(IntelliJPlatformType.WebStorm)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("goland" in verifierIdes) {
                // languages/17-go-details §3: GoLand bundles the Go plugin.
                create(IntelliJPlatformType.GoLand, "2025.1.3")
                latest {
                    types = listOf(IntelliJPlatformType.GoLand)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("phpstorm" in verifierIdes) {
                // languages/19-php-details §3: PhpStorm bundles the PHP plugin.
                create(IntelliJPlatformType.PhpStorm, "2025.1.3")
                latest {
                    types = listOf(IntelliJPlatformType.PhpStorm)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
            if ("eap" in verifierIdes) {
                // platform/01 R1, R2: the newest IntelliJ IDEA early-access build, or the newest release when no
                // early-access build is newer. A release is listed by version (2026.3.1), an EAP build by build
                // number (271.1234.5), so both are compared by their branch (263, 271).
                val newestEap = ProductReleasesValueSource {
                    types = listOf(IntelliJPlatformType.IntellijIdea)
                    channels = listOf(ProductRelease.Channel.EAP)
                }
                val newestRelease = ProductReleasesValueSource {
                    types = listOf(IntelliJPlatformType.IntellijIdea)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
                create(newestEap.zip(newestRelease) { eap, release ->
                    listOf(earlyAccessOrRelease(eap.firstOrNull(), release.firstOrNull() ?: error("No IntelliJ IDEA release found")))
                })
            }
        }
    }
}

/** The verifier IDE families of -Psmartdiff.verifier.ides; `eap` is accepted but never in the default set (platform/01 R1, R4). */
fun verifierFamilies(value: String?): Set<String> {
    val known = setOf("idea", "pycharm", "rustrover", "webstorm", "goland", "phpstorm", "eap")
    if (value == null) return setOf("idea", "pycharm", "rustrover", "webstorm", "goland", "phpstorm")
    return value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().also { families ->
        val unknown = families - known
        if (unknown.isNotEmpty()) {
            error("Unknown smartdiff.verifier.ides families: ${unknown.sorted().joinToString(", ")}. Known families: ${known.sorted().joinToString(", ")}")
        }
    }
}

/**
 * The ignored-problems files under gradle/verifier for the requested verifier [families]: `idea` ignores the unresolved
 * JavaScript, Go, CSS and PHP plugin PSI, `webstorm` and `phpstorm` the unresolved Java plugin PSI
 * (languages/16-javascript-typescript-details §3, languages/17-go-details §3, languages/18-css-details §3,
 * languages/19-php-details §3).
 * CI runs one family per invocation, so each family gets only its own file; a run over several families gets them all.
 */
fun verifierIgnoredProblemFiles(families: Set<String>): List<String> = buildList {
    if ("idea" in families) add("ignored-problems-idea.txt")
    // PhpStorm, like WebStorm, has no Java plugin (languages/19-php-details §3).
    if ("webstorm" in families || "phpstorm" in families) add("ignored-problems-webstorm.txt")
}

/** Joins the requested families' ignored-problems files into the one file the verifier takes. */
abstract class VerifierIgnoredProblems : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun join() {
        output.get().asFile.writeText(sources.files.joinToString("") { it.readText().trimEnd() + "\n" })
    }
}

/** The branch of an IDE notation: `IU-271.1234.5` is 271, `IU-2026.3.1` is 263. */
fun branchOf(notation: String): Int {
    val parts = notation.substringAfter('-').split('.')
    val first = parts[0].toInt()
    return if (first >= 2000) (first - 2000) * 10 + parts.getOrElse(1) { "0" }.toInt() else first
}

/**
 * The notation the early-access family verifies: [eap] when it is on a newer branch than [release], otherwise [release],
 * noted in the GitHub step summary when there is one (platform/01 R2).
 */
fun earlyAccessOrRelease(eap: String?, release: String): String {
    if (eap != null && branchOf(eap) > branchOf(release)) return eap
    val note = "No early-access build newer than $release; verified the latest release."
    println(note)
    System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotBlank() }?.let { File(it).appendText("$note\n") }
    return release
}

// platform/01 R1, R4: a configuration check, run by `check`, of which families the verifier accepts.
val verifierFamiliesTest by tasks.registering {
    group = "verification"
    description = "Checks the -Psmartdiff.verifier.ides families (platform/01 R1, R4)."
    doLast {
        check(verifierFamilies("eap") == setOf("eap")) { "eap is an accepted family" }
        check("eap" !in verifierFamilies(null)) { "eap is not in the default set" }
        check(verifierFamilies(null) == setOf("idea", "pycharm", "rustrover", "webstorm", "goland", "phpstorm")) { "the default set adds webstorm, goland and phpstorm" }
        check(verifierFamilies("webstorm") == setOf("webstorm")) { "webstorm is an accepted family" }
        check(verifierFamilies("goland") == setOf("goland")) { "goland is an accepted family" }
        check(verifierFamilies("phpstorm") == setOf("phpstorm")) { "phpstorm is an accepted family" }
        check(runCatching { verifierFamilies("idea,nope") }.isFailure) { "an unknown family still fails" }
        check(branchOf("IU-2026.3.1") == 263 && branchOf("IU-271.1234.5") == 271 && branchOf("IC-2025.1") == 251) { "branches compare" }
        check(earlyAccessOrRelease("IU-271.1234.5", "IU-2026.3.1") == "IU-271.1234.5") { "a newer EAP build is verified" }
        check(earlyAccessOrRelease("IU-263.100.1", "IU-2026.3.1") == "IU-2026.3.1") { "an EAP build on the release's branch is not newer" }
        check(earlyAccessOrRelease(null, "IU-2026.3.1") == "IU-2026.3.1") { "no EAP build verifies the release" }
        // languages/16-javascript-typescript-details §3: each family gets only its own ignored problems.
        check(verifierIgnoredProblemFiles(setOf("idea")) == listOf("ignored-problems-idea.txt")) { "idea ignores the JavaScript, Go, CSS and PHP PSI" }
        check(verifierIgnoredProblemFiles(setOf("webstorm")) == listOf("ignored-problems-webstorm.txt")) { "webstorm ignores the Java PSI" }
        check(verifierIgnoredProblemFiles(setOf("phpstorm")) == listOf("ignored-problems-webstorm.txt")) { "phpstorm ignores the Java PSI as webstorm does" }
        check(verifierIgnoredProblemFiles(setOf("webstorm", "phpstorm")) == listOf("ignored-problems-webstorm.txt")) { "the Java file is passed once" }
        check(verifierIgnoredProblemFiles(setOf("pycharm", "rustrover", "goland", "eap")).isEmpty()) { "the other families ignore nothing" }
        val ignored = listOf("idea", "webstorm").associateWith { family ->
            file("gradle/verifier/ignored-problems-$family.txt").readLines().filter { it.isNotBlank() && !it.startsWith("//") }
        }
        ignored.forEach { (family, lines) ->
            check(lines.isNotEmpty()) { "$family has ignored problems" }
            lines.forEach { line ->
                val pattern = line.removePrefix("io.github.agadek.smartdiff:")
                check(pattern != line && ':' !in pattern) { "$family: '$line' is scoped to the plugin and has one pattern" }
                Regex(pattern)
            }
        }
        check(ignored.getValue("idea").all { "com\\.intellij\\.lang\\.(javascript|ecmascript6|typescript)" in it || "com\\.goide" in it || "com\\.intellij\\.psi\\.css" in it || "com\\.jetbrains\\.php" in it }) { "idea ignores only JavaScript, Go, CSS and PHP plugin packages" }
        check(ignored.getValue("idea").any { "com\\.goide" in it }) { "idea ignores the Go PSI" }
        // languages/18-css-details §3: the CSS PSI, and nothing of the Sass or Less plugins, which no class uses.
        check(ignored.getValue("idea").any { "com\\.intellij\\.psi\\.css" in it }) { "idea ignores the CSS PSI" }
        check(ignored.values.flatten().none { Regex("plugins\\\\\\.(sass|scss|less)").containsMatchIn(it) }) { "no family ignores the Sass or Less plugins" }
        check(ignored.getValue("idea").any { "com\\.jetbrains\\.php" in it }) { "idea ignores the PHP PSI" }
        check(ignored.getValue("webstorm").none { "javascript" in it || "ecmascript" in it || "typescript" in it }) { "webstorm keeps every JavaScript check" }
        check(ignored.getValue("webstorm").none { it.endsWith("com\\.intellij\\.psi") || ".*" in it || "[" in it }) { "webstorm ignores Java classes by name, not a com.intellij.psi prefix" }
    }
}

// platform/01 R8: every experimental, internal, deprecated or scheduled-for-removal use by a plugin class that a
// verifier report names must be listed in docs/PLATFORM-API.md. Run after verifyPlugin; `check` does not depend on it.
val checkPlatformApiInventory by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when a Plugin Verifier report names an API use that docs/PLATFORM-API.md does not list (platform/01 R8)."
    dependsOn("testClasses")
    // The IDE supplies the Kotlin standard library to tests; this plain JVM run needs its own.
    classpath = sourceSets["test"].runtimeClasspath +
        configurations.detachedConfiguration(dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:2.1.20"))
    mainClass = "io.github.agadek.smartdiff.platform.PlatformApiInventoryCheck"
    args(layout.buildDirectory.dir("reports/pluginVerifier").get().asFile.path, file("docs/PLATFORM-API.md").path)
}

tasks.named("check") { dependsOn(verifierFamiliesTest) }

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/agadek/smart-diff"
    // Release tags are v<version> (docs/RELEASING.md), so the version links point at those tags.
    sectionUrlBuilder = object : ChangelogSectionUrlBuilder {
        override fun build(repositoryUrl: String, currentVersion: String?, previousVersion: String?, isUnreleased: Boolean) =
            repositoryUrl + when {
                isUnreleased && previousVersion == null -> "/commits"
                isUnreleased -> "/compare/v$previousVersion...HEAD"
                previousVersion == null -> "/commits/v$currentVersion"
                else -> "/compare/v$previousVersion...v$currentVersion"
            }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    // K2 is the Kotlin plugin's default mode from 2025.1; set it explicitly so the Kotlin tests always run in it.
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // -Psmartdiff.updateGolden=true rewrites the golden fixtures' expected files; -Psmartdiff.limits.corpus=<list> runs the limits
    // benchmark; -Psmartdiff.validation.corpus=<list> runs the corpus validation over real commits;
    // -Psmartdiff.validation.merges=<list> runs the merge corpus validation;
    // -Psmartdiff.relationships.corpus=<list> runs the relationship benchmark; -Psmartdiff.relationships.drafts=<dir>
    // writes unreviewed draft labels for its unlabelled real-commit files.
    systemProperty("smartdiff.updateGolden", providers.gradleProperty("smartdiff.updateGolden").getOrElse("false"))
    systemProperty("smartdiff.limits.corpus", providers.gradleProperty("smartdiff.limits.corpus").getOrElse(""))
    systemProperty("smartdiff.validation.corpus", providers.gradleProperty("smartdiff.validation.corpus").getOrElse(""))
    systemProperty("smartdiff.validation.merges", providers.gradleProperty("smartdiff.validation.merges").getOrElse(""))
    // -Psmartdiff.validation.treesitter.corpus=<list> runs the Tree-sitter corpus validation (engines/05 R30).
    systemProperty("smartdiff.validation.treesitter.corpus", providers.gradleProperty("smartdiff.validation.treesitter.corpus").getOrElse(""))
    systemProperty("smartdiff.relationships.corpus", providers.gradleProperty("smartdiff.relationships.corpus").getOrElse(""))
    systemProperty("smartdiff.relationships.gate", providers.gradleProperty("smartdiff.relationships.gate").getOrElse("no-new-wrong-pairs"))
    systemProperty("smartdiff.relationships.baseline", providers.gradleProperty("smartdiff.relationships.baseline").getOrElse(""))
    systemProperty("smartdiff.relationships.tolerance", providers.gradleProperty("smartdiff.relationships.tolerance").getOrElse("0"))
    systemProperty("smartdiff.relationships.drafts", providers.gradleProperty("smartdiff.relationships.drafts").getOrElse(""))
    // The golden fixtures, the limits benchmark and the corpus validation print "GOLDEN ...", "BENCHMARK ..." and
    // "VALIDATION ..." lines; copy them into the build log.
    addTestOutputListener(TestOutputListener { _, event ->
        if (listOf("BENCHMARK ", "GOLDEN ", "RELATIONSHIPS ", "VALIDATION ").any { event.message.startsWith(it) }) logger.lifecycle(event.message.trimEnd())
    })
}

// declarations/10 R21: the fine-grained differencing validation against Difftastic and GumTree, run by hand on a
// developer's machine with `-Psmartdiff.fineGrained.corpus=<list>`; not part of `check` or the release. Both tools are
// found on the PATH (`difft`, `gumtree`) and are never dependencies of the plugin.
// It runs the platform test task filtered to FineGrainedBaselinesTest, so the test gets the IDE set-up `test` has.
val fineGrainedBaselines by tasks.registering {
    group = "verification"
    description = "Compares changed runs with the native diff, Difftastic and GumTree on a corpus (declarations/10 R21)."
    dependsOn(tasks.test)
}

gradle.taskGraph.whenReady {
    if (!hasTask(fineGrainedBaselines.get())) return@whenReady
    val corpus = providers.gradleProperty("smartdiff.fineGrained.corpus").getOrElse("")
    val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    fun onPath(name: String) = path.map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
    if (corpus.isBlank()) throw GradleException("fineGrainedBaselines needs -Psmartdiff.fineGrained.corpus=<list of repositories and commits>")
    val missing = listOf("difft" to "Difftastic (brew install difftastic)", "gumtree" to "GumTree (a release zip from github.com/GumTreeDiff/gumtree, its bin directory on the PATH)")
        .filter { onPath(it.first) == null }
    if (missing.isNotEmpty()) throw GradleException("fineGrainedBaselines needs ${missing.joinToString(" and ") { "`${it.first}`, from ${it.second}," }} on the PATH")
    tasks.test {
        filter { includeTestsMatching("*.FineGrainedBaselinesTest") }
        outputs.upToDateWhen { false }
        systemProperty("smartdiff.fineGrained.corpus", corpus)
        systemProperty("smartdiff.fineGrained.difft", onPath("difft")!!)
        systemProperty("smartdiff.fineGrained.gumtree", onPath("gumtree")!!)
    }
}

apply(from = "gradle/treesitter-helper.gradle.kts")
