package io.specmatic.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.*
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.ssl.SSLContextBuilder

const val BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST = 1

class ApacheHttpClientFactory(override val timeoutPolicy: TimeoutPolicy): HttpClientFactory {
    constructor(timeoutInMilliseconds: Long): this(TimeoutPolicy(timeoutInMilliseconds))

    override fun create(): HttpClient = HttpClient(Apache) {
        expectSuccess = false

        followRedirects = false

        engine {
            customizeClient {
                setSSLContext(
                    SSLContextBuilder.create()
                        .loadTrustMaterial(TrustAllStrategy())
                        .build()
                )
                setSSLHostnameVerifier(NoopHostnameVerifier())
                useSystemProperties()
            }
        }

        install(HttpTimeout) {
            timeoutPolicy.configure(this)
        }
    }
}
