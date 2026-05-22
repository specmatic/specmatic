package application.mcp.server.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream

private val systemIoLock = Any()
private const val mcpQuietModeProperty = "specmatic.mcp.quiet"

internal fun <T> captureStandardStreams(block: () -> T): Triple<T, String, String> {
    synchronized(systemIoLock) {
        val originalOut = System.out
        val originalErr = System.err
        val originalQuietMode = System.getProperty(mcpQuietModeProperty)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        System.setProperty(mcpQuietModeProperty, "true")

        return try {
            val result = block()
            Triple(result, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            if (originalQuietMode == null) {
                System.clearProperty(mcpQuietModeProperty)
            } else {
                System.setProperty(mcpQuietModeProperty, originalQuietMode)
            }
        }
    }
}
