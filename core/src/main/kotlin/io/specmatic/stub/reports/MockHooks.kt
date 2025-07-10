package io.specmatic.stub.reports

import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener

object MockHooks {
    private val listeners: MutableList<MockEventListener> = mutableListOf()

    fun registerListener(listener: MockEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: MockEventListener) {
        listeners.remove(listener)
    }

    fun onEachListener(block: MockEventListener.() -> Unit) {
        listeners.forEach(block)
    }

    fun onMockEvent(mockEvent: MockEvent) {
        onEachListener { onRespond(mockEvent) }
    }
}