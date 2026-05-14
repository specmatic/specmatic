package io.specmatic.core.config.v3.components

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.TemplateOrValue

class ExampleDirectories(val directories: TemplateOrValue<List<TemplateOrValue<String>>>)
data class Examples(
    val testExamples: TemplateOrValue<List<TemplateOrValue<RefOrValue<ExampleDirectories>>>>? = null,
    val mockExamples: TemplateOrValue<List<TemplateOrValue<RefOrValue<ExampleDirectories>>>>? = null,
    val commonExamples: TemplateOrValue<ExampleDirectories>? = null,
)
