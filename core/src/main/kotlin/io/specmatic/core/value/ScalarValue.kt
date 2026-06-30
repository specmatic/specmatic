package io.specmatic.core.value

import io.specmatic.core.value.fold.ScalarValueCase
import io.specmatic.core.value.fold.ValueVisitor

interface ScalarValue: Value {
    val nativeValue: Any?

    override fun toNativeValue(): Any? = nativeValue

    override fun <C, R> accept(visitor: ValueVisitor<C, R>, context: C): R {
        return visitor.scalar(ScalarValueCase(value = this, context = context, nativeValue = nativeValue))
    }

    fun alterValue(): ScalarValue
}
