package io.specmatic.core.log

import java.util.*

object LogTail {
    var n: Int = 50

    private var logs = Collections.synchronizedList(LinkedList<LogMessage>())
    private var snapshot = emptyList<LogMessage>()

    fun append(msg: LogMessage) {
        logs.size
        logs.add(msg)

        if(logs.size > n && logs.isNotEmpty()) {
            logs.removeFirst()
        }
    }

    fun storeSnapshot() {
        synchronized(this) { snapshot = logs.toList() }
    }

    fun getString(): String = logs.joinToString("\n") { it.toLogString() }

    fun getSnapshot(): String = snapshot.joinToString("\n") { it.toLogString() }

    fun clear() {
        logs.clear()
    }
}