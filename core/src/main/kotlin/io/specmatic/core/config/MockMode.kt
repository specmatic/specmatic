package io.specmatic.core.config

enum class MockMode(val cliValue: String) {
    MOCK("mock"),
    STATEFUL_MOCK("stateful-mock");

    companion object {
        fun fromCliValue(value: String): MockMode? = entries.firstOrNull { it.cliValue == value }
    }
}
