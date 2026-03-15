package io.specmatic.core.log

import java.util.UUID

object LogCorrelationContext {
    private val correlationId: ThreadLocal<String?> = ThreadLocal.withInitial { null }

    fun current(): String? = correlationId.get()

    fun start(id: String = UUID.randomUUID().toString()): String {
        correlationId.set(id)
        return id
    }

    fun clear() {
        correlationId.remove()
    }

    fun withCorrelation(id: String, fn: () -> Unit) {
        val previous = correlationId.get()
        correlationId.set(id)
        try {
            fn()
        } finally {
            if (previous == null) {
                correlationId.remove()
            } else {
                correlationId.set(previous)
            }
        }
    }
}
