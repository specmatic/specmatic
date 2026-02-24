package io.specmatic.core.wsdl

import io.specmatic.Utils.readTextResource
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.*
import io.specmatic.core.utilities.contractStubPaths
import io.specmatic.core.utilities.contractTestPathsFrom
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.FeatureStubsResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.loadContractStubsFromFilesAsResults
import io.specmatic.test.TestExecutor
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLTest {
    @Test
    fun `conversion with bare types`() {
        val (wsdlContent, expectedGherkin) = readContracts("stockquote")

        val wsdl = WSDL(toXMLNode(wsdlContent), "")
        val gherkinFromWSDL: String = wsdl.convertToGherkin().trim()
        val featureFromWSDL = parseGherkinStringToFeature(gherkinFromWSDL)

        val featureFromExpectedGherkin = parseGherkinStringToFeature(expectedGherkin)

        assertThat(featureFromWSDL).isEqualTo(featureFromExpectedGherkin)
    }

    @Test
    fun `conversion with simple type bodies`() {
        val (wsdlContent, expectedGherkin) = readContracts("hello")

        val wsdl = WSDL(toXMLNode(wsdlContent), "")
        val generatedGherkin: String = wsdl.convertToGherkin().trim()

        assertThat(parseGherkinStringToFeature(generatedGherkin)).isEqualTo(parseGherkinStringToFeature(expectedGherkin))
    }

    @Test
    fun `when a WSDL containing references is run as stub and then as contract tests against itself the tests should pass`() {
        val wsdlFile = File("src/test/resources/wsdl/order_api.wsdl")
        val feature = wsdlContentToFeature(checkExists(wsdlFile).readText(), wsdlFile.canonicalPath)

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return stub.client.execute(request)
                }
            })
        }

        println(result.report())

        assertThat(result.report()).doesNotContain("Expected xml, got string")
        assertThat(result.report()).doesNotContain("Didn't get enough values")
        assertThat(result.success()).isTrue()
        assertThat(result.successCount).isGreaterThan(0)
    }

    @Test
    fun `when a WSDL with an example is run as a test against a stub of itself the tests should pass`() {
        val wsdlFile = File("src/test/resources/wsdl/with_examples/order_api.wsdl")
        val examplesFolder = wsdlFile.resolveSibling("order_api_examples")

        val feature = wsdlContentToFeature(checkExists(wsdlFile).readText(), wsdlFile.canonicalPath).loadExternalisedExamples()
        val scenarioStubs = examplesFolder.listFiles()?.map(ScenarioStub::readFromFile).orEmpty()
        assertDoesNotThrow { feature.validateExamplesOrException() }

        val result = HttpStub(feature, scenarioStubs).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val response = stub.client.execute(request)
                    assertThat(response.headers["X-Specmatic-Type"]).isNotEqualTo("random")
                    return response
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `wsdl loop should load v3 configured non underscore examples for sut and mock and traffic should contain example values`(@TempDir tempDir: File) {
        val wsdlSource = File("src/test/resources/wsdl/with_examples/order_api.wsdl")
        val wsdlFile = tempDir.resolve("order_api.wsdl").apply { writeText(wsdlSource.readText()) }

        val exampleSource = File("src/test/resources/wsdl/with_examples/order_api_examples/create_product.json")
        val sutExamplesDir = tempDir.resolve("sut-example-data").apply { mkdirs() }
        val mockExamplesDir = tempDir.resolve("mock-example-data").apply { mkdirs() }
        exampleSource.copyTo(sutExamplesDir.resolve("create_product.json"))
        exampleSource.copyTo(mockExamplesDir.resolve("create_product.json"))

        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${tempDir.canonicalPath}
                      specs:
                        - ${wsdlFile.name}
                data:
                  examples:
                    - directories:
                        - ${sutExamplesDir.canonicalPath}
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ${tempDir.canonicalPath}
                          specs:
                            - ${wsdlFile.name}
                    data:
                      examples:
                        - directories:
                            - ${mockExamplesDir.canonicalPath}
            """.trimIndent()
        )

        val config = loadSpecmaticConfig(configFile.canonicalPath)
        val stubContractPathData = contractStubPaths(configFile.canonicalPath)
        val testContractPathData = contractTestPathsFrom(configFile.canonicalPath, ".")

        assertThat(stubContractPathData.single().exampleDirPaths).containsExactly(mockExamplesDir.canonicalPath)
        assertThat(testContractPathData.single().exampleDirPaths).containsExactly(sutExamplesDir.canonicalPath)

        val stubLoadResults = loadContractStubsFromFilesAsResults(
            contractPathDataList = stubContractPathData,
            dataDirPaths = emptyList(),
            specmaticConfig = config,
            withImplicitStubs = false
        )
        val stubConfig = stubLoadResults.filterIsInstance<FeatureStubsResult.Success>().single()

        val testPathData = testContractPathData.single()
        val testFeature = parseContractFileToFeature(
            contractPath = testPathData.path,
            specmaticConfig = config,
            exampleDirPaths = testPathData.exampleDirPaths.orEmpty()
        ).loadExternalisedExamples()

        val requestBodies = mutableListOf<String>()
        val responseBodies = mutableListOf<String>()
        val result = HttpStub(stubConfig.feature, stubConfig.scenarioStubs).use { stub ->
            testFeature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val response = stub.client.execute(request)
                    requestBodies.add(request.body.toStringLiteral())
                    responseBodies.add(response.body.toStringLiteral())
                    return response
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(requestBodies.joinToString("\n"))
            .contains("<name>Phone</name>")
            .contains("<type>Gadget</type>")
        assertThat(responseBodies.joinToString("\n"))
            .contains("<id>123</id>")
    }

    private fun readContracts(filename: String): Pair<String, String> {
        val wsdlContent = readTextResource("wsdl/$filename.wsdl")
        val expectedGherkin = readTextResource("wsdl/$filename.$CONTRACT_EXTENSION").trimIndent().trim()
        return Pair(wsdlContent, expectedGherkin)
    }
}
