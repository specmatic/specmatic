package io.specmatic.core.pattern.regex

import io.specmatic.core.pattern.regex.Frame

class ExecutionStack(
    frame: Frame,
) {
    private val executionStack =
        ArrayDeque<Frame>().also {
            it.addLast(frame)
        }

    fun lastFrame(): Frame? = executionStack.lastOrNull()

    fun backtrack() {
        executionStack.removeLast()
        executionStack.lastOrNull()?.dropLastChar()
    }

    fun addFrame(newFrame: Frame) {
        executionStack.addLast(newFrame)
    }
}