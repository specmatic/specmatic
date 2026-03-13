package io.specmatic.core.wsdl

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import java.io.File

internal data class WsdlExampleFixture(
    val feature: Feature,
    val scenarioStubs: List<ScenarioStub>,
) {
    val scenarioStub: ScenarioStub
        get() = scenarioStubs.single()
}

internal fun loadWsdlExampleFixture(wsdlPath: String, examplesPath: String): WsdlExampleFixture {
    val wsdlFile = File(wsdlPath)
    val examplesDir = File(examplesPath)
    val feature = parseContractFileToFeature(wsdlFile, exampleDirPaths = listOf(examplesDir.path)).loadExternalisedExamples()
    val scenarioStubs = examplesDir.listFiles().orEmpty().sortedBy(File::getName).map(ScenarioStub::readFromFile)

    return WsdlExampleFixture(feature, scenarioStubs)
}

internal fun assertHttpRequestMatches(actual: HttpRequest, expected: HttpRequest) {
    assertThat(actual.method).isEqualTo(expected.method)
    assertThat(actual.path).isEqualTo(expected.path)
    assertThat(actual.headers["SOAPAction"]).isEqualTo(expected.headers["SOAPAction"])
    assertThat(actual.body).isEqualTo(expected.body)
}
