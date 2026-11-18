package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A grammar in the helper, by the number the helper's protocol gives it (ADR 0016). */
enum class TreeSitterGrammar(val code: Int) { SWIFT(0), C_SHARP(1), C(2), CPP(3) }

/** The symbol and field names of one grammar, indexed by the ids a [TreeSitterTree] holds. */
class TreeSitterNames(val symbols: List<String>, val fields: List<String>)

/**
 * One side's syntax tree as the helper sends it: six ints per node in pre-order (symbol, start, end, parent index, field
 * id, flags), with offsets in the request text's `char` units.
 */
class TreeSitterTree(private val data: IntArray) {
    val size: Int get() = data.size / FIELDS

    fun symbol(node: Int) = data[node * FIELDS]
    fun start(node: Int) = data[node * FIELDS + 1]
    fun end(node: Int) = data[node * FIELDS + 2]

    /** The parent's index, or -1 for the root. */
    fun parent(node: Int) = data[node * FIELDS + 3]

    /** The field id the parent gives this node, or 0. */
    fun field(node: Int) = data[node * FIELDS + 4]
    fun isNamed(node: Int) = data[node * FIELDS + 5] and NAMED != 0
    fun isError(node: Int) = data[node * FIELDS + 5] and ERROR != 0
    fun isMissing(node: Int) = data[node * FIELDS + 5] and MISSING != 0

    private companion object {
        const val FIELDS = 6
        const val NAMED = 1
        const val ERROR = 2
        const val MISSING = 4
    }
}

/** The helper died, closed its output or sent an answer that does not fit the protocol (engines/05 R24). */
class TreeSitterHelperException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A running `smartdiff-treesitter` process (ADR 0016) and the protocol to it. It serves one request at a time; callers
 * share one instance through [TreeSitterHelpers] and must not use it after a [TreeSitterHelperException].
 */
class TreeSitterHelper(executable: Path) : AutoCloseable {
    private val process: Process = try {
        ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: IOException) {
        throw TreeSitterHelperException("the Tree-sitter helper could not be started: ${e.message}", e)
    }
    private val output = BufferedOutputStream(process.outputStream, 1 shl 16)
    private val input = DataInputStream(BufferedInputStream(process.inputStream, 1 shl 16))

    @Volatile
    private var closed = false

    /** Whether the helper can take another request: it was not closed after a failure or a cancellation. */
    val isUsable: Boolean get() = !closed && process.isAlive

    /** The names of [grammar]'s symbols and fields. */
    @Synchronized
    fun names(grammar: TreeSitterGrammar, cancellation: Cancellation = Cancellation.NONE): TreeSitterNames = call(cancellation) {
        send(grammar.code or METADATA, ByteArray(0))
        await(cancellation)
        TreeSitterNames(strings(), strings())
    }

    /** Parses [text] with [grammar]; the host's time limit bounds it through [cancellation]. */
    @Synchronized
    fun parse(grammar: TreeSitterGrammar, text: CharSequence, cancellation: Cancellation): TreeSitterTree = call(cancellation) {
        val bytes = ByteBuffer.allocate(text.length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in text.indices) bytes.putChar(text[i])
        send(grammar.code, bytes.array())
        await(cancellation)
        val count = u32()
        if (count <= 0 || count > MAX_NODES) throw TreeSitterHelperException("the Tree-sitter helper sent $count nodes")
        val raw = ByteArray(count * 24)
        input.readFully(raw)
        val data = IntArray(count * 6)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(data)
        TreeSitterTree(data).also { check(it, text.length) }
    }

    override fun close() {
        closed = true
        process.destroyForcibly()
    }

    private fun <T> call(cancellation: Cancellation, block: () -> T): T {
        try {
            return block()
        } catch (e: TreeSitterHelperException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw TreeSitterHelperException("the Tree-sitter helper failed: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: Throwable) {
            // A cancellation, or any other failure while the helper may still be parsing: its next answer would belong
            // to this request, so the helper cannot be reused.
            close()
            throw e
        }
    }

    private fun send(kind: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(kind.toByte()).putInt(payload.size)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    /** Waits for the first byte of the answer, checking [cancellation] meanwhile; the helper answers only when done. */
    private fun await(cancellation: Cancellation) {
        while (input.available() == 0) {
            cancellation.checkCanceled()
            if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS) && input.available() == 0) {
                throw TreeSitterHelperException("the Tree-sitter helper exited with status ${process.exitValue()}")
            }
        }
    }

    private fun u32(): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun strings(): List<String> {
        val count = u32()
        if (count < 0 || count > MAX_NAMES) throw TreeSitterHelperException("the Tree-sitter helper sent $count names")
        return List(count) {
            val b = ByteArray(2)
            input.readFully(b)
            val length = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            String(ByteArray(length).also(input::readFully), Charsets.UTF_8)
        }
    }

    private fun check(tree: TreeSitterTree, length: Int) {
        if (tree.parent(0) != -1 || tree.start(0) < 0 || tree.end(0) > length) {
            throw TreeSitterHelperException("the Tree-sitter helper sent a tree that does not fit the text")
        }
        for (node in 1 until tree.size) {
            val parent = tree.parent(node)
            if (parent !in 0 until node || tree.start(node) > tree.end(node) || tree.start(node) < 0 || tree.end(node) > length) {
                throw TreeSitterHelperException("the Tree-sitter helper sent a tree that does not fit the text")
            }
        }
    }

    companion object {
        private const val METADATA = 0x80
        private const val POLL_MILLIS = 5L

        /** More nodes than any text within the analysis limits can have, so a garbled count fails instead of allocating. */
        private const val MAX_NODES = 50_000_000
        private const val MAX_NAMES = 100_000

        /** The executable's name on this OS. */
        fun executableName(): String = if (System.getProperty("os.name").lowercase().startsWith("windows")) "smartdiff-treesitter.exe" else "smartdiff-treesitter"

        /** This computer's platform folder, such as `mac-arm64`, as the build names it; null on a platform without a helper. */
        fun platform(): String? {
            val os = System.getProperty("os.name").lowercase()
            val osName = when {
                os.startsWith("mac") -> "mac"
                os.startsWith("windows") -> "windows"
                os.startsWith("linux") -> "linux"
                else -> return null
            }
            val arch = when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "x64"
                else -> return null
            }
            return "$osName-$arch"
        }
    }
}
