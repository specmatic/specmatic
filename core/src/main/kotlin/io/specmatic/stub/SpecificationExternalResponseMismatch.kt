package io.specmatic.stub

import io.specmatic.core.MismatchMessages
import io.specmatic.core.utilities.capitalizeFirstChar

object SpecificationExternalResponseMismatch: MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but response from external command contained $actual"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Specification expected mandatory $keyLabel \"$keyName\" to be present but was missing in the response from the external command"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional $keyLabel \"$keyName\" from specification to be present but was missing in the response from the external command"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the response from the external command was not in the specification"
    }
}
