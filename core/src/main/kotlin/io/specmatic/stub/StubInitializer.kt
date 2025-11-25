package io.specmatic.stub

import io.specmatic.core.SpecmaticConfig

interface StubInitializer {
    fun initialize(specmaticConfig: SpecmaticConfig, httpStub: HttpStub)
}