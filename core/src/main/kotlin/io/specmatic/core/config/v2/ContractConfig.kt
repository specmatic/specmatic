package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.wrapFullyOrNull

data class ContractConfig(
    @JsonIgnore
    val contractSource: ContractSource? = null,
    @JsonDeserialize(using = ProvidesDeserializer::class)
    val provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null,
    val consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null
) {
    @JsonCreator
    @Suppress("unused")
    constructor(
        @JsonProperty("git") git: GitContractSource? = null,
        @JsonProperty("filesystem") filesystem: FileSystemContractSource? = null,
        @JsonProperty("web") web: WebContractSource? = null,
        @JsonDeserialize(using = ProvidesDeserializer::class) @JsonProperty("provides") provides: List<SpecExecutionConfig>? = null,
        @JsonDeserialize(using = ConsumesDeserializer::class) @JsonProperty("consumes") consumes: List<SpecExecutionConfig>? = null
    ) : this(
        contractSource = git ?: filesystem ?: web,
        provides = provides.wrapFullyOrNull(),
        consumes = consumes.wrapFullyOrNull()
    )

    constructor(source: Source) : this(
        contractSource = when {
            source.provider == SourceProvider.git -> GitContractSource(source)
            source.directory != null -> FileSystemContractSource(source)
            source.provider == SourceProvider.web && source.webBaseUrl != null -> WebContractSource(source.webBaseUrl)
            else -> null
        },
        provides = source.test.wrapFullyOrNull(),
        consumes = source.stub.wrapFullyOrNull()
    )

    @get:JsonIgnore
    val resolvedProvides: List<SpecExecutionConfig>
        get() = provides.resolveFullyOrEmpty()

    @get:JsonIgnore
    val resolvedConsumes: List<SpecExecutionConfig>
        get() = consumes.resolveFullyOrEmpty()

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

    @JsonProperty("web")
    @Suppress("unused")
    fun getWebSource(): WebContractSource? {
        return contractSource as? WebContractSource
    }

    fun transform(): Source {
        return this.contractSource?.transform(provides, consumes)
            ?: Source(test = resolvedProvides, stub = resolvedConsumes)
    }

    fun interface ContractSource {
        fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source
    }

    data class GitContractSource(
        val url: String? = null,
        val branch: String? = null,
        val matchBranch: Boolean? = null
    ) : ContractSource {
        constructor(source: Source) : this(source.repository, source.branch, source.matchBranch)

        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            return Source(
                provider = SourceProvider.git,
                repository = this.url,
                branch = this.branch,
                test = provides.resolveFullyOrEmpty(),
                stub = consumes.resolveFullyOrEmpty(),
                matchBranch = this.matchBranch
            )
        }
    }

    data class FileSystemContractSource(
        val directory: String = "."
    ) : ContractSource {
        constructor(source: Source) : this(source.directory ?: ".")

        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            return Source(
                provider = SourceProvider.filesystem,
                directory = this.directory,
                test = provides.resolveFullyOrEmpty(),
                stub = consumes.resolveFullyOrEmpty(),
            )
        }
    }

    data class WebContractSource(
        val url: String? = null
    ) : ContractSource {
        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            val resolvedUrl = url?.takeIf { it.isNotBlank() }
                ?: throw ContractException("Missing required field 'url' in 'web' contract source in Specmatic configuration")

            return Source(
                provider = SourceProvider.web,
                webBaseUrl = resolvedUrl,
                test = provides.resolveFullyOrEmpty(),
                stub = consumes.resolveFullyOrEmpty(),
            )
        }
    }
}
