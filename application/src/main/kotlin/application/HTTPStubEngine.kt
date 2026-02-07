package application

import io.specmatic.core.Feature
import io.specmatic.core.KeyData
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.log.consoleLog
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.HttpStub
import io.specmatic.stub.RequestHandler
import io.specmatic.stub.SpecmaticConfigSource
import io.specmatic.stub.contractInfoToHttpExpectations
import io.specmatic.stub.listener.MockEventListener

class HTTPStubEngine {
    fun runHTTPStub(
        stubs: List<Pair<Feature, List<ScenarioStub>>>,
        host: String,
        port: Int,
        keyData: KeyData?,
        strictMode: Boolean,
        passThroughTargetBase: String = "",
        specmaticConfigSource: SpecmaticConfigSource,
        httpClientFactory: HttpClientFactory,
        workingDirectory: WorkingDirectory,
        gracefulRestartTimeoutInMs: Long,
        specToBaseUrlMap: Map<String, String?>,
        listeners: List<MockEventListener> = emptyList(),
        requestHandlers: List<RequestHandler> = emptyList()
    ): HttpStub {
        return HttpStub(
            features = stubs.map { it.first },
            rawHttpStubs = contractInfoToHttpExpectations(stubs),
            host = host,
            port = port,
            log = ::consoleLog,
            strictMode = strictMode,
            keyData = keyData,
            passThroughTargetBase = passThroughTargetBase,
            httpClientFactory = httpClientFactory,
            workingDirectory = workingDirectory,
            specmaticConfigSource = specmaticConfigSource,
            timeoutMillis = gracefulRestartTimeoutInMs,
            specToStubBaseUrlMap = specToBaseUrlMap,
            listeners = listeners,
            requestHandlers = requestHandlers.toMutableList()
        ).also {
            it.printStartupMessage()
        }
    }
}
