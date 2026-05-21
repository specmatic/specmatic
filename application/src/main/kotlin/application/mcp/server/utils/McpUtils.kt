package application.mcp.server.utils

import application.SpecmaticApplication
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI

object McpUtils {
    private val systemIoLock = Any()
    private const val mcpQuietModeProperty = "specmatic.mcp.quiet"

    fun <T> captureStandardStreams(block: () -> T): Triple<T, String, String> {
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

    fun safeToolCall(block: () -> String): CallToolResult {
        return try {
            val text = io.specmatic.core.utilities.SystemExit.throwOnExit {
                block()
            }
            CallToolResult(
                content = listOf(TextContent(text = text)),
                isError = false
            )
        } catch (t: Throwable) {
            val errorMessage = when(t) {
                is io.specmatic.core.utilities.SystemExitException -> t.message
                else -> t.message ?: (t::class.simpleName ?: "Unknown error")
            }
            
            t.printStackTrace(System.err)
            CallToolResult(
                content = listOf(
                    TextContent(
                        text = buildString {
                            append("# Specmatic MCP Tool Error\n\n")
                            append("- ")
                            append(errorMessage)
                        }
                    )
                ),
                isError = true
            )
        }
    }

    fun resolveCurrentJarPath(): String {
        System.getenv("SPECMATIC_MCP_JAR")
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it).canonicalPath }

        val codeSource = SpecmaticApplication::class.java.protectionDomain.codeSource?.location
            ?: error("Could not resolve Specmatic executable jar path")
        return File(URI(codeSource.toString())).canonicalPath
    }

    fun resolveJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome).resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        return javaBin.canonicalPath
    }

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
