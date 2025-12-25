package io.specmatic.core

import io.specmatic.core.utilities.capitalizeFirstChar

object AttributeSelectionWithExampleMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but example contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "Unexpected ${keyLabel.capitalizeFirstChar()} \"$keyName\" in the example"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Expected mandatory ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" to be present but was missing from the example"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" to be present but was missing from the example"
    }
}

object AttributeSelectionWithResponseMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but response contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "Unexpected ${keyLabel.capitalizeFirstChar()} \"$keyName\" in the response"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Expected mandatory ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" to be present but was missing from the response"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" to be present but was missing from the response"
    }
}
