package io.specmatic.core.config.v3

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

/**
 * Delegates provides deserialization to ConsumesDeserializer while disallowing basePath.
 */
class ProvidesDeserializer : JsonDeserializer<List<SpecsWithPort>>() {
    private val delegate = ConsumesDeserializer(allowBasePath = false)

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<SpecsWithPort> =
        delegate.deserialize(p, ctxt)
}
