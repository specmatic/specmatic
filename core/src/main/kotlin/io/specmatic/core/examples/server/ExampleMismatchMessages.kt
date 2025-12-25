package io.specmatic.core.examples.server

import io.specmatic.core.MismatchMessages
import io.specmatic.core.utilities.capitalizeFirstChar

object ExampleMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but example contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the example was not in the specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Specification expected mandatory ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" to be present but was missing from the example"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Warning: Expected optional ${keyLabel.lowercase().capitalizeFirstChar()} \"$keyName\" from specification to be present but was missing from the example"
    }
}
