package io.specmatic.mock

import io.specmatic.core.Result
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class FuzzyExampleJsonValidatorTest {
    private fun assertFailureContainsExactLines(result: Result, vararg expectedLines: String) {
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val failure = result as Result.Failure
        val report = failure.reportString()
        println(report)
        expectedLines.forEach { line -> assertThat(report).containsIgnoringWhitespaces(line) }
    }

    @ParameterizedTest
    @MethodSource("stubTyposToExpectedFailures")
    fun `should detect typos in keys and report back with appropriate error message`(stub: Map<String, Value>, vararg errors: String) {
        val result = FuzzyExampleJsonValidator.matches(JSONObjectValue(stub))
        assertFailureContainsExactLines(result, *errors)
    }

    @ParameterizedTest
    @MethodSource("invalidStubToExpectedFailures")
    fun `should detect invalid values for keys and report back with appropriate error message`(stub: Map<String, Value>, vararg errors: String) {
        val result = FuzzyExampleJsonValidator.matches(JSONObjectValue(stub))
        assertFailureContainsExactLines(result, *errors)
    }

    @ParameterizedTest
    @MethodSource("validStubs")
    fun `should validate correct stubs successfully`(stub: Map<String, Value>) {
        val result = FuzzyExampleJsonValidator.matches(JSONObjectValue(stub))
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @MethodSource("fixScenarios")
    fun `should fix complex scenarios including typos and invalid data types`(stubWithIssues: Map<String, Value>, expectedFixedStub: Map<String, Value>) {
        val beforeResult = FuzzyExampleJsonValidator.matches(JSONObjectValue(stubWithIssues))
        assertThat(beforeResult).isInstanceOf(Result.Failure::class.java)

        val fixedStub = FuzzyExampleJsonValidator.fix(JSONObjectValue(stubWithIssues))
        assertThat(fixedStub).isEqualTo(JSONObjectValue(expectedFixedStub))

        val afterResult = FuzzyExampleJsonValidator.matches(fixedStub)
        assertThat(afterResult).isInstanceOf(Result.Success::class.java)
    }

    companion object {
        @JvmStatic
        fun stubTyposToExpectedFailures(): Stream<Arguments> {
            return Stream.of(
                expectFailure(
                    stub = stubWith {
                        put("nam", StringValue("My Stub"))
                    },
                    ">> nam Key named \"nam\" is invalid. Did you mean \"name\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-secs", NumberValue(10))
                    },
                    ">> delay-in-secs Key named \"delay-in-secs\" is invalid. Did you mean \"delay-in-seconds\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("dealy-in-seconds", NumberValue(10))
                    },
                    ">> dealy-in-seconds Key named \"dealy-in-seconds\" is invalid. Did you mean \"delay-in-seconds\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-milisecnds", NumberValue(10))
                    },
                    ">> delay-in-milisecnds Key named \"delay-in-milisecnds\" is invalid. Did you mean \"delay-in-milliseconds\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("transent", BooleanValue(true))
                    },
                    ">> transent Key named \"transent\" is invalid. Did you mean \"transient\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("htp-stb-id", StringValue("123"))
                    },
                    ">> htp-stb-id Key named \"htp-stb-id\" is invalid. Did you mean \"http-stub-id\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    ">> http-req Key named \"http-req\" is invalid. Did you mean \"http-request\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("request", value)
                    },
                    ">> request Key named \"request\" is invalid. Did you mean \"http-request\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("http-res", value)
                    },
                    ">> http-res Key named \"http-res\" is invalid. Did you mean \"http-response\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("response", value)
                    },
                    ">> response Key named \"response\" is invalid. Did you mean \"http-response\"?"
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("path")!!
                            put("paths", value)
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.paths Key named \"paths\" is invalid. Did you mean \"path\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.mthd Key named \"mthd\" is invalid. Did you mean \"method\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("queries", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.queries Key named \"queries\" is invalid. Did you mean \"query\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("header", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.header Key named \"header\" is invalid. Did you mean \"headers\"?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("requestBodyRegex", StringValue("A-Z+"))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.requestBodyRegex Key named \"requestBodyRegex\" is invalid. Did you mean \"bodyRegex\"?"
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("header", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.header Key named \"header\" is invalid. Did you mean \"headers\"?"
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial(
                        rootMutation = {
                            val value = remove(PARTIAL)!!
                            put("partal", value)
                        }
                    ),
                    ">> partal Key named \"partal\" is invalid. Did you mean \"partial\"?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    ">> partial.http-req Key named \"http-req\" is invalid. Did you mean \"http-request\"?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        put("nam", StringValue("Inner Name"))
                    },
                    ">> partial.nam Key named \"nam\" is invalid. Did you mean \"name\"?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    ">> partial.$MOCK_HTTP_REQUEST.mthd Key named \"mthd\" is invalid. Did you mean \"method\"?"
                ),
            )
        }

        @JvmStatic
        fun invalidStubToExpectedFailures(): Stream<Arguments> {
            return Stream.of(
                expectFailure(
                    stub = stubWith {
                        put("name", NumberValue(10))
                    },
                    ">> name Should be string as per example format, but got 10 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        put(IS_TRANSIENT_MOCK, StringValue("yes"))
                    },
                    ">> $IS_TRANSIENT_MOCK Should be boolean as per example format, but got \"yes\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        put(TRANSIENT_MOCK_ID, NumberValue(10))
                    },
                    ">> $TRANSIENT_MOCK_ID Should be string as per example format, but got 10 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, StringValue("10Seconds"))
                    },
                    ">> $DELAY_IN_SECONDS Should be number as per example format, but got \"10Seconds\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, NumberValue(-5))
                    },
                    ">> $DELAY_IN_SECONDS Should be number >= 0 as per example format, but got -5 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_MILLISECONDS, StringValue("OneThousand"))
                    },
                    ">> $DELAY_IN_MILLISECONDS Should be number as per example format, but got \"OneThousand\" in the actual example"
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_REQUEST, StringValue("My-Request"))
                    },
                    ">> $MOCK_HTTP_REQUEST Should be JSON object as per example format, but got \"My-Request\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("method", NumberValue(123))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.method Should be string as per example format, but got 123 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("path", BooleanValue(true))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.path Should be string as per example format, but got true (boolean) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("query", StringValue("param=value"))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.query Should be JSON object as per example format, but got \"param=value\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("headers", NumberValue(0))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.headers Should be JSON object as per example format, but got 0 (number) in the actual example"
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_RESPONSE, StringValue("My-Response"))
                    },
                    ">> $MOCK_HTTP_RESPONSE Should be JSON object as per example format, but got \"My-Response\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.status Should be number as per example format, but got \"200 OK\" in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", NumberValue(-200))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.status Should be number >= 0 as per example format, but got -200 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("headers", StringValue("Content-Type: JSON"))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.headers Should be JSON object as per example format, but got \"Content-Type: JSON\" in the actual example"
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial {
                        put("name", NumberValue(12345))
                    },
                    ">> partial.name Should be string as per example format, but got 12345 (number) in the actual example"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    ">> partial.$MOCK_HTTP_RESPONSE.status Should be number as per example format, but got \"200 OK\" in the actual example"
                ),
            )
        }

        @JvmStatic
        fun validStubs(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(validStub()),

                Arguments.of(stubWith {
                    put("name", StringValue("Full Scenario"))
                    put(DELAY_IN_SECONDS, NumberValue(5))
                    put(TRANSIENT_MOCK_ID, StringValue("stub-123"))
                    modifyNested(MOCK_HTTP_REQUEST) {
                        put("query", JSONObjectValue(mapOf("q" to StringValue("specmatic"))))
                        put("headers", JSONObjectValue(mapOf("Authorization" to StringValue("Bearer token"))))
                        put("body", StringValue("{\"data\": 123}"))
                    }
                    modifyNested(MOCK_HTTP_RESPONSE) {
                        put("headers", JSONObjectValue(mapOf("Content-Type" to StringValue("application/json"))))
                        put("body", JSONObjectValue(mapOf("status" to StringValue("success"))))
                    }
                }),

                Arguments.of(stubWith {
                    put("extra-metadata", StringValue("Some internal data"))
                    put("description", StringValue("This key is not in the spec but should be allowed"))
                }),

                Arguments.of(stubWith {
                    modifyNested(MOCK_HTTP_REQUEST) {
                        put("extra-req-field", NumberValue(999))
                    }
                }),

                Arguments.of(stubWith {
                    modifyNested(MOCK_HTTP_RESPONSE) {
                        put("trace-id", StringValue("xyz-789"))
                    }
                }),

                Arguments.of(stubWith {
                    put("top-extra", StringValue("1"))
                    modifyNested(MOCK_HTTP_REQUEST) {
                        put("req-extra", StringValue("2"))
                    }
                    modifyNested(MOCK_HTTP_RESPONSE) {
                        put("res-extra", StringValue("3"))
                    }
                }),

                Arguments.of(stubWithPartial {}),

                Arguments.of(stubWithPartial {
                    put("name", StringValue("Inner Metadata"))
                    put(IS_TRANSIENT_MOCK, BooleanValue(true))
                }),

                Arguments.of(stubWithPartial(
                    rootMutation = {
                        put("name", StringValue("Outer Metadata"))
                    },
                )),

                Arguments.of(stubWithPartial(
                    rootMutation = {
                        put("name", StringValue("Outer Name"))
                    },
                    partialMutation = {
                        put(TRANSIENT_MOCK_ID, StringValue("inner-id"))
                    }
                )),
            )
        }

        @JvmStatic
        fun fixScenarios(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    stubWith { put("nam", StringValue("My Stub")) },
                    stubWith { put("name", StringValue("My Stub")) }
                ),
                Arguments.of(
                    stubWith { put("transent", StringValue("true")) },
                    stubWith { put(IS_TRANSIENT_MOCK, BooleanValue(true)) }
                ),
                Arguments.of(
                    stubWith { put("delay-in-secs", StringValue("5")) },
                    stubWith { put(DELAY_IN_SECONDS, NumberValue(5)) }
                ),

                Arguments.of(
                    stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    validStub()
                ),
                Arguments.of(
                    stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("request", value)
                    },
                    validStub()
                ),
                Arguments.of(
                    stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    validStub()
                ),
                Arguments.of(
                    stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("queries", JSONObjectValue(mapOf("q" to StringValue("test"))))
                        }
                    },
                    stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("query", JSONObjectValue(mapOf("q" to StringValue("test"))))
                        }
                    }
                ),
                Arguments.of(
                    stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("body-regex", StringValue(".+"))
                        }
                    },
                    stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("bodyRegex", StringValue(".+"))
                        }
                    }
                ),

                Arguments.of(
                    stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("http-res", value)
                    },
                    validStub()
                ),
                Arguments.of(
                    stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("response", value)
                    },
                    validStub()
                ),
                Arguments.of(
                    stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            remove("status")
                            put("stat", StringValue("201"))
                        }
                    },
                    stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", NumberValue(201))
                        }
                    }
                ),
                Arguments.of(
                    stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            remove("body")
                            put("res-body", StringValue("Fixed"))
                        }
                    },
                    stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("body", StringValue("Fixed"))
                        }
                    }
                ),

                Arguments.of(
                    stubWithPartial(
                        rootMutation = {
                            val value = remove("partial")!!
                            put("partal", value)
                        }
                    ),
                    stubWithPartial {}
                ),
                Arguments.of(
                    stubWithPartial {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    stubWithPartial {}
                ),
                Arguments.of(
                    stubWithPartial {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            remove("method")
                            put("mthd", StringValue("PUT"))
                        }
                    },
                    stubWithPartial {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("method", StringValue("PUT"))
                        }
                    }
                )
            )
        }

        private fun expectFailure(stub: MutableMap<String, Value>, vararg expectedMessage: String): Arguments {
            return Arguments.of(stub, expectedMessage)
        }

        private fun stubWith(mutation: MutableMap<String, Value>.() -> Unit): MutableMap<String, Value> {
            return validStub().apply(mutation)
        }

        private fun stubWithPartial(rootMutation: (MutableMap<String, Value>.() -> Unit) = {}, partialMutation: MutableMap<String, Value>.() -> Unit = {}): MutableMap<String, Value> {
            val standard = validStub()
            val request = standard.remove(MOCK_HTTP_REQUEST)!!
            val response = standard.remove(MOCK_HTTP_RESPONSE)!!

            val partialMap = mutableMapOf(MOCK_HTTP_REQUEST to request, MOCK_HTTP_RESPONSE to response)
            partialMap.apply(partialMutation)

            val rootMap = mutableMapOf<String, Value>("partial" to JSONObjectValue(partialMap))
            rootMap.apply(rootMutation)

            return rootMap
        }

        private fun MutableMap<String, Value>.modifyNested(key: String, block: MutableMap<String, Value>.() -> Unit) {
            val currentValue = this[key] as? JSONObjectValue ?: throw IllegalArgumentException("Key '$key' is not a JSONObjectValue")
            val mutableInner = currentValue.jsonObject.toMutableMap()
            mutableInner.block()
            this[key] = JSONObjectValue(mutableInner)
        }

        private fun validStub(): MutableMap<String, Value> {
            return mutableMapOf(
                MOCK_HTTP_REQUEST to JSONObjectValue(
                    mapOf(
                        "path" to StringValue("/test"),
                        "method" to StringValue("GET")
                    )
                ),
                MOCK_HTTP_RESPONSE to JSONObjectValue(
                    mapOf(
                        "status" to NumberValue(200),
                        "body" to StringValue("OK")
                    )
                )
            )
        }
    }
}
