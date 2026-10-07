package io.specmatic.core.pipeline

import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.asReturnValue

class Pipeline<I, O> private constructor(private val input: I, private val execute: (I) -> ReturnValue<O>) {
    fun <Next> then(stage: (O) -> ReturnValue<Next>): Pipeline<I, Next> {
        return Pipeline(input) { value ->
            execute(value).ifHasValue { current ->
                runCatching { stage(current.value) }
                    .getOrElse { it.asReturnValue("") }
                    .ifHasValue { next ->
                        HasValue(next.value, current.valueDetails + next.valueDetails)
                    }
            }
        }
    }

    fun run(): ReturnValue<O> = execute(input)

    companion object {
        fun <Input> from(input: Input): Pipeline<Input, Input> {
            return Pipeline(input) { HasValue(it) }
        }
    }
}
