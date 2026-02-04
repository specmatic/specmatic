package io.specmatic.stub

import io.ktor.server.application.*
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
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

    @Test
    fun `should be delayed when delay is provided in arguments`() = runBlocking {
        val delayInMillis = 500L

        val timeTaken = measureTimeMillis {
            respondToKtorHttpResponse(
                call = applicationCall,
                httpResponse = httpResponse,
                delayInMilliSeconds = delayInMillis,
                specmaticConfig = SpecmaticConfig.default()
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
                specmaticConfig = SpecmaticConfig.default()
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
                specmaticConfig = SpecmaticConfig.default()
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
                specmaticConfig = SpecmaticConfig.default()
            )
        }

        assertTrue(
            timeTaken <= maxDelayInMs,
            "Expected minimum delay within $maxDelayInMs but actual delay was $timeTaken ms"
        )
    }
}
