package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.ADDITIONAL_EXAMPLE_PARAMS_FILE
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.asserts.AssertComparisonTest.Companion.toFactStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class ExampleProcessorTest {
    companion object {
        private val payloadConfig = JSONObjectValue(
            mapOf(
                "post" to JSONObjectValue(
                    mapOf(
                        "Product" to JSONObjectValue(
                            mapOf(
                                "name" to StringValue("productName"),
                                "price" to NumberValue(1000),
                                "details" to JSONObjectValue(mapOf(
                                    "description" to StringValue("product description"),
                                    "category" to StringValue("product category")
                                )),
                                "tags" to JSONArrayValue(listOf(StringValue("tag1"), StringValue("tag2")))
                            )
                        )
                    )
                ),
                "patch" to JSONObjectValue(
                    mapOf(
                        "Product" to JSONObjectValue(
                            mapOf(
                                "price" to JSONArrayValue(listOf(NumberValue(1000), NumberValue(2000))),
                                "name" to JSONArrayValue(listOf(StringValue("productName"))),
                                "description" to StringValue("product description new")
                            )
                        )
                    )
                ),
                "queryParams" to JSONObjectValue(
                    mapOf(
                        "name" to StringValue("productName"),
                        "price" to NumberValue(1000)
                    )
                ),
                "headers" to JSONObjectValue(
                    mapOf(
                        "Bearer" to StringValue("token")
                    )
                )
            )
        )

        @JvmStatic
        fun resolveAnyTestCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                mapOf("name" to "\$(CONFIG.queryParams.name)"),
                mapOf("name" to "productName")
            ),
            Arguments.of(
                mapOf("string" to "random"),
                mapOf("string" to "random")
            ),
            Arguments.of(
                mapOf("number" to 10),
                mapOf("number" to 10)
            )
        )
    }

    @BeforeEach
    fun setupConfig(@TempDir tempDir: File) {
        val configFile = File(tempDir, "config.json")
        configFile.writeText(payloadConfig.toStringLiteral())
        System.setProperty(ADDITIONAL_EXAMPLE_PARAMS_FILE, configFile.canonicalPath)
        ExampleProcessor.cleanStores()
    }

    @AfterEach
    fun cleanStores() {
        System.clearProperty(ADDITIONAL_EXAMPLE_PARAMS_FILE)
        ExampleProcessor.cleanStores()
    }

    @Nested
    inner class DelayedRandomSubstitution {
        @Test
        fun `should not resolve delayed random substitution on row resolve`() {
            val exampleRow = Row(
                requestExample = HttpRequest(
                    headers = mapOf("Bearer" to "\$(CONFIG.headers.Bearer)"),
                    body = JSONObjectValue(
                        mapOf("price" to StringValue("\$rand(CONFIG.patch.Product.price)"))
                    )
                )
            )

            val resolvedExampleRow = ExampleProcessor.resolve(exampleRow)
            println(resolvedExampleRow.requestExample)

            assertThat(resolvedExampleRow.requestExample?.headers).isEqualTo(mapOf("Bearer" to "token"))
            assertThat(resolvedExampleRow.requestExample?.body).isEqualTo(JSONObjectValue(
                mapOf("price" to StringValue("\$rand(CONFIG.patch.Product.price)"))
            ))
        }

        @Test
        fun `should resolve delayed random substitution on request or response resolve that is not equal to Entity_key`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("price" to NumberValue(1000))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val httpRequest = HttpRequest(
                headers = mapOf("Bearer" to "token"),
                body = JSONObjectValue(
                    mapOf("price" to StringValue("\$rand(CONFIG.patch.Product.price)"))
                )
            )

            val resolvedRequest = ExampleProcessor.resolve(httpRequest)
            val price = (resolvedRequest.body as JSONObjectValue).findFirstChildByPath("price")
            println(resolvedRequest)

            assertThat(resolvedRequest.headers).isEqualTo(mapOf("Bearer" to "token"))
            assertThat(price).isEqualTo(NumberValue(2000))
        }

        @Test
        fun `should throw when there is no random value to pick that is not equal to Entity_key`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("name" to StringValue("productName"))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val httpRequest = HttpRequest(
                headers = mapOf("Bearer" to "token"),
                body = JSONObjectValue(
                    mapOf("name" to StringValue("\$rand(CONFIG.patch.Product.name)"))
                )
            )

            val exception = assertThrows<ContractException> { ExampleProcessor.resolve(httpRequest)  }
            println(exception.report())
            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> CONFIG.patch.Product.name  
            Couldn't pick a random value from "CONFIG.patch.Product.name" that was not equal to "productName"
            """.trimIndent())
        }

        @Test
        fun `should throw when random value substitution source value is not an array`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("description" to StringValue("product description"))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val httpRequest = HttpRequest(
                headers = mapOf("Bearer" to "token"),
                body = JSONObjectValue(
                    mapOf("description" to StringValue("\$rand(CONFIG.patch.Product.description)"))
                )
            )

            val exception = assertThrows<ContractException> { ExampleProcessor.resolve(httpRequest)  }
            println(exception.report())
            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> CONFIG.patch.Product.description  
            "CONFIG.patch.Product.description" is not an array in fact store
            """.trimIndent())
        }
    }

    @Nested
    inner class ResponseStore {
        @Test
        fun `should implicitly store response as ENTITY when request is POST`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("price" to NumberValue(1000))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val factStore = ExampleProcessor.getFactStore()
            assertThat(factStore).isNotEmpty
            assertThat(factStore.getValue("ENTITY")).isEqualTo(previousResponse.body)
            assertThat(factStore.getValue("ENTITY.price")).isEqualTo(NumberValue(1000))
        }

        @Test
        fun `should merge entity store with response when store is merge`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("name" to StringValue("product name"), "description" to StringValue("product description"))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val row = Row(
                responseExampleForAssertion = HttpResponse(
                    body = JSONObjectValue(mapOf("\$store" to StringValue("merge")))
                )
            )
            val request = HttpRequest(method = "PATCH")
            val response = HttpResponse(
                body = JSONObjectValue(
                    mapOf(
                        "price" to NumberValue(2000),
                        "description" to StringValue("product description new"),
                    )
                )
            )
            ExampleProcessor.store(row, request, response)

            val factStore = ExampleProcessor.getFactStore()
            println(factStore)

            assertThat(factStore).isNotEmpty
            assertThat(factStore.getValue("ENTITY.price")).isEqualTo(NumberValue(2000))
            assertThat(factStore.getValue("ENTITY.description")).isEqualTo(StringValue("product description new"))
            assertThat(factStore.getValue("ENTITY.name")).isEqualTo(StringValue("product name"))
        }

        @Test
        fun `should replace entity store with response when store is replace`() {
            val previousRequest = HttpRequest(method = "POST")
            val previousResponse = HttpResponse(
                body = JSONObjectValue(
                    mapOf("name" to StringValue("product name"), "description" to StringValue("product description"))
                )
            )
            ExampleProcessor.store(Row(), previousRequest, previousResponse)

            val row = Row(
                responseExampleForAssertion = HttpResponse(
                    body = JSONObjectValue(mapOf("\$store" to StringValue("replace")))
                )
            )
            val request = HttpRequest(method = "GET")
            val response = HttpResponse(
                body = JSONObjectValue(
                    mapOf(
                        "price" to NumberValue(2000),
                        "description" to StringValue("product description new"),
                    )
                )
            )
            ExampleProcessor.store(row, request, response)

            val factStore = ExampleProcessor.getFactStore()
            println(factStore)

            assertThat(factStore).isNotEmpty
            assertThat(factStore.getValue("ENTITY.price")).isEqualTo(NumberValue(2000))
            assertThat(factStore.getValue("ENTITY.description")).isEqualTo(StringValue("product description new"))
            assertThat(factStore.keys).doesNotContain("ENTITY.name")
            assertThat(factStore.getValue("ENTITY")).isEqualTo(response.body)
        }

        @Test
        fun `should store the first value of response body array`() {
            val request = HttpRequest(method = "GET")
            val response = HttpResponse(
                body = JSONArrayValue(
                    listOf(
                        JSONObjectValue(mapOf("price" to NumberValue(2000))),
                        JSONObjectValue(mapOf("price" to NumberValue(1000)))
                    )
                )
            )
            val row = Row(
                responseExampleForAssertion = HttpResponse(
                    body = JSONArrayValue(listOf(
                        JSONObjectValue(mapOf("\$store" to StringValue("replace")))
                    ))
                )
            )
            ExampleProcessor.store(row, request, response)

            val responseBody = response.body as JSONArrayValue
            val factStore = ExampleProcessor.getFactStore()
            println(factStore.getValue("ENTITY"))
            assertThat(factStore).isNotEmpty
            assertThat(factStore.getValue("ENTITY")).isEqualTo(responseBody.list.first())
            assertThat(factStore.getValue("ENTITY.price")).isEqualTo(NumberValue(2000))
        }
    }

    @Test
    fun `should load config from system property or specmatic config`() {
        val factStore = ExampleProcessor.getFactStore()
        println(factStore)

        assertThat(factStore).isNotEmpty
        assertThat(factStore.getValue("CONFIG")).isEqualTo(payloadConfig)
        assertThat(payloadConfig.jsonObject.entries).allSatisfy {
            assertThat(factStore.getValue("CONFIG.${it.key}")).isEqualTo(it.value)
        }
        assertThat(payloadConfig.toFactStore("CONFIG")).isEqualTo(factStore)
    }

    @Test
    fun `should substitute values from fact store if exists`() {
        val request = HttpRequest(
            queryParams = QueryParameters(
                listOf("name" to "\$(CONFIG.queryParams.name)", "price" to "\$(CONFIG.queryParams.price)")
            ),
            headers = mapOf("Bearer" to "\$(CONFIG.headers.Bearer)"),
            body = StringValue("\$(CONFIG.post.Product)")
        )
        val resolvedRequest = ExampleProcessor.resolve(request)
        println(resolvedRequest)

        assertThat(resolvedRequest.body).isEqualTo(payloadConfig.findFirstChildByPath("post.Product"))
        assertThat(resolvedRequest.queryParams.paramPairs).isEqualTo(listOf("name" to "productName", "price" to "1000"))
        assertThat(resolvedRequest.headers).isEqualTo(mapOf("Bearer" to "token"))
    }

    @ParameterizedTest
    @MethodSource("resolveAnyTestCases")
    fun `resolveAny should substitute values from fact store into the map if the substitute values exist else keep the value as is`(
        inputMap: Map<String, Any>,
        expectedMap: Map<String, Any>
    ) {
        val resolvedMap = ExampleProcessor.resolveAny(
            inputMap,
            ExampleProcessor::ifNotExitsToLookupPattern
        )

        assertThat(resolvedMap).isEqualTo(expectedMap)
    }

    @Test
    fun `should throw if lookup key is not present in fact store`() {
        val request = HttpRequest(body = StringValue("\$(CONFIG.post.Person)"))

        val exception = assertThrows<ContractException> { ExampleProcessor.resolve(request) }
        println(exception.report())
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        >> CONFIG.post.Person  
        Could not resolve "CONFIG.post.Person", key does not exist in fact store
        """.trimIndent())
    }

    @Test
    fun `should throw if response body is not json value and asked to store`() {
        val request = HttpRequest(body = NoBodyValue)
        val response = HttpResponse(body = NoBodyValue)
        val row = Row(
            name = "test",
            responseExampleForAssertion = HttpResponse(body = JSONObjectValue(mapOf("\$store" to StringValue("replace"))))
        )

        val exception = assertThrows<ContractException> { ExampleProcessor.store(row, request, response) }
        println(exception.report())
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        >> test  
       Could not save http response body as ENTITY for example "test"
        """.trimIndent())
    }

    @Test
    fun `should throw if response body array is empty and asked to store`() {
        val request = HttpRequest(body = NoBodyValue)
        val response = HttpResponse(body = JSONArrayValue(emptyList()))
        val row = Row(
            name = "test",
            responseExampleForAssertion = HttpResponse(body = JSONObjectValue(mapOf("\$store" to StringValue("merge"))))
        )

        val exception = assertThrows<ContractException> { ExampleProcessor.store(row, request, response) }
        println(exception.report())
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        >> test  
        Could not merge http response body with ENTITY for example "test"
        """.trimIndent())
    }

    @Test
    fun `should throw an exception when defined config is not found`() {
        Flags.using(ADDITIONAL_EXAMPLE_PARAMS_FILE to "/does/not/exist") {
            val exception = assertThrows<ContractException> { ExampleProcessor.cleanStores() }
            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> ${File("/does/not/exist").path}
            Could not find the CONFIG at path ${File("/does/not/exist").canonicalPath}
            """.trimIndent())
        }
    }

    @Test
    fun `should throw an exception when defined config is not valid`(@TempDir tempDir: File) {
        val configFile = File(tempDir, "config.json")
        configFile.writeText("10")
        Flags.using(ADDITIONAL_EXAMPLE_PARAMS_FILE to configFile.canonicalPath) {
            val exception = assertThrows<ContractException> { ExampleProcessor.cleanStores() }
            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> ${configFile.canonicalPath}  
            Could not parse the CONFIG at path ${configFile.canonicalPath}: Expected json object, actual was 10
            """.trimIndent())
        }
    }
}