package io.specmatic.test

import io.ktor.client.plugins.*

open class TimeoutPolicy(timeoutInMilliseconds: Long) {
    val requestTimeoutInMillis: Long = timeoutInMilliseconds
    val socketTimeoutInMillis: Long = timeoutInMilliseconds + BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST

    fun configure(httpTimeoutCapabilityConfiguration: HttpTimeoutConfig) {
        httpTimeoutCapabilityConfiguration.socketTimeoutMillis = socketTimeoutInMillis
        httpTimeoutCapabilityConfiguration.requestTimeoutMillis = requestTimeoutInMillis
    }
}
