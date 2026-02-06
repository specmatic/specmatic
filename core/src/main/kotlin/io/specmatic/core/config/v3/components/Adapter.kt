package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonValue

data class Adapter(@JsonValue val hooks: Map<String, String>)
