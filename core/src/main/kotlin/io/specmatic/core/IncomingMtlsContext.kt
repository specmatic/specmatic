package io.specmatic.core

import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.specmatic.core.pattern.ContractException
import java.security.PrivateKey
import java.security.cert.X509Certificate

internal fun incomingMtlsContextBuilder(keyData: KeyData): SslContextBuilder {
    val alias = keyData.keyAlias
    val privateKey = keyData.keyStore.getKey(alias, keyData.keyPassword.toCharArray()) as? PrivateKey
        ?: throw ContractException("Incoming mTLS requires a private key for alias '$alias'")
    val certificateChain = keyData.keyStore.getCertificateChain(alias)
        ?.map { it as X509Certificate }
        ?: throw ContractException("Incoming mTLS requires a certificate chain for alias '$alias'")

    return SslContextBuilder.forServer(privateKey, *certificateChain.toTypedArray())
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .clientAuth(ClientAuth.REQUIRE)
}
