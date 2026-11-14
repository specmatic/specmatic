package io.specmatic.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.HttpTimeoutConfig

private const val ONE_SECOND_IN_MILLISECONDS = 1000L

class TimeoutPolicyTest {

    @Test
    fun `timeout policy should set the socket timeout to longer than the request timeout to let the request timeout kick in first` () {

        val timeoutPolicy = TimeoutPolicy(ONE_SECOND_IN_MILLISECONDS)

        assertThat(timeoutPolicy.requestTimeoutInMillis).isEqualTo(ONE_SECOND_IN_MILLISECONDS)
        assertThat(timeoutPolicy.socketTimeoutInMillis).isEqualTo(ONE_SECOND_IN_MILLISECONDS + BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST)
    }

    @Test
    fun `timeout policy should set the socket and request timeouts in the TimeoutPolicy ktor plugin as configured` () {
        val timeoutPolicy = TimeoutPolicy(ONE_SECOND_IN_MILLISECONDS)

        val pluginTimeoutConfiguration = HttpTimeoutConfig()

        timeoutPolicy.configure(pluginTimeoutConfiguration)

        assertThat(pluginTimeoutConfiguration.requestTimeoutMillis).isEqualTo(ONE_SECOND_IN_MILLISECONDS)
        assertThat(pluginTimeoutConfiguration.socketTimeoutMillis).isEqualTo(ONE_SECOND_IN_MILLISECONDS + BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST)
    }
}