package io.specmatic.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.*
import io.specmatic.core.KeyData
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.ssl.SSLContextBuilder

const val BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST = 1

class ApacheHttpClientFactory(
    override val timeoutPolicy: TimeoutPolicy,
    private val keyData: KeyData? = null
): HttpClientFactory {
    constructor(timeoutInMilliseconds: Long, keyData: KeyData? = null): this(TimeoutPolicy(timeoutInMilliseconds), keyData)

    override fun create(): HttpClient = HttpClient(Apache) {
        expectSuccess = false

        followRedirects = false

        engine {
            customizeClient {
                val sslContextBuilder = SSLContextBuilder.create().loadTrustMaterial(TrustAllStrategy())
                if (keyData != null) {
                    sslContextBuilder.loadKeyMaterial(keyData.keyStore, keyData.keyPassword.toCharArray()) { aliases, _ ->
                        val configuredAlias = keyData.keyAlias.takeIf { it.isNotBlank() }
                        configuredAlias?.takeIf(aliases::containsKey) ?: aliases.keys.firstOrNull()
                    }
                }

                setSSLContext(
                    sslContextBuilder.build()
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
