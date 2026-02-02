package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class RunOptionType(@get:JsonValue val value: String) {
    TEST("test"),
    MOCK("mock"),
    STATEFUL_MOCK("stateful-mock");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String) = entries.firstOrNull { it.value == value } ?: throw IllegalStateException(
            "Invalid type ${value}, must be one of ${entries.joinToString(separator = ",", transform = { it.value })}"
        )
    }
}
