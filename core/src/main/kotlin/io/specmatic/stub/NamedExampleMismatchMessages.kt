package io.specmatic.stub

import io.specmatic.core.MismatchMessages
import io.specmatic.core.utilities.capitalizeFirstChar

class NamedExampleMismatchMessages(val exampleName: String) : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but example \"$exampleName\" contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the example \"$exampleName\" was not in the specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Specification expected mandatory $keyLabel \"$keyName\" to be present but was missing from the example \"$exampleName\""
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional $keyLabel \"$keyName\" from specification to be present but was missing from the example \"$exampleName\""
    }
}
