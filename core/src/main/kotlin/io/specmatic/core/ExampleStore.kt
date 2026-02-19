package io.specmatic.core

import io.specmatic.mock.ScenarioStub

enum class ExampleType { INLINE, EXTERNAL }

enum class ExampleTag { PERSISTENT, TRANSIENT, PARTIAL, DATA_LOOKUP }

data class ExampleData(
    val example: ScenarioStub,
    val name: String,
    val type: ExampleType,
    val tags: Set<ExampleTag>
)

data class ExampleStore(val examples: List<ExampleData>, val size: Int = examples.size) {
    companion object {
        fun from(examples: List<NamedStub>, type: ExampleType): ExampleStore {
            return ExampleStore(
                examples.map { namedStub ->
                    ExampleData(
                        name = namedStub.name,
                        example = namedStub.stub,
                        type = type,
                        tags = namedStub.stub.getTags()
                    )
                }
            )
        }

        @Deprecated(
            message = "Use list-based API",
            replaceWith = ReplaceWith(
                "from(examples.flatMap { (name, pairs) -> pairs.map { (request, response) -> NamedStub(name, ScenarioStub(request = request, response = response)) } }, type)"
            )
        )
        fun from(examples: Map<String, List<Pair<HttpRequest, HttpResponse>>>, type: ExampleType): ExampleStore {
            return ExampleStore(
                examples.flatMap { (name, pairs) ->
                    pairs.map { (request, response) ->
                        val example = ScenarioStub(request = request, response = response)
                        ExampleData(name = name, example = example, type = type, tags = example.getTags())
                    }
                }
            )
        }

        fun empty(): ExampleStore {
            return ExampleStore(examples = emptyList())
        }
    }

    fun filter(predicate: (ExampleData) -> Boolean): ExampleStore {
        return ExampleStore(examples = examples.filter(predicate))
    }
}

private fun ScenarioStub.getTags(): Set<ExampleTag> {
    return buildSet {
        if (isPartial()) add(ExampleTag.PARTIAL)
        if (data.jsonObject.isNotEmpty()) add(ExampleTag.DATA_LOOKUP)
        if (stubToken != null) add(ExampleTag.TRANSIENT) else add(ExampleTag.PERSISTENT)
    }
}
