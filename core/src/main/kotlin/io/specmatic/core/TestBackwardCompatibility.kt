package io.specmatic.core

import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value

fun testBackwardCompatibility(older: Feature, newer: Feature): Results {
    val operationToResult = OpenApiBackwardCompatibilityChecker(older, newer).run()
    return operationToResult.values.fold(Results()) { acc, results -> acc.plus(results) }
}

object NewAndOldSpecificationRequestMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $expected in the new specification, but $actual in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the request from the old specification is not in the new specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "New specification expects $keyLabel \"$keyName\" in the request but it is missing from the old specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return mismatchMessage(expectedPart, actualPart)
    }
}

object NewAndOldSpecificationResponseMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $actual in the new specification response but $expected in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the response from the new specification is not in the old specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "The old specification expects $keyLabel \"$keyName\" but it is missing in the new specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return NewAndOldSpecificationRequestMismatches.mismatchMessage(expectedPart, actualPart)
    }
}
