package application.mcp.server.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.text.Charsets

private val systemIoLock = Any()
private const val PROGRAMMATIC_OUTPUT_PROPERTY = "specmatic.programmaticOutput"


internal fun <T> captureStandardStreams(block: () -> T): Triple<T, String, String> = synchronized(systemIoLock) {
    val originalOut = System.out
    val originalErr = System.err
    val originalQuiet = System.getProperty(PROGRAMMATIC_OUTPUT_PROPERTY)

    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()

    try {
        System.setOut(PrintStream(outBuffer, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuffer, true, Charsets.UTF_8))
        System.setProperty(PROGRAMMATIC_OUTPUT_PROPERTY, "true")

        val result = block()
        Triple(result, outBuffer.toString(Charsets.UTF_8), errBuffer.toString(Charsets.UTF_8))
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)

        if (originalQuiet != null) {
            System.setProperty(PROGRAMMATIC_OUTPUT_PROPERTY, originalQuiet)
        } else {
            System.clearProperty(PROGRAMMATIC_OUTPUT_PROPERTY)
        }
    }
}
