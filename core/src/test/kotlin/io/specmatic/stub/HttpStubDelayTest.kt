package io.specmatic.stub

import io.ktor.server.application.*
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForEntity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class HttpStubDelayTest {

    companion object {
        private lateinit var applicationCall: ApplicationCall
        private lateinit var httpResponse: HttpResponse

        @JvmStatic
        @BeforeAll
        fun setUp() {
            applicationCall = mockk<ApplicationCall>(relaxed = true)
            httpResponse = mockk<HttpResponse>(relaxed = true)
            every { httpResponse.headers } returns mapOf()
            every { httpResponse.body.toStringLiteral() } returns "response body"
            every { httpResponse.body.httpContentType } returns "text/plain"
            every { httpResponse.status } returns 200
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            clearMocks(applicationCall, httpResponse)
        }
    }

    @AfterEach
    fun resetResponseBodyMock() {
        every { httpResponse.body.toStringLiteral() } returns "response body"
    }

    @Test
    fun `should be delayed when delay is provided in arguments`() = runBlocking {
        val delayInMillis = 500L

        val timeTaken = measureTimeMillis {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                delayInMilliSeconds = delayInMillis,
                specmaticConfig = SpecmaticConfig()
            )
        }

        assertTrue(
            timeTaken >= delayInMillis,
            "Expected delay of at least $delayInMillis ms but actual delay was $timeTaken ms"
        )
    }

    @Test
    fun `delay in argument should be prioritized over system property`() = runBlocking {
        System.setProperty(SPECMATIC_STUB_DELAY, "0")
        val delayInMillis = 500L

        val timeTaken = measureTimeMillis {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                delayInMilliSeconds = delayInMillis,
                specmaticConfig = SpecmaticConfig()
            )
        }

        try {
            assertTrue(
                timeTaken >= delayInMillis,
                "Expected delay of at least $delayInMillis ms but actual delay was $timeTaken ms"
            )
        } finally {
            System.clearProperty(SPECMATIC_STUB_DELAY)
        }
    }

    @Test
    fun `delay in system or config property should be used when no delay in argument`() = runBlocking {
        val delayInMs = 500L
        System.setProperty(SPECMATIC_STUB_DELAY, delayInMs.toString())

        val timeTaken = measureTimeMillis {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                specmaticConfig = SpecmaticConfig()
            )
        }

        try {
            assertTrue(
                timeTaken >= delayInMs,
                "Expected delay of at least $delayInMs ms but actual delay was $timeTaken ms"
            )
        } finally {
            System.clearProperty(SPECMATIC_STUB_DELAY)
        }
    }

    @Test
    fun `should be no delay when no delay argument or system or config property`() = runBlocking {
        val maxDelayInMs = 1000L
        val timeTaken = measureTimeMillis {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                specmaticConfig = SpecmaticConfig()
            )
        }

        assertTrue(
            timeTaken <= maxDelayInMs,
            "Expected minimum delay within $maxDelayInMs but actual delay was $timeTaken ms"
        )
    }

    @Test
    fun `should not throw uncaught exceptions when cancelled during delayed response`() = runBlocking {
        val job = launch {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                delayInMilliSeconds = 5_000L,
                specmaticConfig = SpecmaticConfig()
            )
        }

        delay(50L)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled || job.isCompleted)
    }

    @Test
    fun `closing http stub with many in-flight delayed openapi responses should not crash`(@TempDir tempDir: File) {
        val openApiSpec = tempDir.resolve("http-stub-delay.yaml").apply {
            writeText("""
            openapi: 3.0.1
            info:
              title: Delay Test
              version: "1.0"
            paths:
              /data:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        text/plain:
                          schema:
                            type: string
                            example: ok
            """.trimIndent())
        }

        val uncaughtExceptionSeen = CountDownLatch(1)
        val uncaughtExceptions = CopyOnWriteArrayList<Throwable>()
        val previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            uncaughtExceptions.add(throwable)
            uncaughtExceptionSeen.countDown()
        }

        try {
            System.setProperty(SPECMATIC_STUB_DELAY, "1500")
            val feature = parseContractFileToFeature(openApiSpec.absolutePath)
            val executor = Executors.newFixedThreadPool(4)

            try {
                HttpStub(features = listOf(feature), timeoutMillis = 100).use { stub ->
                    repeat(4) {
                        executor.submit {
                            try {
                                RestTemplate().getForEntity<String>(stub.endPoint + "/data")
                            } catch (_: Throwable) {
                                // expected client-side failure
                            }
                        }
                    }
                    Thread.sleep(250)
                }
            } finally {
                executor.shutdown()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }

            assertFalse(
                uncaughtExceptionSeen.await(2, TimeUnit.SECONDS),
                "Expected no uncaught exception during stop-vs-delay race, got: ${uncaughtExceptions.joinToString(separator = "\n") { it.stackTraceToString() }}"
            )
        } finally {
            System.clearProperty(SPECMATIC_STUB_DELAY)
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
        }
    }
}
