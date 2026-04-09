package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import java.util.*

interface HttpStubMatcher {
    fun matches(httpRequest: HttpRequest): Result
    fun utilize(): Boolean
}

fun interface HttpStubMatcherFactory {
    fun create(httpStubData: HttpStubData): HttpStubMatcher

    companion object {
        fun load(): HttpStubMatcherFactory? {
            return ServiceLoader.load(HttpStubMatcherFactory::class.java).firstOrNull()
        }
    }
}

