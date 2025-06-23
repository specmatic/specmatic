package application

import io.specmatic.core.Feature
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.log.consoleLog
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.HttpStub
import io.specmatic.stub.contractInfoToHttpExpectations
import io.specmatic.stub.listener.MockEventListener

class HTTPStubEngine {
    fun runHTTPStub(
        stubs: List<Pair<Feature, List<ScenarioStub>>>,
        host: String,
        port: Int,
        certInfo: CertInfo,
        strictMode: Boolean,
        passThroughTargetBase: String = "",
        specmaticConfigPath: String? = null,
        httpClientFactory: HttpClientFactory,
        workingDirectory: WorkingDirectory,
        gracefulRestartTimeoutInMs: Long,
        specToBaseUrlMap: Map<String, String?>,
        listeners: List<MockEventListener> = emptyList()
    ): HttpStub {
        val keyData = certInfo.getHttpsCert()

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
            specmaticConfigPath = specmaticConfigPath,
            timeoutMillis = gracefulRestartTimeoutInMs,
            specToStubBaseUrlMap = specToBaseUrlMap,
            listeners = listeners
        ).also {
            it.printStartupMessage()
        }
    }
}
