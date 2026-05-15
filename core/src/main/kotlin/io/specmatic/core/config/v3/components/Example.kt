package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow
import io.specmatic.core.config.resolveFully

class ExampleDirectories(val directories: TemplateOrValue<List<TemplateOrValue<String>>>) {
    @JsonIgnore
    fun getDirectories(): List<String> {
        return directories.resolveFully()
    }
}

data class Examples(
    val testExamples: TemplateOrValue<List<TemplateOrValue<RefOrValue<ExampleDirectories>>>>? = null,
    val mockExamples: TemplateOrValue<List<TemplateOrValue<RefOrValue<ExampleDirectories>>>>? = null,
    val commonExamples: TemplateOrValue<ExampleDirectories>? = null,
) {
    @JsonIgnore
    fun getTestExamples(resolver: RefOrValueResolver): List<List<String>>? {
        return testExamples?.resolve()?.map { it.resolveElseThrow(resolver).directories.resolveFully() }
    }

    @JsonIgnore
    fun getMockExamples(resolver: RefOrValueResolver): List<List<String>>? {
        return mockExamples?.resolve()?.map { it.resolveElseThrow(resolver).directories.resolveFully() }
    }

    @JsonIgnore
    fun getCommonExamples(): List<String>? {
        return commonExamples?.resolve()?.directories?.resolveFully()
    }
}
