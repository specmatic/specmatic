package io.specmatic.core.pattern.regex

class ExecutionStack(
    stage: Stage,
) {
    private val executionStack =
        ArrayDeque<Stage>().also {
            it.addLast(stage)
        }

    fun lastStage(): Stage? = executionStack.lastOrNull()

    fun backtrack() {
        executionStack.removeLast()
        executionStack.lastOrNull()?.dropLastChar()
    }

    fun addStage(newStage: Stage) {
        executionStack.addLast(newStage)
    }
}