package io.specmatic.stub

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class OneShotClose(private val lifecycleLock: Any, private val action: () -> Unit) : Closeable {
    private val closeStarted = AtomicBoolean(false)
    val hasStarted: Boolean get() = closeStarted.get()

    override fun close() {
        synchronized(lifecycleLock) {
            if (closeStarted.compareAndSet(false, true)) action()
        }
    }
}

interface ShutdownHookRegistrar {
    fun register(closeable: Closeable): Closeable
}

object JvmShutdownHookRegistrar : ShutdownHookRegistrar {
    override fun register(closeable: Closeable): Closeable {
        val hook = Thread { closeable.close() }
        Runtime.getRuntime().addShutdownHook(hook)
        return Closeable {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
}

object NoOpShutdownHookRegistrar : ShutdownHookRegistrar {
    override fun register(closeable: Closeable): Closeable = Closeable {}
}
