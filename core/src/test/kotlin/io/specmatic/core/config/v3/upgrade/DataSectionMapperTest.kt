package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.config.v3.RefOrValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataSectionMapperTest {
    @Test
    fun `maps examples dictionary and hooks`() {
        val data = DataSectionMapper().mapFrom(
            dictionaryPath = "dict.json",
            exampleDirectories = listOf("examples/common", "examples/payments"),
            hooks = mapOf("response-header" to "scripts/hook.js"),
        )

        val examples = (data.examples as RefOrValue.Value).value
        val directories = ((examples.single()) as RefOrValue.Value).value.directories
        assertThat(directories).containsExactly("examples/common", "examples/payments")
        assertThat((data.dictionary as RefOrValue.Value).value.path).isEqualTo("dict.json")
        assertThat((data.adapters as RefOrValue.Value).value.hooks).containsEntry("response-header", "scripts/hook.js")
    }

    @Test
    fun `keeps data fields null when input empty`() {
        val data = DataSectionMapper().mapFrom(exampleDirectories = emptyList(), dictionaryPath = null, hooks = emptyMap())
        assertThat(data.examples).isNull()
        assertThat(data.dictionary).isNull()
        assertThat(data.adapters).isNull()
    }
}
