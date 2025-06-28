package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.TypeStack
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import io.specmatic.stub.RequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseBuilderTest {
    @Test
    fun `should ferry request context to scenario`() {
        val responsePattern = object : Pattern {
            override fun matches(sampleData: Value?, resolver: Resolver): Result {
                TODO("Not yet implemented")
            }

            override fun generate(resolver: Resolver): Value {
                assertThat(resolver.context).isEqualTo(RequestContext(HttpRequest(path = "testPath")))
                return NullValue
            }

            override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
                TODO("Not yet implemented")
            }

            override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
                TODO("Not yet implemented")
            }

            override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
                TODO("Not yet implemented")
            }

            override fun parse(value: String, resolver: Resolver): Value {
                TODO("Not yet implemented")
            }

            override fun encompasses(
                otherPattern: Pattern,
                thisResolver: Resolver,
                otherResolver: Resolver,
                typeStack: TypeStack
            ): Result {
                TODO("Not yet implemented")
            }

            override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
                TODO("Not yet implemented")
            }

            override val typeAlias: String?
                get() = null
            override val typeName: String
                get() = TODO("Not yet implemented")
            override val pattern: Any
                get() = TODO("Not yet implemented")

        }

        val scenario = Scenario(
            "test scenario",
            HttpRequest("GET", "/").toPattern(),
            HttpResponsePattern(body = responsePattern),
            emptyMap(), emptyList(), emptyMap(), emptyMap()
        )

        ResponseBuilder(scenario, emptyMap()).build(RequestContext(HttpRequest(path = "testPath")))
    }
}