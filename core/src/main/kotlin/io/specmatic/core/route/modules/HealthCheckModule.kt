package io.specmatic.core.route.modules

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.utilities.jsonObjectMapper
import io.specmatic.stub.respondToKtorHttpResponse

class HealthCheckModule {
    companion object {
        private const val HEALTH_ENDPOINT = "/actuator/health"

        fun Application.configureHealthCheckModule() {
            routing {
                get(HEALTH_ENDPOINT) {
                    val healthStatus = mapOf("status" to "UP")
                    respondToKtorHttpResponse(
                        call,
                        HttpResponse(
                            status = 200,
                            body = jsonObjectMapper.writeValueAsString(healthStatus),
                            headers = mapOf("Content-Type" to "application/json")
                        )
                    )
                }
            }
        }

        fun HttpRequest.isHealthCheckRequest(): Boolean {
            return (this.path == HEALTH_ENDPOINT) && (this.method == "GET")
        }
    }
}