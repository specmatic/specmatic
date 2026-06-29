package io.specmatic.test.interceptor

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.mock.ScenarioStub
import java.util.ServiceLoader

sealed interface InterceptResult<out T> {
    data object PassThrough : InterceptResult<Nothing>
    data class Processed<T>(val value: ReturnValue<T>) : InterceptResult<T>
}

interface ContractTestInterceptor {
    fun updateRequest(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpRequest: HttpRequest,
        substitution: Substitution,
    ): InterceptResult<HttpRequest>

    fun updateSubstitution(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpResponse: HttpResponse,
        substitution: Substitution,
    ): InterceptResult<Substitution>

    companion object {
        private val loaderOverride = ThreadLocal<(() -> ContractTestInterceptor?)?>()

        fun load(): ContractTestInterceptor? {
            return loaderOverride.get()?.invoke()
                ?: ServiceLoader.load(ContractTestInterceptor::class.java).firstNotNullOfOrNull { it }
        }

        internal fun <T> withLoaderForTest(loader: () -> ContractTestInterceptor?, block: () -> T): T {
            loaderOverride.set(loader)
            return try {
                block()
            } finally {
                loaderOverride.remove()
            }
        }
    }
}
