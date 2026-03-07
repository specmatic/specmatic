package io.specmatic.core.log

class UsesIndentationImpl(initialIndentation: Int = 0) : UsesIndentationWithHelpers {
    private val indentation = ThreadLocal.withInitial { initialIndentation }

    override fun <T> withIndentation(count: Int, block: () -> T): T {
        indentation.set(indentation.get() + count)
        return try {
            block()
        } finally {
            indentation.set(indentation.get() - count)
        }
    }

    override fun currentIndentation(): String {
        return " ".repeat(indentation.get())
    }
}
