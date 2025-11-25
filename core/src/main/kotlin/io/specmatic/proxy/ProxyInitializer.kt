package io.specmatic.proxy

import io.specmatic.core.SpecmaticConfig

interface ProxyInitializer {
    fun initialize(specmaticConfig: SpecmaticConfig, proxy: Proxy)
}