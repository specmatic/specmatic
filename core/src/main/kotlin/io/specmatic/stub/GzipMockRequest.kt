package io.specmatic.stub

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.application.PipelineCall
import io.ktor.server.request.PipelineRequest
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.appendFiltered
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import io.specmatic.core.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import java.io.FilterInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal suspend fun mockHttpRequest(call: PipelineCall): HttpRequest {
    val contentCodings = call.request.headers.getAll(HttpHeaders.ContentEncoding)
    if (contentCodings?.singleOrNull()?.trim()?.equals("gzip", ignoreCase = true) != true) {
        return ktorHttpRequestToHttpRequest(call)
    }

    return try {
        coroutineScope {
            var decodedBytes = 0L
            val decoded = writer(Dispatchers.IO) {
                val source = call.request.receiveChannel()
                val compressed = object : FilterInputStream(source.toInputStream(coroutineContext[Job])) {
                    // GZIPInputStream checks availability between members. A temporarily empty
                    // network channel still needs to be read through to the end of the request.
                    override fun available(): Int = if (source.isClosedForRead) 0 else maxOf(1, super.available())
                }
                compressed.use {
                    GZIPInputStream(compressed).use { gzip ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val size = gzip.read(buffer)
                            if (size < 0) break
                            decodedBytes += size
                            channel.writeFully(buffer, 0, size)
                        }
                    }
                }
            }.channel

            try {
                val request = ktorHttpRequestToHttpRequest(DecodedGzipCall(call, decoded))
                // Multipart parsing can finish at its closing boundary before the gzip stream ends.
                decoded.discard()
                decoded.closedCause?.let { throw it }
                if (HttpHeaders.ContentLength in call.request.headers) {
                    request.updateHeader(HttpHeaders.ContentLength, decodedBytes.toString())
                } else {
                    request
                }
            } finally {
                decoded.cancel(null)
            }
        }
    } catch (e: IOException) {
        throw CouldNotParseRequest(e)
    }
}

internal class DecodedGzipCall(call: PipelineCall, decoded: ByteReadChannel) : PipelineCall by call {
    // Delegated receives would read the original compressed request in Ktor 3.
    override suspend fun <T> receive(typeInfo: TypeInfo): T = super<PipelineCall>.receive(typeInfo)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override suspend fun <T> receiveNullable(typeInfo: TypeInfo): T? = super<PipelineCall>.receiveNullable(typeInfo)

    override val request: PipelineRequest = object : PipelineRequest by call.request {
        override val call: PipelineCall get() = this@DecodedGzipCall

        override val headers: Headers = Headers.build {
            appendFiltered(call.request.headers) { name, _ ->
                !name.equals(HttpHeaders.ContentEncoding, ignoreCase = true) &&
                    !name.equals(HttpHeaders.ContentLength, ignoreCase = true)
            }
        }

        override fun receiveChannel(): ByteReadChannel = decoded
    }
}
