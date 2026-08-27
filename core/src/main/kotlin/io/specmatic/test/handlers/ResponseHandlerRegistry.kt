package io.specmatic.test.handlers

import io.specmatic.core.Feature
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.plus

class ResponseHandlerRegistry(feature: Feature, originalScenario: Scenario) {
    private val handlers: List<ResponseHandler> = serviceLoadedHandlers(feature, originalScenario)

    fun getHandlerFor(httpResponse: HttpResponse, scenario: Scenario): ResponseHandler? {
        return handlers.firstOrNull { it.canHandle(httpResponse, scenario) }
    }

    companion object {
        private fun serviceLoadedHandlers(feature: Feature, originalScenario: Scenario): List<ResponseHandler> {
            val providedHandlers = ServiceLoader.load(ResponseHandlerProvider::class.java).flatMap { provider ->
                provider.handlersFor(feature, originalScenario)
            }

            return providedHandlers.toList() + ServiceLoadedHandlers.handlers
        }

        private object ServiceLoadedHandlers {
            val handlers = CopyOnWriteArrayList<ResponseHandler>()

            init {
                ServiceLoader.load(ResponseHandler::class.java).forEach {
                    handlers.add(it)
                }
            }
        }
    }
}
