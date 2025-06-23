package io.specmatic.core.filters

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NumericComparisonOperatorFunctionTest {

    @Test
    fun `should delegate to FilterContext#compare`() {
        val evalExExpression = ExpressionStandardizer.filterToEvalEx("Foo > '10'")
        val filterContext = mockk<FilterContext>()
        every {filterContext.compare("Foo", ">", "10")}.returns(true)
        evalExExpression.with("context", filterContext)
        val evaluationValue = evalExExpression.evaluate()
        assertThat(evaluationValue.booleanValue).isTrue
        verify { filterContext.compare("Foo", ">", "10") }
    }
}
