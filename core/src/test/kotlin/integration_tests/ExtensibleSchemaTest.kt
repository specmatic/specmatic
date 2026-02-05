package integration_tests

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensibleSchemaTest {

    @BeforeEach
    fun beforeEach() {
        unmockkAll()
    }

    @Test
    fun `when extensible schema is enabled, a JSON request object with unexpected keys should be accepted when running tests`() {
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
            every { isExtensibleSchemaEnabled() } returns true
            every { getWorkflowDetails() } returns null
        }
        mockkObject(SpecmaticConfigV1V2Common.Companion)
        every { SpecmaticConfigV1V2Common.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()
        val feature =
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.0
info:
    title: Test
    version: 1.0.0
paths:
    /test:
        post:
            requestBody:
                content:
                    application/json:
                        schema:
                            type: object
                            required:
                                - name
                            properties:
                                name:
                                    type: string
                        examples:
                            SUCCESS:
                                value:
                                    name: John
                                    address: "Baker street"
            responses:
                '200':
                    description: OK
                    content:
                      text/plain:
                          schema:
                              type: string
                          examples:
                              SUCCESS:
                                  value: success
            """.trimIndent(),
                "",
                specmaticConfig = specmaticConfig
            ).toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                assertThat((request.body as JSONObjectValue).jsonObject).containsKey("address")
                return HttpResponse.ok("success")
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `when extensible schema is enabled, a JSON response object with unexpected keys should be accepted when running tests`() {
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
            every { isExtensibleSchemaEnabled() } returns true
            every { getWorkflowDetails() } returns null
        }
        mockkObject(SpecmaticConfigV1V2Common.Companion)
        every { SpecmaticConfigV1V2Common.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()

        val feature =
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.0
info:
    title: Test
    version: 1.0.0
paths:
    /test:
        post:
            requestBody:
                content:
                    application/json:
                        schema:
                            type: object
                            required:
                                - name
                            properties:
                                name:
                                    type: string
                        examples:
                            SUCCESS:
                                value:
                                    name: John
                                    address: "Baker street"
            responses:
                '200':
                    description: OK
                    content:
                      application/json:
                          schema:
                              type: object
                              properties:
                                    name:
                                        type: string
                          examples:
                              SUCCESS:
                                  value:
                                      name: John
            """.trimIndent(),
                "",
                specmaticConfig = specmaticConfig
            ).toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                assertThat((request.body as JSONObjectValue).jsonObject).containsKey("address")
                return HttpResponse.ok(parsedJSONObject("""{"name": "John", "address": "Baker street"}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `with extensible schema and generative tests enabled both positive and negative generated tests should appear`() {
        val specmaticConfig = SpecmaticConfigV1V2Common(test = TestConfiguration(allowExtensibleSchema = true))
        val feature =
            OpenApiSpecification.fromYAML(
                """
                    openapi: 3.0.0
                    info:
                        title: Test
                        version: 1.0.0
                    paths:
                        /test:
                            post:
                                requestBody:
                                    content:
                                        application/json:
                                            schema:
                                                type: object
                                                properties:
                                                    name:
                                                        type: string
                                            examples:
                                                SUCCESS:
                                                    value:
                                                        name: John
                                                        address: "Baker street"
                                responses:
                                    '200':
                                        description: OK
                                        content:
                                          text/plain:
                                              schema:
                                                  type: string
                                              examples:
                                                  SUCCESS:
                                                      value: success
            """.trimIndent(),
                "",
                specmaticConfig = specmaticConfig
            ).toFeature().enableGenerativeTesting()

        val testTypes = mutableListOf<String>()
        var bodyFound = false

        feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                testTypes.add(scenario.generativePrefix.trim())

                if(request.body == parsedJSONObject("""{"name": "John", "address": "Baker street"}"""))
                    bodyFound = true

                println(scenario.testDescription())
                println(request.toLogString())
            }
        })

        assertThat(testTypes.filter { it == "+ve" }).hasSizeGreaterThan(1)
        assertThat(bodyFound).isTrue()
    }
}