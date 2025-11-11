package io.specmatic.core.utilities

import java.util.concurrent.atomic.AtomicReference

data class TransactionalState<T>(val committed: T, val working: T, val isDirty: Boolean)

class Transactional<T>(initial: T) {
    private val state = AtomicReference(TransactionalState(committed = initial, working = initial, isDirty = false))

    fun stage(newValue: T) {
        state.updateAndGet { current ->
            current.copy(working = newValue, isDirty = true)
        }
    }

    fun commit() {
        state.updateAndGet { current ->
            if (current.isDirty) {
                current.copy(committed = current.working, isDirty = false)
            } else {
                current
            }
        }
    }

    fun rollback() {
        state.updateAndGet { current ->
            if (current.isDirty) {
                current.copy(working = current.committed, isDirty = false)
            } else {
                current
            }
        }
    }

    fun getCommitted(): T = state.get().committed

    fun getWorking(): T = state.get().working

    fun isDirty(): Boolean = state.get().isDirty
}
