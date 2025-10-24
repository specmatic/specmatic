package io.specmatic.core.pattern.regex

import io.specmatic.core.pattern.Frame

class ExecutionStack(
    frame: Frame,
) {
    private val executionStack =
        ArrayDeque<Frame>().also {
            it.addLast(frame)
        }

    fun lastOrNull(): Frame? = executionStack.lastOrNull()

    fun removeLast() {
        executionStack.removeLast()
        executionStack.lastOrNull()?.deleteLastChar()
    }

    fun addLast(newFrame: Frame) {
        executionStack.addLast(newFrame)
    }
}