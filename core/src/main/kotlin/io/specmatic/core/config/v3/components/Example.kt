package io.specmatic.core.config.v3.components

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.mapValue
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

class ExampleDirectories(val directories: List<String>) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ExampleDirectories {
        return ExampleDirectories(
            directories.mapIndexed { i, path ->
                mapper.child("directories").child(i).map(path, configDirectory)
            }
        )
    }
}
data class Examples(
    val testExamples: List<RefOrValue<ExampleDirectories>>? = null,
    val mockExamples: List<RefOrValue<ExampleDirectories>>? = null,
    val commonExamples: ExampleDirectories? = null,
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Examples = copy(
        commonExamples = commonExamples?.mapPaths(mapper.child("commonExamples"), configDirectory),
        testExamples = testExamples?.mapIndexed { i, value ->
            value.mapValue { it.mapPaths(mapper.child("testExamples").child(i), configDirectory) }
        },
        mockExamples = mockExamples?.mapIndexed { i, value -> value.mapValue {
            it.mapPaths(mapper.child("mockExamples").child(i), configDirectory) }
        },
    )
}
