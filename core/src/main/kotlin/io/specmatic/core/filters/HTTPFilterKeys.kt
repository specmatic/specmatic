package io.specmatic.core.filters

import io.ktor.http.ContentType
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Scenario
import java.util.regex.Pattern

enum class HTTPFilterKeys(val key: String, val isPrefix: Boolean) {
    PATH("PATH", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            val scenarioPath = convertPathParameterStyle(scenario.path)
            return value == scenarioPath || matchesPath(scenarioPath, value)
        }
    },
    METHOD("METHOD", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.method.equals(value, ignoreCase = true)
        }
    },
    STATUS("STATUS", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.status == value.toIntOrNull()
        }
    },
    PARAMETERS_HEADER("PARAMETERS.HEADER", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.getHeaderKeys().caseInsensitiveContains(value)
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
    },
    PARAMETERS_QUERY("PARAMETERS.QUERY", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.getQueryParamKeys().caseSensitiveContains(value)
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
    },
    PARAMETERS_PATH("PARAMETERS.PATH", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.httpRequestPattern.httpPathPattern?.pathSegmentPatterns?.map { it.key }?.contains(value) ?: false
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
    },
    REQUEST_BODY_CONTENT_TYPE("REQUEST-BODY.CONTENT-TYPE", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            if (value.isBlank()) return false
            val contentType = scenario.httpRequestPattern.headersPattern.contentType ?: return false
            return try {
                ContentType.parse(contentType).match(value)
            } catch (_: Exception) {
                false
            }
        }
    },
    RESPONSE_CONTENT_TYPE("RESPONSE.CONTENT-TYPE", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            if (value.isBlank()) return false
            val contentType = scenario.httpResponsePattern.headersPattern.contentType ?: return false
            return try {
                ContentType.parse(contentType).match(value)
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
    },
    TAGS("TAGS", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.tags?.contains(value) ?: false
        }
    },
    SUMMARY("SUMMARY", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.summary.equals(value, ignoreCase = true)
        }
    },
    OPERATION_ID("OPERATION-ID", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.operationId == value
        }

    },
    DESCRIPTION("DESCRIPTION", false) {
        override fun includes(scenario: Scenario, key: String, value: String): Boolean {
            return scenario.operationMetadata?.description.equals(value, ignoreCase = true)
        }
    };

    abstract fun includes(scenario: Scenario, key: String, value: String): Boolean

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
