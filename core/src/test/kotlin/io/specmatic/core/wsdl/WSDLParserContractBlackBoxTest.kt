package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.wsdl.payload.emptySoapMessage
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class WSDLParserContractBlackBoxTest {
    @Test
    fun `contract test without examples exercises all scalar choice branches`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        assertThat(feature.scenarios).hasSize(1)

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(2)
        assertThat(seenRequestBodies.map(::scalarRequestBranch)).containsExactlyInAnyOrder("customerNumber", "loginId")
    }

    @Test
    fun `scenario generation for scalar choice only emits valid single-branch payloads`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        val generatedScenarios = feature.scenarios.single()
            .generateTestScenarios(feature.flagsBased)
            .map { it.value }
            .toList()

        assertThat(generatedScenarios).hasSize(2)
        assertThat(generatedScenarios.map { it.generateHttpRequest(feature.flagsBased).body.toStringLiteral() })
            .allSatisfy { body ->
                assertThat(body.contains("CustomerNumber")).isNotEqualTo(body.contains("LoginId"))
            }
    }

    @Test
    fun `contract test without examples exercises all complex choice branches`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        assertThat(feature.scenarios).hasSize(1)

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(2)
        assertThat(seenRequestBodies.map(::complexRequestBranch)).containsExactlyInAnyOrder("customerByPermId", "customerByLogin")
    }

    @Test
    fun `scenario generation for complex choice only emits valid single-branch payloads`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        val generatedScenarios = feature.scenarios.single()
            .generateTestScenarios(feature.flagsBased)
            .map { it.value }
            .toList()

        assertThat(generatedScenarios).hasSize(2)
        assertThat(generatedScenarios.map { it.generateHttpRequest(feature.flagsBased).body.toStringLiteral() })
            .allSatisfy { body ->
                assertThat(body.contains("CustomerByPermId")).isNotEqualTo(body.contains("CustomerByLogin"))
            }
    }

    @Test
    fun `scenario generation for repeating scalar choice only emits valid repeated occurrence payloads`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice_repeating.wsdl"))

        val generatedBodies = feature.scenarios.single()
            .generateTestScenarios(feature.flagsBased)
            .map { it.value.generateHttpRequest(feature.flagsBased).body.toStringLiteral() }
            .toList()

        assertThat(generatedBodies).isNotEmpty()
        assertThat(generatedBodies).allSatisfy { body ->
            val customerCount = countOccurrences(body, "<Choice-scalar-repeating:CustomerNumber>")
            val loginCount = countOccurrences(body, "<Choice-scalar-repeating:LoginId>")
            assertThat(customerCount + loginCount).isBetween(1, 2)
        }
    }

    @Test
    fun `contract test for choice wsdl sends both example soap payload variants`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/choice_ref.wsdl",
            "src/test/resources/wsdl/state_machine/choice_ref_examples",
        )

        assertThat(fixture.feature.scenarios).hasSize(1)
        assertThat(fixture.feature.scenarios.single().examples.flatMap { it.rows }).hasSize(2)

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/choice-ref")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/choice-ref/signonCustId\"", "/choice-ref/signonCustId")
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(2)
        assertThat(seenRequestBodies).hasSize(2)
        assertThat(seenRequestBodies).anySatisfy {
            assertThat(it).contains("SignonCustId").contains("PrimaryName").contains("CustId").contains("CP-123")
            assertThat(it).doesNotContain("CustLoginId>login-123<")
        }
        assertThat(seenRequestBodies).anySatisfy {
            assertThat(it).contains("SignonCustId").contains("PrimaryName").contains("CustLoginId>login-123<")
            assertThat(it).doesNotContain("CustPermId")
        }
    }

    @Test
    fun `contract test with scalar choice example and resiliency off sends only the example selected branch`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/scalar_choice.wsdl",
            "src/test/resources/wsdl/state_machine/scalar_choice_examples",
        )

        assertThat(fixture.feature.scenarios).hasSize(1)
        assertThat(fixture.feature.scenarios.single().examples.flatMap { it.rows }).hasSize(1)

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(fixture.feature).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
        assertThat(seenRequestBodies.single()).contains("LoginId>login-123<")
        assertThat(seenRequestBodies.single()).doesNotContain("CustomerNumber")
    }

    @Test
    fun `contract test with complex choice example and resiliency off sends only the example selected branch`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/complex_choice.wsdl",
            "src/test/resources/wsdl/state_machine/complex_choice_examples",
        )

        assertThat(fixture.feature.scenarios).hasSize(1)
        assertThat(fixture.feature.scenarios.single().examples.flatMap { it.rows }).hasSize(1)

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(fixture.feature).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
        assertThat(seenRequestBodies.single()).contains("CustomerByPermId").contains("PermId>CP-123<")
        assertThat(seenRequestBodies.single()).doesNotContain("CustomerByLogin")
    }

    @Test
    fun `contract test for no input wsdl sends no request body`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/no_input.wsdl"))

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/no-input")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/no-input/ping\"", "/no-input/ping")
                    assertThat(request.body.toStringLiteral()).isEmpty()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for empty message part sends empty soap payload`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/empty_part.wsdl"))

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/empty-part")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/empty-part/ping\"", "/empty-part/ping")
                    assertThat(request.body).isEqualTo(emptySoapMessage())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for element ref wsdl sends the example soap payload`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/element_ref.wsdl",
            "src/test/resources/wsdl/state_machine/element_ref_examples",
        )

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertHttpRequestMatches(request, fixture.scenarioStub.request)
                    assertThat(request.headers["Content-Type"]).isEqualTo(ContentType.Text.Xml.toString())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for type ref wsdl sends the example soap payload`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/type_ref.wsdl",
            "src/test/resources/wsdl/state_machine/type_ref_examples",
        )

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertHttpRequestMatches(request, fixture.scenarioStub.request)
                    assertThat(request.headers["Content-Type"]).isEqualTo(ContentType.Text.Xml.toString())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for soap version_1_2 wsdl uses example request and content-type`() {
        val wsdlSpecPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val examplesPath = "src/test/resources/wsdl/cdata_test_soap12/data_api_examples"
        val fixture = loadWsdlExampleFixture(wsdlSpecPath, examplesPath)

        val result = fixture.feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.body).isEqualTo(fixture.scenarioStub.request.body)
                assertThat(request.headers[CONTENT_TYPE]).startsWith("application/soap+xml")
                return fixture.scenarioStub.response
            }
        })

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for soap version_1_2 wsdl generated request uses content-type`() {
        val wsdlSpecPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val feature = parseContractFileToFeature(File(wsdlSpecPath))
        val generatedResponse = feature.scenarios.single().generateHttpResponse(emptyMap())

        val result = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers[CONTENT_TYPE]).startsWith("application/soap+xml")
                return generatedResponse
            }
        })

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for wsdl header part sends the header xml element inside the soap envelope`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/header_part.wsdl"))

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/header-part")
                    assertThat(request.body.toStringLiteral())
                        .contains("soapenv:Header")
                        .contains("<ClientHeader>")
                        .contains("</ClientHeader>")
                        .contains("soapenv:Body")
                        .contains("HeaderPartRequest")
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    private fun scalarRequestBranch(body: String): String {
        return when {
            "CustomerNumber" in body -> "customerNumber"
            "LoginId" in body -> "loginId"
            else -> error("No scalar choice branch found in request body: $body")
        }
    }

    private fun complexRequestBranch(body: String): String {
        return when {
            "CustomerByPermId" in body -> "customerByPermId"
            "CustomerByLogin" in body -> "customerByLogin"
            else -> error("No complex choice branch found in request body: $body")
        }
    }

    private fun countOccurrences(text: String, token: String): Int {
        return text.windowed(token.length, 1).count { it == token }
    }
}
