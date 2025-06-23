package io.specmatic.core.filters

import io.specmatic.core.Scenario
import io.specmatic.mock.ScenarioStub
import java.util.regex.Pattern
import javax.activation.MimeType

enum class HTTPFilterKeys(val key: String, val isPrefix: Boolean) {
    PATH("PATH", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return value == scenario.rawPath || matchesPath(scenario.rawPath, value)
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return value == stub.request.path || matchesPath(stub.request.path ?: "", value)
        }
    },
    METHOD("METHOD", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.method.equals(value, ignoreCase = true)
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return stub.request.method.equals(value, ignoreCase = true)
        }
    },
    STATUS("STATUS", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.status == value.toIntOrNull()
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return stub.response.status == value.toIntOrNull()
        }
    },
    PARAMETERS_HEADER("PARAMETERS.HEADER", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.getHeaderKeys().caseInsensitiveContains(value)
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return stub.request.headers.keys.caseInsensitiveContains(value)
        }
    },
    PARAMETERS_HEADER_WITH_SPECIFIC_VALUE("PARAMETERS.HEADER.", true) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            val queryKey = key.substringAfter(PARAMETERS_HEADER_WITH_SPECIFIC_VALUE.key).substringBefore("=")
            val queryValue = value.substringAfter("=")
            return scenario.examples.any { eachExample ->
                eachExample.rows.any { eachRow ->
                    eachRow.containsField(queryKey) && eachRow.getField(queryKey) == queryValue
                }
            }
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            val queryKey = key.substringAfter(PARAMETERS_HEADER_WITH_SPECIFIC_VALUE.key).substringBefore("=")
            val queryValue = value.substringAfter("=")
            return stub.request.headers[queryKey] == queryValue
        }
    },
    PARAMETERS_QUERY("PARAMETERS.QUERY", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.getQueryParamKeys().caseSensitiveContains(value)
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return stub.request.queryParams.keys.caseSensitiveContains(value)
        }
    },
    PARAMETERS_QUERY_WITH_SPECIFIC_VALUE("PARAMETERS.QUERY.", true) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            val queryKey = key.substringAfter(PARAMETERS_QUERY_WITH_SPECIFIC_VALUE.key).substringBefore("=")
            val queryValue = value.substringAfter("=")
            return scenario.examples.any { eachExample ->
                eachExample.rows.any { eachRow ->
                    eachRow.containsField(queryKey) && eachRow.getField(queryKey) == queryValue
                }
            }
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            val queryKey = key.substringAfter(PARAMETERS_QUERY_WITH_SPECIFIC_VALUE.key).substringBefore("=")
            val queryValue = value.substringAfter("=")
            return stub.request.queryParams.paramPairs.any {
                it.first == queryKey && it.second == queryValue
            }
        }
    },
    PARAMETERS_PATH("PARAMETERS.PATH", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.httpPathPattern?.pathSegmentPatterns?.map { it.key }?.contains(value) ?: false
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    PARAMETERS_PATH_WITH_SPECIFIC_VALUE("PARAMETERS.PATH.", true) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            val pathKey = key.substringAfter(PARAMETERS_PATH_WITH_SPECIFIC_VALUE.key).substringBefore("=")
            val pathValue = value.substringAfter("=")
            return scenario.examples.any { eachExample ->
                eachExample.rows.any { eachRow ->
                    eachRow.containsField(pathKey) && eachRow.getField(pathKey) == pathValue
                }
            }
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    REQUEST_BODY_CONTENT_TYPE("REQUEST-BODY.CONTENT-TYPE", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return try {
                MimeType(scenario.httpRequestPattern.headersPattern.contentType).match(MimeType(value))
            } catch (_: Exception) {
                false
            }
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return try {
                MimeType(stub.request.body.httpContentType).match(MimeType(value))
            } catch (_: Exception) {
                false
            }
        }
    },
    RESPONSE_CONTENT_TYPE("RESPONSE.CONTENT-TYPE", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return try {
                MimeType(scenario.httpResponsePattern.headersPattern.contentType).match(MimeType(value))
            } catch (_: Exception) {
                false
            }
        }
        override fun includes(stub: ScenarioStub, key: String, value: String): Boolean {
            return try {
                MimeType(stub.response.body.httpContentType).match(MimeType(value))
            } catch (_: Exception) {
                false
            }
        }
    },
    EXAMPLE_NAME("EXAMPLE-NAME", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.examples.any { example ->
                example.rows.any { eachRow -> eachRow.name == value }
            }
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    TAGS("TAGS", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.tags?.contains(value) ?: false
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    SUMMARY("SUMMARY", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.summary.equals(value, ignoreCase = true)
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    OPERATION_ID("OPERATION-ID", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.operationId == value
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    },
    DESCRIPTION("DESCRIPTION", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.description.equals(value, ignoreCase = true)
        }

        override fun includes(
            stub: ScenarioStub,
            key: String,
            value: String
        ): Boolean {
            TODO("Not yet implemented")
        }
    };

    abstract fun includes(scenario: Scenario, key: String, value: String): Boolean
    abstract fun includes(stub: ScenarioStub, key: String, value: String): Boolean

    companion object {
        fun fromKey(key: String): HTTPFilterKeys {
            entries.firstOrNull { it.key == key }?.let { return it }
            return entries.firstOrNull { it.isPrefix && key.startsWith(it.key) }
                ?: throw IllegalArgumentException("Invalid filter key: $key")
        }
        private fun matchesPath(scenarioValue: String, value: String): Boolean {
            return value.contains("*") && Pattern.compile(value.replace("*", ".*")).matcher(scenarioValue).matches()
        }
    }
}

internal fun Iterable<String>.caseInsensitiveContains(needle: String): Boolean =
    this.any { haystack -> haystack.lowercase().trim().removeSuffix("?") == needle.lowercase() }

internal fun Iterable<String>.caseSensitiveContains(needle: String): Boolean =
    this.any { haystack -> haystack.trim().removeSuffix("?") == needle }
