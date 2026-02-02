package io.specmatic.core.config.v3.components.sources

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.utilities.applyIf
import java.io.File

class SourceV3(private val git: Git?, private val fileSystem: FileSystem?) {
    init {
        if (git == null && fileSystem == null) throw IllegalStateException("Must specify either 'git' or 'filesystem'")
        if (git != null && fileSystem != null) throw IllegalStateException("Specify only one of 'git' or 'filesystem'")
    }

    @Suppress("unused")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("git")
    fun getGit(): Git? = git

    @Suppress("unused")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("filesystem")
    fun getFileSystem(): FileSystem? = fileSystem

    fun resolveSpecification(specification: File): File {
        if (git != null) return git.resolveSpecification(specification)
        return fileSystem?.resolveSpecification(specification) ?: specification
    }

    data class Git(val url: String? = null, val branch: String? = null, val matchBranch: Boolean? = null, val auth: GitAuthentication? = null) {
        fun resolveSpecification(specification: File): File {
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

    data class FileSystem(val directory: String? = null) {
        fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            val specDirectory = workingDirectory.applyIf(directory) { resolve(it) }
            return specDirectory.resolve(specification).canonicalFile
        }
    }

    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonProperty("git") git: Git? = null, @JsonProperty("filesystem") filesystem: FileSystem? = null): SourceV3 {
            return SourceV3(git, filesystem)
        }
    }
}
