package io.specmatic.core.utilities

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

val jsonObjectMapper: ObjectMapper by lazy { ObjectMapper() }
val yamlObjectMapper: ObjectMapper by lazy {
    ObjectMapper(YAMLFactory())
}