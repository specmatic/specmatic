package io.specmatic.stub.report

enum class StubType(val value: String) {
    EXPLICIT("explicit"),
    EXAMPLE("example"),
    GENERATED("generated"),
    PASS_THROUGH("pass-through");

    companion object {
        fun fromValue(value: String): StubType {
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("Unknown stub type: $value")
        }
    }
}