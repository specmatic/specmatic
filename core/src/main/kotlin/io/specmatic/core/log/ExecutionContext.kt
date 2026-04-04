package io.specmatic.core.log

enum class ExecutionMode {
    TEST,
    STUB,
    PROXY,
    EXAMPLES,
    BACKWARD_COMPATIBILITY,
    LIBRARY,
    UNKNOWN,
}

data class ExecutionContext(
    val mode: ExecutionMode,
    val label: String? = null,
    val component: String? = null,
) {
    fun displayLabel(): String = buildList {
        add(mode.name)
        label?.takeIf(String::isNotBlank)?.let(::add)
        component?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(":")

    companion object {
        val Unknown = ExecutionContext(ExecutionMode.UNKNOWN)
    }
}
