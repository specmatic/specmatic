package io.specmatic.core.config.v3.components.sources

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.config.v2.Source
import io.specmatic.core.config.v2.SourceProvider
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.utilities.applyIf
import java.io.File

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(JsonSubTypes.Type(value = SourceV3.Git::class, name = "git"), JsonSubTypes.Type(value = SourceV3.FileSystem::class, name = "filesystem"))
sealed interface SourceV3 {
    fun resolveSpecification(specification: File): File

    data class Git(val url: String? = null, val branch: String? = null, val matchBranch: Boolean? = null, val auth: GitAuthentication? = null) : SourceV3 {
        constructor(source: Source): this(
            url = source.repository ?: throw IllegalArgumentException("Source doesn't contain a repository url"),
            branch = source.branch,
            matchBranch = source.matchBranch,
            auth = null, // TODO: Where is this in Base
        )

        override fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            val specmaticFolder = workingDirectory.resolve(WorkingDirectory(DEFAULT_WORKING_DIRECTORY).path)
            val repository = url?.split("/")?.lastOrNull()?.removeSuffix(".git")
            return if (repository != null) {
                specmaticFolder.resolve("repos").resolve(repository).resolve(specification).canonicalFile
            } else {
                workingDirectory.resolve(specification).canonicalFile
            }
        }
    }

    data class FileSystem(val directory: String? = null) : SourceV3 {
        constructor(source: Source): this(directory = source.directory)

        override fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            val specDirectory = workingDirectory.applyIf(directory) { resolve(it) }
            return specDirectory.resolve(specification).canonicalFile
        }
    }

    companion object {
        fun from(source: Source): SourceV3? {
            return when(source.provider) {
                SourceProvider.git -> Git(source)
                SourceProvider.filesystem -> FileSystem(source)
                else -> null
            }
        }
    }
}
