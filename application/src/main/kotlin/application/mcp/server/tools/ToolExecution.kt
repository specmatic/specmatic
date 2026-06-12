package application.mcp.server.tools

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.text.Charsets

private val systemIoLock = Any()

/**
 * A memory-safe OutputStream that caps data at [maxBytes].
 *
 * This is used for MCP tool execution because we only return the first 4,000 characters
 * of logs to the AI. This prevents the server from consuming unbounded memory (OutOfMemoryError)
 * if a test suite generates very large logs.
 */
class BoundedByteArrayOutputStream(private val maxBytes: Int) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (buffer.size() < maxBytes) {
            buffer.write(b)
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (buffer.size() < maxBytes) {
            val bytesToWrite = minOf(len, maxBytes - buffer.size())
            buffer.write(b, off, bytesToWrite)
        }
    }

    fun size(): Int = buffer.size()

    fun toString(charset: Charset): String = buffer.toString(charset)
}

fun <T> captureStandardStreams(block: () -> T): Triple<T, String, String> = synchronized(systemIoLock) {
    val originalOut = System.out
    val originalErr = System.err

    // We cap output at 16KB because MCP tools only send back the first 4000 characters to the AI anyway.
    val outBuffer = BoundedByteArrayOutputStream(16384)
    val errBuffer = BoundedByteArrayOutputStream(16384)

    try {
        System.setOut(PrintStream(outBuffer, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuffer, true, Charsets.UTF_8))

        val result = block()
        Triple(result, outBuffer.toString(Charsets.UTF_8), errBuffer.toString(Charsets.UTF_8))
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
}
