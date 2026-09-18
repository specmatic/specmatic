package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.ShutdownIntent

enum class SpecmaticShutdownIntent : ShutdownIntent {
    MOCK,
    PROXY,
    BACKWARD_COMPATIBILITY_CHECK,
}
