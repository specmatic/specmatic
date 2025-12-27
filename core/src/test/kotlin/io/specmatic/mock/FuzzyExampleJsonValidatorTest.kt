package io.specmatic.mock

import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.toViolationReportString
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
                    toViolationReportString(
                        breadCrumb = "nam",
                        details = "Key named \"nam\" is invalid. Did you mean \"name\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-secs", NumberValue(10))
                    },
                    toViolationReportString(
                        breadCrumb = "delay-in-secs",
                        details = "Key named \"delay-in-secs\" is invalid. Did you mean \"delay-in-seconds\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put("dealy-in-seconds", NumberValue(10))
                    },
                    toViolationReportString(
                        breadCrumb = "dealy-in-seconds",
                        details = "Key named \"dealy-in-seconds\" is invalid. Did you mean \"delay-in-seconds\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put("delay-in-milisecnds", NumberValue(10))
                    },
                    toViolationReportString(
                        breadCrumb = "delay-in-milisecnds",
                        details = "Key named \"delay-in-milisecnds\" is invalid. Did you mean \"delay-in-milliseconds\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put("transent", BooleanValue(true))
                    },
                    toViolationReportString(
                        breadCrumb = "transent",
                        details = "Key named \"transent\" is invalid. Did you mean \"transient\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put("htp-stb-id", StringValue("123"))
                    },
                    toViolationReportString(
                        breadCrumb = "htp-stb-id",
                        details = "Key named \"htp-stb-id\" is invalid. Did you mean \"http-stub-id\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    toViolationReportString(
                        breadCrumb = "http-req",
                        details = "Key named \"http-req\" is invalid. Did you mean \"http-request\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("request", value)
                    },
                    toViolationReportString(
                        breadCrumb = "request",
                        details = "Key named \"request\" is invalid. Did you mean \"http-request\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("http-res", value)
                    },
                    toViolationReportString(
                        breadCrumb = "http-res",
                        details = "Key named \"http-res\" is invalid. Did you mean \"http-response\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        val value = remove(MOCK_HTTP_RESPONSE)!!
                        put("response", value)
                    },
                    toViolationReportString(
                        breadCrumb = "response",
                        details = "Key named \"response\" is invalid. Did you mean \"http-response\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("path")!!
                            put("paths", value)
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.paths",
                        details = "Key named \"paths\" is invalid. Did you mean \"path\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.mthd",
                        details = "Key named \"mthd\" is invalid. Did you mean \"method\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("queries", JSONObjectValue())
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.queries",
                        details = "Key named \"queries\" is invalid. Did you mean \"query\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("header", JSONObjectValue())
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.header",
                        details = "Key named \"header\" is invalid. Did you mean \"headers\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("requestBodyRegex", StringValue("A-Z+"))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.requestBodyRegex",
                        details = "Key named \"requestBodyRegex\" is invalid. Did you mean \"bodyRegex\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("header", JSONObjectValue())
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_RESPONSE.header",
                        details = "Key named \"header\" is invalid. Did you mean \"headers\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial(
                        rootMutation = {
                            val value = remove(PARTIAL)!!
                            put("partal", value)
                        }
                    ),
                    toViolationReportString(
                        breadCrumb = "partal",
                        details = "Key named \"partal\" is invalid. Did you mean \"partial\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWithPartial {
                        val value = remove(MOCK_HTTP_REQUEST)!!
                        put("http-req", value)
                    },
                    toViolationReportString(
                        breadCrumb = "partial.http-req",
                        details = "Key named \"http-req\" is invalid. Did you mean \"http-request\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWithPartial {
                        put("nam", StringValue("Inner Name"))
                    },
                    toViolationReportString(
                        breadCrumb = "partial.nam",
                        details = "Key named \"nam\" is invalid. Did you mean \"name\"?",
                        StandardRuleViolation.OPTIONAL_PROPERTY_MISSING
                    )
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            val value = remove("method")!!
                            put("mthd", value)
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "partial.$MOCK_HTTP_REQUEST.mthd",
                        details = "Key named \"mthd\" is invalid. Did you mean \"method\"?",
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
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
                    toViolationReportString(
                        breadCrumb = "name",
                        details = "Should be string as per example format, but got 10 (number) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put(IS_TRANSIENT_MOCK, StringValue("yes"))
                    },
                    toViolationReportString(
                        breadCrumb = IS_TRANSIENT_MOCK,
                        details = "Should be boolean as per example format, but got \"yes\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put(TRANSIENT_MOCK_ID, NumberValue(10))
                    },
                    toViolationReportString(
                        breadCrumb = TRANSIENT_MOCK_ID,
                        details = "Should be string as per example format, but got 10 (number) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, StringValue("10Seconds"))
                    },
                    toViolationReportString(
                        breadCrumb = DELAY_IN_SECONDS,
                        details = "Should be number as per example format, but got \"10Seconds\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_SECONDS, NumberValue(-5))
                    },
                    toViolationReportString(
                        breadCrumb = DELAY_IN_SECONDS,
                        details = "Should be number >= 0 as per example format, but got -5 (number) in the actual example",
                        StandardRuleViolation.CONSTRAINT_VIOLATION
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        put(DELAY_IN_MILLISECONDS, StringValue("OneThousand"))
                    },
                    toViolationReportString(
                        breadCrumb = DELAY_IN_MILLISECONDS,
                        details = "Should be number as per example format, but got \"OneThousand\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),

                // $MOCK_HTTP_REQUEST failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_REQUEST, StringValue("My-Request"))
                    },
                    toViolationReportString(
                        breadCrumb = MOCK_HTTP_REQUEST,
                        details = "Should be json object as per example format, but got \"My-Request\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("method", NumberValue(123))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.method",
                        details = "Should be string as per example format, but got 123 (number) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("path", BooleanValue(true))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.path",
                        details = "Should be string as per example format, but got true (boolean) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("query", StringValue("param=value"))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.query",
                        details = "Should be json object as per example format, but got \"param=value\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_REQUEST) {
                            put("headers", NumberValue(0))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_REQUEST.headers",
                        details = "Should be json object as per example format, but got 0 (number) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),

                // $MOCK_HTTP_RESPONSE-failures
                expectFailure(
                    stub = stubWith {
                        put(MOCK_HTTP_RESPONSE, StringValue("My-Response"))
                    },
                    toViolationReportString(
                        breadCrumb = MOCK_HTTP_RESPONSE,
                        details = "Should be json object as per example format, but got \"My-Response\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_RESPONSE.status",
                        details = "Should be number as per example format, but got \"200 OK\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", NumberValue(-200))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_RESPONSE.status",
                        details = "Should be number >= 0 as per example format, but got -200 (number) in the actual example",
                        StandardRuleViolation.CONSTRAINT_VIOLATION
                    )
                ),
                expectFailure(
                    stub = stubWith {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("headers", StringValue("Content-Type: JSON"))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "$MOCK_HTTP_RESPONSE.headers",
                        details = "Should be json object as per example format, but got \"Content-Type: JSON\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),

                // PARTIALS
                expectFailure(
                    stub = stubWithPartial {
                        put("name", NumberValue(12345))
                    },
                    toViolationReportString(
                        breadCrumb = "partial.name",
                        details = "Should be string as per example format, but got 12345 (number) in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
                ),
                expectFailure(
                    stub = stubWithPartial {
                        modifyNested(MOCK_HTTP_RESPONSE) {
                            put("status", StringValue("200 OK"))
                        }
                    },
                    toViolationReportString(
                        breadCrumb = "partial.$MOCK_HTTP_RESPONSE.status",
                        details = "Should be number as per example format, but got \"200 OK\" in the actual example",
                        StandardRuleViolation.TYPE_MISMATCH
                    )
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
