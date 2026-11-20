package io.specmatic.test.interceptor

import io.specmatic.core.Feature
import java.util.ServiceLoader

interface ContractTestInterceptorProvider {
    fun interceptorsFor(feature: Feature): List<ContractTestInterceptor>

    companion object {
        private val serviceLoadedProviders by lazy {
            ServiceLoader.load(ContractTestInterceptorProvider::class.java).toList()
        }

        fun interceptorsFor(feature: Feature): List<ContractTestInterceptor> {
            return serviceLoadedProviders.flatMap { provider -> provider.interceptorsFor(feature) }
        }
    }
}
