// The Tree-sitter helper of ADR 0016 (engines/05-tree-sitter-details §9): fetched from pinned, checksummed sources and
// compiled with the host's C compiler into build/treesitter/helpers/<platform>/. Other platforms' helpers are built by
// their own CI runners and copied into the same folder before the plugin is packaged.

import java.net.URI
import java.security.MessageDigest
import org.gradle.kotlin.dsl.support.serviceOf

/** One pinned source archive; [name] is also the folder it is extracted to. */
data class TreeSitterSource(val name: String, val repository: String, val tag: String, val sha256: String) {
    val url get() = "https://github.com/$repository/archive/refs/tags/$tag.tar.gz"
}

// engines/05 R27, R29: these versions are the engine version's grammar versions and the notices' entries.
val treeSitterSources = listOf(
    TreeSitterSource("tree-sitter", "tree-sitter/tree-sitter", "v0.25.3", "862fac52653bc7bc9d2cd0630483e6bdf3d02bcd23da956ca32663c4798a93e3"),
    TreeSitterSource("swift", "alex-pinkus/tree-sitter-swift", "0.7.4-with-generated-files", "bf4d34383638f41cde398f4678dea72cdcda8442c7cfd13944f4bb63e9a96f70"),
    TreeSitterSource("c-sharp", "tree-sitter/tree-sitter-c-sharp", "v0.23.1", "c0b008dca3c6820604bf0853b9668ba034f9750d89d170ba834261e94e2cd917"),
    TreeSitterSource("c", "tree-sitter/tree-sitter-c", "v0.23.2", "d8b9c1b2ffb6a42caf9bc76e07c52507d4e60b17175ed9beb0e779be8db1200c"),
    TreeSitterSource("cpp", "tree-sitter/tree-sitter-cpp", "v0.23.4", "7a2c55afe3028f4105f25762ea58cc16537d1f5a1dcd9cca90410b3cd5d46051"),
)

/** The host's platform folder name, such as `mac-arm64`, as `TreeSitterHelper.platform()` computes it at run time. */
fun treeSitterPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val osName = when {
        os.startsWith("mac") -> "mac"
        os.startsWith("windows") -> "windows"
        os.startsWith("linux") -> "linux"
        else -> error("No Tree-sitter helper for the OS $os")
    }
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> error("No Tree-sitter helper for the architecture $a")
    }
    return "$osName-$arch"
}

val treeSitterRoot = layout.buildDirectory.dir("treesitter")
val treeSitterHelpers = treeSitterRoot.map { it.dir("helpers") }
val windowsHost = System.getProperty("os.name").lowercase().startsWith("windows")
val helperName = if (windowsHost) "smartdiff-treesitter.exe" else "smartdiff-treesitter"

val fetchTreeSitterSources by tasks.registering {
    group = "build"
    description = "Downloads the pinned Tree-sitter runtime and grammar sources and checks their SHA-256 (ADR 0016)."
    val archives = treeSitterRoot.map { it.dir("archives") }
    inputs.property("sources", treeSitterSources.map { "${it.url} ${it.sha256}" })
    outputs.dir(archives)
    doLast {
        val dir = archives.get().asFile.apply { mkdirs() }
        for (source in treeSitterSources) {
            val file = dir.resolve("${source.name}.tar.gz")
            fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (!file.exists() || digest() != source.sha256) {
                URI(source.url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val actual = digest()
            check(actual == source.sha256) { "${source.url} has SHA-256 $actual, expected ${source.sha256}" }
        }
    }
}

val extractTreeSitterSources by tasks.registering(Sync::class) {
    group = "build"
    description = "Extracts the pinned Tree-sitter sources."
    dependsOn(fetchTreeSitterSources)
    for (source in treeSitterSources) {
        from(tarTree(resources.gzip(treeSitterRoot.map { it.file("archives/${source.name}.tar.gz") }))) {
            into(source.name)
            // Each archive has one top folder, named after the repository and tag.
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray().let { arrayOf(source.name) + it }) }
            includeEmptyDirs = false
        }
    }
    into(treeSitterRoot.map { it.dir("src") })
}

val buildTreeSitterHelper by tasks.registering {
    group = "build"
    description = "Compiles the Tree-sitter helper for this computer's platform (ADR 0016)."
    dependsOn(extractTreeSitterSources)
    val sources = treeSitterRoot.map { it.dir("src") }
    val objects = treeSitterRoot.map { it.dir("obj") }
    val main = layout.projectDirectory.file("treesitter-helper/main.c")
    // -PtreeSitterPlatform=mac-x64 with CC="clang -arch x86_64" cross-builds the Intel Mac helper on an Apple silicon Mac.
    val platform = providers.gradleProperty("treeSitterPlatform").orElse(provider { treeSitterPlatform() })
    val output = platform.flatMap { p -> treeSitterHelpers.map { it.file("$p/$helperName") } }
    // A workflow passes CC="" where its matrix sets none, which means the default.
    val compiler = providers.environmentVariable("CC").filter { it.isNotBlank() }.orElse(if (windowsHost) "clang" else "cc")
    inputs.dir(sources)
    inputs.file(main)
    inputs.property("compiler", compiler)
    inputs.property("platform", platform)
    outputs.file(output)
    doLast {
        val exec = project.serviceOf<org.gradle.process.ExecOperations>()
        val src = sources.get().asFile
        val obj = objects.get().asFile.apply { deleteRecursively(); mkdirs() }
        // CC may carry flags, such as "clang -arch x86_64".
        val cc = compiler.get().trim().split(Regex("\\s+"))
        val runtime = src.resolve("tree-sitter/lib")
        val defines = if (windowsHost) listOf("-D_CRT_SECURE_NO_WARNINGS") else listOf("-D_DEFAULT_SOURCE", "-D_POSIX_C_SOURCE=200112L")
        fun compile(file: File, out: String, vararg includes: File) {
            exec.exec {
                commandLine(cc + listOf("-O2", "-std=c11", "-w") + defines + includes.map { "-I$it" } + listOf("-c", file.path, "-o", obj.resolve(out).path))
            }
        }
        compile(runtime.resolve("src/lib.c"), "lib.o", runtime.resolve("include"), runtime.resolve("src"))
        for (grammar in listOf("swift", "c-sharp", "c", "cpp")) {
            val dir = src.resolve("$grammar/src")
            compile(dir.resolve("parser.c"), "$grammar-parser.o", dir)
            dir.resolve("scanner.c").takeIf { it.exists() }?.let { compile(it, "$grammar-scanner.o", dir) }
        }
        compile(main.asFile, "main.o", runtime.resolve("include"))
        val executable = output.get().asFile.apply { parentFile.mkdirs() }
        exec.exec {
            commandLine(cc + listOf("-O2") + obj.listFiles()!!.filter { it.extension == "o" }.sorted().map { it.path } + listOf("-o", executable.path))
        }
        if (!windowsHost) exec.exec { commandLine("strip", executable.path) }
        executable.setExecutable(true)
    }
}

// The helpers ship in the plugin folder under treesitter/<platform>/ (R26). A local build has only its own platform's.
tasks.named<Sync>("prepareSandbox") {
    dependsOn(buildTreeSitterHelper)
    from(treeSitterHelpers) { into("${project.name}/treesitter") }
}

tasks.named<Test>("test") {
    dependsOn(buildTreeSitterHelper)
    systemProperty("smartdiff.treesitter.helper", treeSitterHelpers.get().file("${treeSitterPlatform()}/$helperName").asFile.path)
}

// engines/05 R27: every pinned source has its notice, with the same version.
val checkTreeSitterNotices by tasks.registering {
    group = "verification"
    description = "Checks that THIRD-PARTY-NOTICES.md lists every Tree-sitter source with its version (engines/05 R27)."
    val notices = layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")
    inputs.file(notices)
    doLast {
        val text = notices.asFile.readText()
        val missing = treeSitterSources.filter { "- Component: `${it.name}`\n- Version: `${it.tag}`" !in text }
        check(missing.isEmpty()) { "THIRD-PARTY-NOTICES.md lacks ${missing.joinToString { "${it.name} ${it.tag}" }}" }
    }
}

// engines/05 R28: the plugin archive grows by at most 25 MB with the helpers.
val checkTreeSitterArchiveSize by tasks.registering {
    group = "verification"
    description = "Checks that the plugin archive is at most 25 MB larger than before the Tree-sitter helpers (engines/05 R28)."
    dependsOn("buildPlugin")
    val archive = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val baseline = providers.gradleProperty("treeSitterArchiveBaselineBytes").map(String::toLong)
    doLast {
        val size = archive.get().asFile.length()
        val limit = baseline.get() + 25L * 1024 * 1024
        check(size <= limit) { "the plugin archive is $size bytes, more than $limit" }
        logger.lifecycle("Plugin archive: $size bytes, ${size - baseline.get()} more than before the Tree-sitter helpers")
    }
}

tasks.named("check") { dependsOn(checkTreeSitterNotices, checkTreeSitterArchiveSize) }

// The notices ship with the plugin.
tasks.named<Sync>("prepareSandbox") {
    from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) { into(project.name) }
}

// CI runs this on each platform's runner, where the full test suite does not run: the helper parses one C line.
val smokeTestTreeSitterHelper by tasks.registering {
    group = "verification"
    description = "Starts this platform's Tree-sitter helper and checks that it parses one line of C (engines/05 R22)."
    dependsOn(buildTreeSitterHelper)
    val executable = treeSitterHelpers.map { it.file("${treeSitterPlatform()}/$helperName") }
    doLast {
        val text = "int x;"
        val request = java.nio.ByteBuffer.allocate(5 + text.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        request.put(2).putInt(text.length * 2)
        text.forEach { request.putChar(it) }
        val process = ProcessBuilder(executable.get().asFile.path).start()
        process.outputStream.use { it.write(request.array()) }
        val answer = process.inputStream.readAllBytes()
        check(process.waitFor() == 0) { "the helper exited with ${process.exitValue()}" }
        val ints = java.nio.ByteBuffer.wrap(answer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = ints.int
        check(count > 1 && answer.size == 4 + count * 24) { "the helper sent $count nodes in ${answer.size} bytes" }
        check(ints.getInt(4 + 8) == text.length) { "the root does not cover the text" }
        logger.lifecycle("Tree-sitter helper ${treeSitterPlatform()}: $count nodes for `$text`")
    }
}
