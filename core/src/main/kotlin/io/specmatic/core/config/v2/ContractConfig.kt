package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.v3.SpecExecutionConfig
import io.specmatic.core.config.v3.ConsumesDeserializer
import io.specmatic.core.config.v3.ProvidesDeserializer

data class ContractConfig(
    @JsonIgnore
    val contractSource: ContractSource? = null,
    @JsonDeserialize(using = ProvidesDeserializer::class)
    val provides: List<SpecExecutionConfig>? = null,
    val consumes: List<SpecExecutionConfig>? = null
) {
    @JsonCreator
    @Suppress("unused")
    constructor(
        @JsonProperty("git") git: GitContractSource? = null,
        @JsonProperty("filesystem") filesystem: FileSystemContractSource? = null,
        @JsonDeserialize(using = ProvidesDeserializer::class) @JsonProperty("provides") provides: List<SpecExecutionConfig>? = null,
        @JsonDeserialize(using = ConsumesDeserializer::class) @JsonProperty("consumes") consumes: List<SpecExecutionConfig>? = null
    ) : this(
        contractSource = git ?: filesystem,
        provides = provides,
        consumes = consumes
    )

    constructor(source: Source) : this(
        contractSource = when {
            source.provider == SourceProvider.git -> GitContractSource(source)
            source.directory != null -> FileSystemContractSource(source)
            else -> null
        },
        provides = source.test,
        consumes = source.stub
    )

    @JsonProperty("git")
    @Suppress("unused")
    fun getGitSource(): GitContractSource? {
        return contractSource as? GitContractSource
    }

    @JsonProperty("filesystem")
    @Suppress("unused")
    fun getFilesystemSource(): FileSystemContractSource? {
        return contractSource as? FileSystemContractSource
    }

    fun transform(): Source {
        return this.contractSource?.transform(provides, consumes)
            ?: Source(test = provides, stub = consumes)
    }

    fun interface ContractSource {
        fun transform(provides: List<SpecExecutionConfig>?, consumes: List<SpecExecutionConfig>?): Source
    }

    data class GitContractSource(
        val url: String? = null,
        val branch: String? = null,
        val matchBranch: Boolean? = null
    ) : ContractSource {
        constructor(source: Source) : this(source.repository, source.branch, source.matchBranch)

        override fun transform(provides: List<SpecExecutionConfig>?, consumes: List<SpecExecutionConfig>?): Source {
            return Source(
                provider = SourceProvider.git,
                repository = this.url,
                branch = this.branch,
                test = provides.orEmpty(),
                stub = consumes.orEmpty(),
                matchBranch = this.matchBranch
            )
        }
    }

    data class FileSystemContractSource(
        val directory: String = "."
    ) : ContractSource {
        constructor(source: Source) : this(source.directory ?: ".")

        override fun transform(provides: List<SpecExecutionConfig>?, consumes: List<SpecExecutionConfig>?): Source {
            return Source(
                provider = SourceProvider.filesystem,
                directory = this.directory,
                test = provides.orEmpty(),
                stub = consumes.orEmpty(),
            )
        }
    }
}
