package io.specmatic.core.filters

import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IncludesFunctionTest {

    @Test
    fun `should delegate to FilterContext#includes`() {
        val configuration = ExpressionConfiguration.builder().singleQuoteStringLiteralsAllowed(true)
            .binaryAllowed(true)
            .build()
            .withAdditionalFunctions(mapOf(Pair("includes", IncludesFunction())).entries.single())
        val expression = Expression("includes('FOO', '1', '2', '3')", configuration)

        val context = mockk<FilterContext>()
        every { context.includes("FOO", listOf("1", "2", "3")) }.returns(true)
        expression.with("context", context)

        val evaluationValue = expression.evaluate()
        assertThat(evaluationValue.booleanValue).isTrue()

        verify { context.includes("FOO", listOf("1", "2", "3")) }
    }
}
