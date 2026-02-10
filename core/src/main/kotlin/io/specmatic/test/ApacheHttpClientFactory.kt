package io.specmatic.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.*
import io.ktor.client.plugins.*
import org.apache.hc.client5.http.config.TlsConfig
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.client5.http.ssl.TrustAllStrategy
import org.apache.hc.core5.http2.HttpVersionPolicy
import org.apache.hc.core5.ssl.SSLContextBuilder

const val BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST = 1

class ApacheHttpClientFactory(override val timeoutPolicy: TimeoutPolicy): HttpClientFactory {
    constructor(timeoutInMilliseconds: Long): this(TimeoutPolicy(timeoutInMilliseconds))

    override fun create(): HttpClient {
        val permissiveSslContext = SSLContextBuilder.create()
            .loadTrustMaterial(TrustAllStrategy.INSTANCE)
            .build()

        return HttpClient(Apache5) {
            expectSuccess = false

            followRedirects = false

            engine {
                sslContext = permissiveSslContext

                customizeClient {
                    setConnectionManager(
                        PoolingAsyncClientConnectionManagerBuilder.create()
                            .setDefaultTlsConfig(
                                TlsConfig.custom()
                                    .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                                    .build()
                            )
                            .setTlsStrategy(
                                ClientTlsStrategyBuilder.create()
                                    .setSslContext(permissiveSslContext)
                                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                    .useSystemProperties()
                                    .build()
                            )
                            .build()
                    )
                    useSystemProperties()
                }
            }

            install(HttpTimeout) {
                timeoutPolicy.configure(this)
            }
        }
    }
}
