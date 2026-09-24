package io.specmatic.core.lifecycle

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class OneShotClose(private val lifecycleLock: Any, private val action: Runnable) : Closeable {
    private val closeStarted = AtomicBoolean(false)
    val hasStarted: Boolean get() = closeStarted.get()

    override fun close() {
        synchronized(lifecycleLock) {
            if (closeStarted.compareAndSet(false, true)) action.run()
        }
    }
}
