package io.specmatic.core.filters

import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.mock.ScenarioStub
import io.specmatic.mock.mockFromJSON
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HttpStubFilterContextTest {
    @ParameterizedTest
    @MethodSource("provideTestParameters")
    fun `test includes method with various parameters`(
        key: String,
        value: String,
        expected: Boolean,
        scenario: ScenarioStub
    ) {
        val httpFilterContext = HttpStubFilterContext(scenario)
        assertThat(httpFilterContext.includes(key, listOf(value))).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("provideTestParametersForCompare")
    fun `test compare method with various parameters`(
        key: String,
        operator: String,
        value: String,
        expected: Boolean,
        scenario: ScenarioStub
    ) {
        val httpFilterContext = HttpStubFilterContext(scenario)
        assertThat(httpFilterContext.compare(key, operator, value)).isEqualTo(expected)
    }

    companion object {

        val exampleOneText = """{
            "http-request": {
                "method": "POST",
                "path": "/foo",
                "body": [
                    {
                        "name": "John Doe",
                        "address": "High Street"
                    }
                ]
            },
            "http-response": {
                "status": 200,
                "body": {}
            }
        }""".trim()
        val exampleOne = mockFromJSON(jsonStringToValueMap(exampleOneText))

        val exampleTwoText = """{
            "http-request": {
                "method": "POST",
                "path": "/users/1",
                "headers": {
                    "Authentication": "Bearer token 123"
                },
                "query": {
                    "type": "admin",
                    "byId": "123",
                    "byName": "John"
                },
                "body": [
                    {
                        "name": "John Doe",
                        "address": "High Street"
                    }
                ]
            },
            "http-response": {
                "status": 200,
                "body": 100
            }
        }""".trim()
        val exampleTwo = mockFromJSON(jsonStringToValueMap(exampleTwoText))

        @JvmStatic
        fun provideTestParameters() = listOf(
            // paths are case-sensitive
            Arguments.of("PATH", "/foo", true, exampleOne),
            Arguments.of("PATH", "/FOO", false, exampleOne),
            Arguments.of("PATH", "/non-existant", false, exampleOne),

            // path globs
            Arguments.of("PATH", "/*", true, exampleOne),
            Arguments.of("PATH", "/bob/*", false, exampleOne),
            Arguments.of("PATH", "/users/*", true, exampleTwo),
            Arguments.of("PATH", "/non-existant/*", false, exampleTwo),
            Arguments.of("PATH", "/users/1", true, exampleTwo),
            Arguments.of("PATH", "/users/2", false, exampleTwo),
            Arguments.of("PATH", "/users/A", false, exampleTwo),
            Arguments.of("PATH", "/users/(id:number)", false, exampleTwo),
            Arguments.of("PATH", "/users/(id: number)", false, exampleTwo),

            // header keys are case-insensitive
            Arguments.of("PARAMETERS.HEADER", "AUTHenTICATION", true, exampleTwo),
            Arguments.of("PARAMETERS.HEADER", "content-type", false, exampleTwo),
            Arguments.of("PARAMETERS.HEADER", "non-existent-header", false, exampleTwo),

            // header keys with specific values case-sensitive
            Arguments.of("PARAMETERS.HEADER.Authentication", "Bearer token 123", true, exampleTwo),
            Arguments.of("PARAMETERS.HEADER.authentication", "Bearer token 123", false, exampleTwo),
            Arguments.of("PARAMETERS.HEADER.Authentication", "Bearer token 321", false, exampleTwo),

            // query param keys are case-sensitive
            Arguments.of("PARAMETERS.QUERY", "type", true, exampleTwo),
            Arguments.of("PARAMETERS.QUERY", "TYPE", false, exampleTwo),

            // specific query params with values
            Arguments.of("PARAMETERS.QUERY.byId", "123", true, exampleTwo),
            Arguments.of("PARAMETERS.QUERY.byId", "ABC", false, exampleTwo),

            Arguments.of("PARAMETERS.QUERY.byName", "John", true, exampleTwo),
            Arguments.of("PARAMETERS.QUERY.byName", "Bob", false, exampleTwo),

            // methods are case-insensitive
            Arguments.of("METHOD", "POST", true, exampleTwo),
            Arguments.of("METHOD", "Post", true, exampleTwo),

            // status
            Arguments.of("STATUS", "200", true, exampleTwo),
            Arguments.of("STATUS", "201", false, exampleTwo),

            // response content type for get requests
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*", true, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/json", true, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/j*", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/json*", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*+json", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*json", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/foo", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "foo", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "", false, exampleOne),
            Arguments.of("RESPONSE.CONTENT-TYPE", "text/plain", false, exampleOne),

            // body content type for post requests
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/*", true, exampleTwo),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/json", true, exampleTwo),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/json*", false, exampleTwo),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/plain", false, exampleTwo),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/*", false, exampleTwo),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/p*", false, exampleTwo),
        )

        @JvmStatic
        fun provideTestParametersForCompare() = listOf(
            Arguments.of("STATUS", ">", "200", false, exampleOne),
            Arguments.of("STATUS", "<", "200", false, exampleOne),
            Arguments.of("STATUS", ">=", "200", true, exampleOne),
            Arguments.of("STATUS", "<=", "200", true, exampleOne)
        )
    }
}