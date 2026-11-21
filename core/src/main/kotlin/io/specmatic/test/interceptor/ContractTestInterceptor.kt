package io.specmatic.test.interceptor

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.ReturnValue
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

sealed interface InterceptResult<out T> {
    data object PassThrough : InterceptResult<Nothing>
    data class Processed<T>(val value: ReturnValue<T>) : InterceptResult<T>
}

interface ContractTestInterceptor {
    fun updateRequest(
        feature: Feature,
        testScenario: Scenario,
        originalScenario: Scenario,
        httpRequest: HttpRequest,
        substitution: Substitution,
    ): InterceptResult<HttpRequest>

    fun updateSubstitution(
        feature: Feature,
        testScenario: Scenario,
        originalScenario: Scenario,
        httpRequest: HttpRequest,
        httpResponse: HttpResponse,
        substitution: Substitution,
    ): InterceptResult<Substitution>

    companion object {
        private val prioritizedInterceptors = CopyOnWriteArrayList<ContractTestInterceptor>()

        fun load(additionalInterceptors: List<ContractTestInterceptor> = emptyList()): List<ContractTestInterceptor> {
            val serviceLoadedInterceptors = ServiceLoader.load(ContractTestInterceptor::class.java).toList()
            return prioritizedInterceptors.toList().plus(serviceLoadedInterceptors).plus(additionalInterceptors)
        }

        internal fun <T> withInterceptor(interceptor: ContractTestInterceptor, block: () -> T): T {
            val registration = register(interceptor)
            return try {
                block()
            } finally {
                registration.close()
            }
        }

        private fun register(interceptor: ContractTestInterceptor): AutoCloseable {
            prioritizedInterceptors.add(0, interceptor)
            return AutoCloseable {
                prioritizedInterceptors.removeIf { it === interceptor }
            }
        }
    }
}
