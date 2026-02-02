package io.specmatic.core.config.v2

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

/**
 * Delegates provides deserialization to ConsumesDeserializer while disallowing basePath.
 */
class ProvidesDeserializer : JsonDeserializer<List<SpecExecutionConfig>>() {
    private val delegate = ConsumesDeserializer(consumes = false)

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<SpecExecutionConfig> =
        delegate.deserialize(p, ctxt)
}
