package io.specmatic.core.value

interface ScalarValue: Value {
    val nativeValue: Any?

    override fun toNativeValue(): Any? = nativeValue

    fun alterValue(): ScalarValue
}
