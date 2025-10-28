package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.logger
import io.specmatic.test.utils.DelayStrategy
import io.specmatic.test.utils.RetryHandler
import io.specmatic.test.utils.RetryResult

sealed interface MonitorResult {
    data class Success(val response: HttpResponse) : MonitorResult
    data class Failure(val failure: Result.Failure, val response: HttpResponse? = null) : MonitorResult
}

sealed interface MonitorRequestGeneratorStrategy {
    val identifier: String

    fun generate(): HttpRequest

    data class MonitorLink(val monitorScenario: Scenario, val link: ResponseMonitor.Link) : MonitorRequestGeneratorStrategy {
        private val request: HttpRequest by lazy { monitorScenario.generateHttpRequest().updatePath(link.toPath()) }
        override val identifier: String by lazy { "${request.method.orEmpty()} ${link.toPath()}" }

        override fun generate(): HttpRequest = request
    }

    data class ReuseRequest(val request: HttpRequest) : MonitorRequestGeneratorStrategy {
        override val identifier: String = "${request.method.orEmpty()} ${request.path.orEmpty()}"

        override fun generate(): HttpRequest = request
    }
}

class ResponseMonitor(
    private val requestGeneratorStrategy: MonitorRequestGeneratorStrategy,
    private val onResponse: (HttpResponse) -> RetryResult<MonitorResult, HttpResponse>,
    private val retryHandler: RetryHandler<MonitorResult, HttpResponse> = RetryHandler(DelayStrategy.RespectRetryAfter(1000)),
    private val initialDelayContext: HttpResponse? = null,
) {
    fun waitForResponse(executor: TestExecutor): MonitorResult {
        return retryHandler.run(onExhausted = ::onAttemptsExhaustion, initialDelayContext = initialDelayContext) { retryCount ->
            val request = requestGeneratorStrategy.generate()
            logger.log("Retrying Link (attempt $retryCount): ${request.path} ...")
            onResponse(executor.execute(request))
        }
    }

    private fun onAttemptsExhaustion(attempts: Int): MonitorResult {
        return MonitorResult.Failure(
            failure = Result.Failure(message = "Max retries of $attempts exceeded with ${requestGeneratorStrategy.identifier}"),
        )
    }

    data class Link(val url: String, val rel: String? = null, val title: String? = null) {
        fun toPath(): String = url
    }
}
