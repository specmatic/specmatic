package io.specmatic.core.config

import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class Testing(val field: TemplateOrValue<String>)

fun main() {
    val testing = Testing(TemplateOrValue.Value("test"))
    println(testing.field.resolve())
    println(testing.field)
}
