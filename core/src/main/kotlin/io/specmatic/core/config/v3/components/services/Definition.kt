package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.mapValue
import io.specmatic.core.config.v3.components.sources.SourceV3
import java.io.File

data class Definition(val definition: Value) {
    fun mapPaths(
        mapper: ConfigPathMapper,
        configDirectory: File,
        sourceReferences: Map<String, SourceV3> = emptyMap()
    ): Definition {
        val mappedSource = definition.source.mapValue {
            it.mapPaths(mapper.child("source"), configDirectory)
        }

        val mappedBase = mappedSource.filesystemBase(configDirectory, sourceReferences) ?: return copy(
            definition = definition.copy(source = mappedSource)
        )

        return copy(definition = definition.copy(source = mappedSource, specs = mappedBase.let { base ->
            definition.specs.mapIndexed { index, spec ->
                spec.mapPaths(mapper.child("specs").child(index), base)
            }
        }))
    }

    data class Value(val source: RefOrValue<SourceV3>, val specs: List<SpecificationDefinition>)

    companion object {
        fun create(specificationDefinition: SpecificationDefinition): Definition {
            val source = SourceV3(git = null, fileSystem = SourceV3.FileSystem(), web = null)
            val value = Value(RefOrValue.Value(source), specs = listOf(specificationDefinition))
            return Definition(value)
        }
    }
}

private fun RefOrValue<SourceV3>.filesystemBase(
    configDirectory: File,
    sourceReferences: Map<String, SourceV3>
): File? {
    return when (this) {
        is RefOrValue.Value -> value.filesystemBase(configDirectory)
        is RefOrValue.Reference -> sourceReferences[ref.substringAfterLast('/')].filesystemBase(configDirectory)
    }
}

private fun SourceV3?.filesystemBase(configDirectory: File): File? {
    return this?.getFileSystem()?.directory?.let { directory ->
        val file = File(directory)
        if (file.isAbsolute) file else configDirectory.resolve(file)
    }
}
