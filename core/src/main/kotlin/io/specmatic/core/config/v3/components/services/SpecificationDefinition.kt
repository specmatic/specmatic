package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = SpecificationDefinition.StringValue::class)
sealed interface SpecificationDefinition {
    data class Specification(val id: String? = null, val path: String, val urlPathPrefix: String? = null)

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    data class ObjectValue(val spec: Specification): SpecificationDefinition

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    data class StringValue(val specification: String) : SpecificationDefinition {
        @Suppress("unused")
        @JsonValue
        fun asValue() = specification

        companion object {
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun from(value: String): SpecificationDefinition = StringValue(value)
        }
    }
}