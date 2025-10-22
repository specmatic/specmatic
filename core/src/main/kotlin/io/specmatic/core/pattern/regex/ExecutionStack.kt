package io.specmatic.core.pattern.regex

import io.specmatic.core.pattern.Frame

class ExecutionStack {
    internal val executionStack = ArrayDeque<Frame>()
    private var returnedValue: ComputationResult? = null

    fun dropFrame() {
        executionStack.removeLast()
    }

    fun returnedValue() = returnedValue

    fun returnValue(result: ComputationResult) {
        executionStack.removeLast()
        returnedValue = result
    }

    fun dropLastResult() {
        returnedValue = null
    }

    fun currentFrame(): Frame? = executionStack.lastOrNull()

    fun addFrame(newFrame: Frame) {
        executionStack.addLast(newFrame)
        returnedValue = null
    }
}
