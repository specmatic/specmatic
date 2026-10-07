package io.specmatic.core.matchers

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pipeline.Pipeline
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

private typealias PipelineCreator = (Input) -> Pipeline<Input, Value>
private data class Input(val pattern: Pattern, val value: Value, val resolver: Resolver, val data: JSONObjectValue)
class TemplateResolver private constructor(private val createPipeline: PipelineCreator) {
    fun resolve(pattern: Pattern, value: Value, resolver: Resolver, data: JSONObjectValue): ReturnValue<Value> {
        val input = Input(pattern, value, resolver, data)
        return createPipeline(input).run()
    }

    companion object {
        fun create(matcherResolutionMode: MatcherResolutionMode): TemplateResolver? {
            val matcherEngine = MatcherEngine.load() ?: return null
            val pipeline: PipelineCreator = { input ->
                Pipeline.from(input)
                    .then { resolveMatcherTemplate(input, matcherEngine, matcherResolutionMode) }
            }

            return TemplateResolver(pipeline)
        }

        private fun resolveMatcherTemplate(input: Input, engine: MatcherEngine, resolutionMode: MatcherResolutionMode): ReturnValue<Value> {
            return engine.resolveValue(
                data = input.data,
                value = input.value,
                pattern = input.pattern,
                resolver = input.resolver,
                resolutionMode = resolutionMode,
            )
        }
    }
}
