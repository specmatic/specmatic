package io.specmatic.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.*
import io.specmatic.core.KeyData
import org.apache.http.HttpResponseInterceptor
import org.apache.http.conn.ManagedHttpClientConnection
import org.apache.http.nio.conn.ManagedNHttpClientConnection
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.protocol.HttpCoreContext
import org.apache.http.ssl.SSLContextBuilder

const val BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST = 1

data class TransportInfo(val mtlsNegotiated: Boolean = false)

class ApacheHttpClientFactory(
    override val timeoutPolicy: TimeoutPolicy,
    private val keyData: KeyData? = null,
    private val onTransportInfo: (TransportInfo) -> Unit = {}
): HttpClientFactory {
    constructor(timeoutInMilliseconds: Long, keyData: KeyData? = null): this(TimeoutPolicy(timeoutInMilliseconds), keyData, {})
    constructor(
        timeoutInMilliseconds: Long,
        keyData: KeyData? = null,
        onTransportInfo: (TransportInfo) -> Unit
    ): this(TimeoutPolicy(timeoutInMilliseconds), keyData, onTransportInfo)

    override fun create(): HttpClient = HttpClient(Apache) {
        expectSuccess = false

        followRedirects = false

        engine {
            customizeClient {
                addInterceptorLast(HttpResponseInterceptor { response, context ->
                    val coreContext = HttpCoreContext.adapt(context)
                    val connection = coreContext.connection
                    val sslSession = when (connection) {
                        is ManagedNHttpClientConnection -> connection.sslSession
                        is ManagedHttpClientConnection -> connection.sslSession
                        else -> null
                    }
                    val clientCertPresented = sslSession?.localCertificates?.isNotEmpty() == true
                    onTransportInfo(TransportInfo(mtlsNegotiated = clientCertPresented))
                })

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
