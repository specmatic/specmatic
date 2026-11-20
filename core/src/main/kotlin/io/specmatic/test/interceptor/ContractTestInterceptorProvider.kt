package io.specmatic.test.interceptor

import io.specmatic.core.Feature
import io.specmatic.core.Scenario
import java.util.ServiceLoader

data class ContractTestInterceptorContext(
    val feature: Feature,
    val selectedScenarios: List<Scenario>,
    val parallelExecutionEnabled: Boolean = false,
)

interface ContractTestInterceptorProvider {
    fun interceptorsFor(context: ContractTestInterceptorContext): List<ContractTestInterceptor>

    companion object {
        fun interceptorsFor(context: ContractTestInterceptorContext): List<ContractTestInterceptor> {
            return ServiceLoader.load(ContractTestInterceptorProvider::class.java)
                .flatMap { provider -> provider.interceptorsFor(context) }
        }
    }
}
