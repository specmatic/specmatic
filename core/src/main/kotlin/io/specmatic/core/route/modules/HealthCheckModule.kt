package io.specmatic.core.route.modules

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.stub.respondToKtorHttpResponse

class HealthCheckModule {
    companion object {
        private const val DEPRECATED_HEALTH_ENDPOINT = "/actuator/health"
        private const val HEALTH_ENDPOINT = "/_specmatic/health"
        private const val ROOT_ENDPOINT = "/"
        private const val SPECMATIC_STATUS_HEADER = "X-Specmatic-Status"
        private const val SPECMATIC_STATUS_UP = "up"

        fun Application.configureHealthCheckModule() {
            routing {
                val healthCheckHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                    val healthStatus = mapOf("status" to "UP")
                    respondToKtorHttpResponse(
                        call,
                        HttpResponse(
                            status = 200,
                            body = ObjectMapper().writeValueAsString(healthStatus),
                            headers = mapOf("Content-Type" to "application/json")
                        )
                    )
                }

                get(DEPRECATED_HEALTH_ENDPOINT, healthCheckHandler)
                get(HEALTH_ENDPOINT, healthCheckHandler)

                head(ROOT_ENDPOINT) {
                    respondToKtorHttpResponse(
                        call,
                        HttpResponse(
                            status = 200,
                            headers = mapOf(SPECMATIC_STATUS_HEADER to SPECMATIC_STATUS_UP)
                        )
                    )
                }
            }
        }

        fun HttpRequest.isHealthCheckRequest(): Boolean {
            return ((this.path == DEPRECATED_HEALTH_ENDPOINT || this.path == HEALTH_ENDPOINT) && (this.method == "GET")) ||
                    ((this.path == ROOT_ENDPOINT) && (this.method == "HEAD"))
        }
    }
}
