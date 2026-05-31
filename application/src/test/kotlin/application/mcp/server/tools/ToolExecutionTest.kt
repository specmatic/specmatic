package application.mcp.server.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.text.Charsets

class ToolExecutionTest {

    @Test
    fun `BoundedByteArrayOutputStream should truncate output beyond maxBytes`() {
        val maxBytes = 10
        val outputStream = BoundedByteArrayOutputStream(maxBytes)
        
        outputStream.write("123456789012345".toByteArray(Charsets.UTF_8))
        
        assertThat(outputStream.size()).isEqualTo(maxBytes)
        assertThat(outputStream.toString(Charsets.UTF_8)).isEqualTo("1234567890")
    }

    @Test
    fun `BoundedByteArrayOutputStream should handle single byte writes correctly`() {
        val maxBytes = 3
        val outputStream = BoundedByteArrayOutputStream(maxBytes)
        
        outputStream.write('A'.code)
        outputStream.write('B'.code)
        outputStream.write('C'.code)
        outputStream.write('D'.code)
        
        assertThat(outputStream.size()).isEqualTo(maxBytes)
        assertThat(outputStream.toString(Charsets.UTF_8)).isEqualTo("ABC")
    }

    @Test
    fun `captureStandardStreams should intercept and restore System out and err`() {
        val originalOut = System.out
        val originalErr = System.err
        
        val (result, stdout, stderr) = captureStandardStreams {
            System.out.print("hello out")
            System.err.print("hello err")
            "return value"
        }
        
        assertThat(result).isEqualTo("return value")
        assertThat(stdout).isEqualTo("hello out")
        assertThat(stderr).isEqualTo("hello err")
        
        assertThat(System.out).isSameAs(originalOut)
        assertThat(System.err).isSameAs(originalErr)
    }

    @Test
    fun `captureStandardStreams should restore streams even if an exception occurs`() {
        val originalOut = System.out
        val originalErr = System.err
        
        val exception = RuntimeException("oops")
        
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            captureStandardStreams {
                System.out.print("some output")
                throw exception
            }
        }
        
        assertThat(thrown).isSameAs(exception)
        assertThat(System.out).isSameAs(originalOut)
        assertThat(System.err).isSameAs(originalErr)
    }

    @Test
    fun `captureStandardStreams should isolate concurrent captures`() {
        val originalOut = System.out
        val originalErr = System.err
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit(Callable {
                captureStandardStreams {
                    System.out.print("first out")
                    System.err.print("first err")
                    "first result"
                }
            })
            val second = executor.submit(Callable {
                captureStandardStreams {
                    System.out.print("second out")
                    System.err.print("second err")
                    "second result"
                }
            })

            assertThat(listOf(first.get(), second.get())).containsExactlyInAnyOrder(
                Triple("first result", "first out", "first err"),
                Triple("second result", "second out", "second err")
            )
            assertThat(System.out).isSameAs(originalOut)
            assertThat(System.err).isSameAs(originalErr)
        } finally {
            executor.shutdownNow()
        }
    }
}
