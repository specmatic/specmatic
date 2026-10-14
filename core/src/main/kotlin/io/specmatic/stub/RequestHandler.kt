package io.specmatic.stub

import io.specmatic.core.HttpRequest

interface RequestHandler {
    val name: String
    fun handleRequest(httpRequest: HttpRequest, baseUrl: String): HttpStubResponse?
}
