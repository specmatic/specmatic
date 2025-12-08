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

class ScenarioStubValidatorTest {
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

    companion object {
        @JvmStatic
        fun stubTyposToExpectedFailures(): Stream<Arguments> {
            return Stream.of(
                expectFailure(
                    stub = stubWith {
                        put("nam", StringValue("My Stub"))
                    },
                    ">> nam Key named \"nam\" was unexpected, Did you mean \"name\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-secs", NumberValue(10))
                    },
                    ">> delay-in-secs Key named \"delay-in-secs\" was unexpected, Did you mean \"$DELAY_IN_SECONDS\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("dealy-in-seconds", NumberValue(10))
                    },
                    ">> dealy-in-seconds Key named \"dealy-in-seconds\" was unexpected, Did you mean \"$DELAY_IN_SECONDS\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-milisecnds", NumberValue(10))
                    },
                    ">> delay-in-milisecnds Key named \"delay-in-milisecnds\" was unexpected, Did you mean \"$DELAY_IN_MILLISECONDS\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("transent", BooleanValue(true))
                    },
                    ">> transent Key named \"transent\" was unexpected, Did you mean \"$IS_TRANSIENT_MOCK\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        put("htp-stb-id", StringValue("123"))
                    },
                    ">> htp-stb-id Key named \"htp-stb-id\" was unexpected, Did you mean \"$TRANSIENT_MOCK_ID\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    ">> $MOCK_HTTP_REQUEST Expected key named \"$MOCK_HTTP_REQUEST\" was missing",
                    ">> http-req Key named \"http-req\" was unexpected, Did you mean \"$MOCK_HTTP_REQUEST\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("request", value)
                    },
                    ">> $MOCK_HTTP_REQUEST Expected key named \"$MOCK_HTTP_REQUEST\" was missing",
                    ">> request Key named \"request\" was unexpected, Did you mean \"$MOCK_HTTP_REQUEST\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("http-res", value)
                    },
                    ">> $MOCK_HTTP_RESPONSE Expected key named \"$MOCK_HTTP_RESPONSE\" was missing",
                    ">> http-res Key named \"http-res\" was unexpected, Did you mean \"$MOCK_HTTP_RESPONSE\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("response", value)
                    },
                    ">> $MOCK_HTTP_RESPONSE Expected key named \"$MOCK_HTTP_RESPONSE\" was missing",
                    ">> response Key named \"response\" was unexpected, Did you mean \"$MOCK_HTTP_RESPONSE\" ?"
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("path")!!
                            put("paths", value)
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.paths Key named \"paths\" was unexpected, Did you mean \"path\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.method Expected key named \"method\" was missing",
                    ">> $MOCK_HTTP_REQUEST.mthd Key named \"mthd\" was unexpected, Did you mean \"method\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("queries", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.queries Key named \"queries\" was unexpected, Did you mean \"query\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("header", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.header Key named \"header\" was unexpected, Did you mean \"headers\" ?"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("requestBodyRegex", StringValue("A-Z+"))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.requestBodyRegex Key named \"requestBodyRegex\" was unexpected, Did you mean \"bodyRegex\" ?"
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("header", JSONObjectValue())
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.header Key named \"header\" was unexpected, Did you mean \"headers\" ?"
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial(
                        rootMutation = {
                            val value = remove(PARTIAL)!!
                            put("partal", value)
                        }
                    ),
                    ">> $PARTIAL Expected key named \"$PARTIAL\" was missing",
                    ">> partal Key named \"partal\" was unexpected, Did you mean \"$PARTIAL\" ?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    ">> partial.$MOCK_HTTP_REQUEST Expected key named \"$MOCK_HTTP_REQUEST\" was missing",
                    ">> partial.http-req Key named \"http-req\" was unexpected, Did you mean \"$MOCK_HTTP_REQUEST\" ?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        put("nam", StringValue("Inner Name"))
                    },
                    ">> partial.nam Key named \"nam\" was unexpected, Did you mean \"name\" ?"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    ">> partial.$MOCK_HTTP_REQUEST.method Expected key named \"method\" was missing",
                    ">> partial.$MOCK_HTTP_REQUEST.mthd Key named \"mthd\" was unexpected, Did you mean \"method\" ?"
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
                    ">> name Expected string, actual was 10 (number)"
                ),
                expectFailure(
                    stub = stubWith {
                        put(IS_TRANSIENT_MOCK, StringValue("yes"))
                    },
                    ">> $IS_TRANSIENT_MOCK Expected boolean, actual was \"yes\""
                ),
                expectFailure(
                    stub = stubWith {
                        put(TRANSIENT_MOCK_ID, NumberValue(10))
                    },
                    ">> $TRANSIENT_MOCK_ID Expected string, actual was 10 (number)"
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, StringValue("10Seconds"))
                    },
                    ">> $DELAY_IN_SECONDS Expected number, actual was \"10Seconds\""
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, NumberValue(-5))
                    },
                    ">> $DELAY_IN_SECONDS Expected number >= 0, actual was -5 (number)"
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_MILLISECONDS, StringValue("OneThousand"))
                    },
                    ">> $DELAY_IN_MILLISECONDS Expected number, actual was \"OneThousand\""
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_REQUEST, StringValue("My-Request"))
                    },
                    ">> $MOCK_HTTP_REQUEST Expected JSON object, actual was \"My-Request\""
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("method", NumberValue(123))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.method Expected string, actual was 123 (number)"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("path", BooleanValue(true))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.path Expected string, actual was true (boolean)"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("query", StringValue("param=value"))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.query Expected JSON object, actual was \"param=value\""
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("headers", NumberValue(0))
                        }
                    },
                    ">> $MOCK_HTTP_REQUEST.headers Expected JSON object, actual was 0 (number)"
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_RESPONSE, StringValue("My-Response"))
                    },
                    ">> $MOCK_HTTP_RESPONSE Expected JSON object, actual was \"My-Response\""
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.status Expected number, actual was \"200 OK\""
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", NumberValue(-200))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.status Expected number >= 0, actual was -200 (number)"
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("headers", StringValue("Content-Type: JSON"))
                        }
                    },
                    ">> $MOCK_HTTP_RESPONSE.headers Expected JSON object, actual was \"Content-Type: JSON\""
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial {
                        put("name", NumberValue(12345))
                    },
                    ">> partial.name Expected string, actual was 12345 (number)"
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    ">> partial.$MOCK_HTTP_RESPONSE.status Expected number, actual was \"200 OK\""
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
