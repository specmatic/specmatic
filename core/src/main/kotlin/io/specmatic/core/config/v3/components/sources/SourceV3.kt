package io.specmatic.core.config.v3.components.sources

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.ResolvedWebSource
import io.specmatic.stub.extractPort
import java.io.File

data class SourceV3(private val git: TemplateOrValue<Git>?, private val fileSystem: TemplateOrValue<FileSystem>?, private val web: TemplateOrValue<Web>?) {
    init {
        val configuredSources = listOfNotNull(git, fileSystem, web)
        if (configuredSources.isEmpty()) throw IllegalStateException("Must specify exactly one of 'git', 'filesystem', or 'web'")
        if (configuredSources.size > 1) throw IllegalStateException("Specify only one of 'git', 'filesystem', or 'web'")
        if (web?.resolve()?.url?.resolve().isNullOrBlank()) throw ContractException("Missing required field 'url' in 'web' source in Specmatic configuration")
    }

    @Suppress("unused")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("git")
    fun getGit(): Git? = git?.resolve()

    @Suppress("unused")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("filesystem")
    fun getFileSystem(): FileSystem? = fileSystem?.resolve()

    @Suppress("unused")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("web")
    fun getWeb(): Web? = web?.resolve()

    fun resolveSpecification(specification: File): File {
        if (git != null) return git.resolve().resolveSpecification(specification)
        if (web != null) return web.resolve().resolveSpecification(specification)
        return fileSystem?.resolve()?.resolveSpecification(specification) ?: specification
    }

    fun toProviderType(): SourceProvider = when {
        git != null -> SourceProvider.git
        web != null -> SourceProvider.web
        else -> SourceProvider.filesystem
    }

    fun toSpecificationSource(specFile: File, specPathInConfig: String, baseUrl: String?, resiliencyTestSuite: ResiliencyTestSuite?, examples: List<String>?): SpecificationSourceEntry {
        val type = when {
            git != null -> SourceProvider.git
            web != null -> SourceProvider.web
            else -> SourceProvider.filesystem
        }

        return SpecificationSourceEntry(
            specFile = specFile,
            specPathInConfig = specPathInConfig,
            type = type,
            repository = git?.resolve()?.url?.resolve(),
            directory = fileSystem?.resolve()?.directory?.resolve(),
            branch = git?.resolve()?.branch?.resolve(),
            matchBranch = git?.resolve()?.matchBranch?.resolve(),
            baseUrl = baseUrl,
            webSourceUrl = web?.resolve()?.url?.resolve(),
            port = baseUrl?.let(::extractPort),
            resiliencyTestSuite = resiliencyTestSuite,
            exampleDirs = examples,
        )
    }

    fun withMatchBranch(matchBranch: Boolean): SourceV3 {
        val currentGit = this.git?.resolve() ?: return this
        return this.copy(git = TemplateOrValue.Value(currentGit.copy(matchBranch = TemplateOrValue.Value(matchBranch))))
    }

    data class Git(val url: TemplateOrValue<String>? = null, val branch: TemplateOrValue<String>? = null, val matchBranch: TemplateOrValue<Boolean>? = null, val auth: TemplateOrValue<GitAuthentication>? = null) {
        fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            val specmaticFolder = workingDirectory.resolve(WorkingDirectory(DEFAULT_WORKING_DIRECTORY).path)
            val repository = url?.resolve()?.split("/")?.lastOrNull()?.removeSuffix(".git")
            return if (repository != null) {
                specmaticFolder.resolve("repos").resolve(repository).resolve(specification).canonicalFile
            } else {
                workingDirectory.resolve(specification).canonicalFile
            }
        }
    }

    data class FileSystem(val directory: TemplateOrValue<String>? = null) {
        fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            val specDirectory = directory?.resolve()?.let { workingDirectory.resolve(it) } ?: workingDirectory
            return specDirectory.resolve(specification).canonicalFile
        }
    }

    data class Web(val url: TemplateOrValue<String>? = null) {
        fun resolveSpecification(specification: File): File {
            val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
            return ResolvedWebSource.localPathFor(
                workingDirectory.resolve(DEFAULT_WORKING_DIRECTORY).resolve("web"),
                url?.resolve() ?: throw ContractException("Missing required field 'url' in 'web' source in Specmatic configuration"),
                specification.path
            )
        }
    }

    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonProperty("git") git: Git? = null, @JsonProperty("filesystem") filesystem: FileSystem? = null, @JsonProperty("web") web: Web? = null): SourceV3 {
            return SourceV3(
                git?.let { TemplateOrValue.Value(it) },
                filesystem?.let { TemplateOrValue.Value(it) },
                web?.let { TemplateOrValue.Value(it) }
            )
        }
    }
}
