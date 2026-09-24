package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.ShutdownRegistration
import io.specmatic.commons.shutdown.ShutdownRegistrar
import io.specmatic.commons.shutdown.ShutdownTask

object NoOpShutdownRegistrar : ShutdownRegistrar {
    override fun register(task: ShutdownTask): ShutdownRegistration = object : ShutdownRegistration {
        override fun close() = Unit
    }
}
