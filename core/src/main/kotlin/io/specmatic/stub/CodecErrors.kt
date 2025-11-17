package io.specmatic.stub

data class InterceptorErrors(val errors: List<InterceptorError>) {
    fun hasErrors(): Boolean = errors.isNotEmpty()

    fun isEmpty(): Boolean = errors.isEmpty()

    fun firstError(): InterceptorError? = errors.firstOrNull()

    override fun toString(): String {
        if (errors.isEmpty()) {
            return "No interceptor errors"
        }

        return if (errors.size == 1) {
            errors.first().toString()
        } else {
            buildString {
                appendLine("Multiple interceptor errors occurred (${errors.size} errors):")
                errors.forEachIndexed { index, error ->
                    appendLine()
                    appendLine("Error ${index + 1}:")
                    appendLine(error.toString().prependIndent("  "))
                }
            }.trimEnd()
        }
    }

    companion object {
        fun empty() = InterceptorErrors(emptyList())
        fun single(error: InterceptorError) = InterceptorErrors(listOf(error))
    }
}
