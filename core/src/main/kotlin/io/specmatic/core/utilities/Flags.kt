package io.specmatic.core.utilities

class Flags {
    companion object {
        const val SPECMATIC_GENERATIVE_TESTS = "SPECMATIC_GENERATIVE_TESTS"
        const val ONLY_POSITIVE = "ONLY_POSITIVE"
        const val VALIDATE_RESPONSE_VALUE = "VALIDATE_RESPONSE_VALUE"
        const val EXTENSIBLE_SCHEMA = "EXTENSIBLE_SCHEMA"
        const val MAX_TEST_REQUEST_COMBINATIONS = "MAX_TEST_REQUEST_COMBINATIONS"
        const val SCHEMA_EXAMPLE_DEFAULT = "SCHEMA_EXAMPLE_DEFAULT"
        const val SPECMATIC_TEST_PARALLELISM = "SPECMATIC_TEST_PARALLELISM"
        const val SPECMATIC_STUB_DELAY = "SPECMATIC_STUB_DELAY"
        const val SPECMATIC_TEST_TIMEOUT = "SPECMATIC_TEST_TIMEOUT"
        const val ALL_PATTERNS_MANDATORY = "ALL_PATTERNS_MANDATORY"
        const val SPECMATIC_BASE_URL = "SPECMATIC_BASE_URL"
        const val SPECMATIC_ESCAPE_SOAP_ACTION = "SPECMATIC_ESCAPE_SOAP_ACTION"

        const val CONFIG_FILE_PATH = "CONFIG_FILE_PATH"

        const val IGNORE_INLINE_EXAMPLES = "IGNORE_INLINE_EXAMPLES"
        const val IGNORE_INLINE_EXAMPLE_WARNINGS = "IGNORE_INLINE_EXAMPLE_WARNINGS"

        const val SPECMATIC_PRETTY_PRINT = "SPECMATIC_PRETTY_PRINT"
        const val EXAMPLE_DIRECTORIES = "EXAMPLE_DIRECTORIES"

        const val EXTENSIBLE_QUERY_PARAMS = "EXTENSIBLE_QUERY_PARAMS"
        const val ADDITIONAL_EXAMPLE_PARAMS_FILE = "ADDITIONAL_EXAMPLE_PARAMS_FILE"

        fun getStringValue(flagName: String): String? = System.getenv(flagName) ?: System.getProperty(flagName)

        fun getBooleanValue(flagName: String, default: Boolean = false) = getStringValue(flagName)?.toBoolean() ?: default

        fun getLongValue(flagName: String): Long? = ( getStringValue(flagName))?.toLong()

        fun <T> using(vararg properties: Pair<String, String>, fn: () -> T): T {
            try {
                properties.forEach { (key, value) ->
                    System.setProperty(key, value)
                }

                return fn()
            } finally {
                properties.forEach { (key, value) ->
                    System.clearProperty(key)
                }
            }
        }
    }
}