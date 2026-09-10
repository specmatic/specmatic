package io.specmatic.core.config.v3.components

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.ValidationContext
import io.specmatic.core.config.v3.resolveElseThrow
import io.specmatic.core.config.v3.mapValue
import io.specmatic.core.config.ConfigPathMapper
import java.io.File
import io.specmatic.core.config.validation.ConfigValidationOutput

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
    fun validate(context: ValidationContext): List<ConfigValidationOutput> {
        return buildList {
            testExamples.orEmpty().forEachIndexed { index, example ->
                addAll(context.child("testExamples").child(index).check(
                    reference = example,
                    resolve = { value, resolver -> value.resolveElseThrow(resolver) },
                ))
            }

            mockExamples.orEmpty().forEachIndexed { index, example ->
                addAll(context.child("mockExamples").child(index).check(
                    reference = example,
                    resolve = { value, resolver -> value.resolveElseThrow(resolver) },
                ))
            }
        }
    }

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
