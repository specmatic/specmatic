package io.specmatic.test.handlers

import io.specmatic.core.Feature
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.plus

class ResponseHandlerRegistry(feature: Feature, scenario: Scenario) {
    private val handlers: List<ResponseHandler> = ServiceLoadedHandlers.handlers.plus(listOf(
        AcceptedResponseHandler(feature, scenario),
        TooManyRequestsHandler(feature, scenario),
    ))

    fun getHandlerFor(httpResponse: HttpResponse, scenario: Scenario): ResponseHandler? {
        return handlers.firstOrNull { it.canHandle(httpResponse, scenario) }
    }

    companion object {
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
