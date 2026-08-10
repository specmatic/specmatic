package io.specmatic.test.utils

import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import io.specmatic.core.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class DelayStrategyTest {
    private val strategy = DelayStrategy.RespectRetryAfter()

    @Test
    fun `should calculate delay from standards compliant HTTP date`() {
        val retryAfter = GMTDate(Instant.now().plusSeconds(10).toEpochMilli()).toHttpDate()
        val targetTime = retryAfter.fromHttpToGmtDate().timestamp
        val earliestExpectedDelay = targetTime - Instant.now().toEpochMilli()

        val delay = strategy.getInitialDelay(contextFor(retryAfter))

        assertThat(delay).isBetween(earliestExpectedDelay - 1.seconds.inWholeMilliseconds, earliestExpectedDelay)
    }

    @Test
    fun `should continue calculating delay from ISO 8601 date`() {
        val retryAfter = Instant.now().plusSeconds(10).toString()

        val delay = strategy.getInitialDelay(contextFor(retryAfter))

        assertThat(delay).isBetween(9.seconds.inWholeMilliseconds, 10.seconds.inWholeMilliseconds)
    }

    @Test
    fun `should use fallback delay for negative delay seconds`() {
        val delay = strategy.calculateDelay(contextFor("-1"))

        assertThat(delay).isEqualTo(1.seconds.inWholeMilliseconds)
    }

    private fun contextFor(retryAfter: String) = DelayStrategyContext(
        value = HttpResponse(headers = mapOf(HttpHeaders.RetryAfter to retryAfter)),
        attempt = 0,
    )
}
