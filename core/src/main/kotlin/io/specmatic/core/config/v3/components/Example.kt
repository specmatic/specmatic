package io.specmatic.core.config.v3.components

import io.specmatic.core.config.v3.RefOrValue

class ExampleDirectories(val directories: List<String>)
data class Examples(
    val testExamples: List<RefOrValue<ExampleDirectories>>? = null,
    val mockExamples: List<RefOrValue<ExampleDirectories>>? = null,
    val commonExamples: ExampleDirectories? = null,
)
