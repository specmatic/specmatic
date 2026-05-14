package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.wrap
import io.specmatic.core.pattern.ContractException

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
        @JsonDeserialize(using = ProvidesDeserializer::class)
        @JsonProperty("provides")
        provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null,
        @JsonDeserialize(using = ConsumesDeserializer::class)
        @JsonProperty("consumes")
        consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null,
    ) : this(
        contractSource = git ?: filesystem ?: web,
        provides = provides,
        consumes = consumes
    )

    constructor(source: Source) : this(
        contractSource = when {
            source.getProvider() == SourceProvider.git -> GitContractSource(source)
            source.getDirectory() != null -> FileSystemContractSource(source)
            source.getProvider() == SourceProvider.web && source.webBaseUrl != null -> WebContractSource(source.webBaseUrl)
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

    @JsonProperty("web")
    @Suppress("unused")
    fun getWebSource(): WebContractSource? {
        return contractSource as? WebContractSource
    }

    fun transform(): Source {
        return this.contractSource?.transform(provides, consumes)
            ?: Source(test = provides, stub = consumes)
    }

    fun interface ContractSource {
        fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source
    }

    data class GitContractSource(
        val url: TemplateOrValue<String>? = null,
        val branch: TemplateOrValue<String>? = null,
        val matchBranch: TemplateOrValue<Boolean>? = null
    ) : ContractSource {
        constructor(source: Source) : this(source.repository, source.branch, source.matchBranch)

        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            return Source(
                provider = wrap(SourceProvider.git),
                repository = this.url,
                branch = this.branch,
                test = provides,
                stub = consumes,
                matchBranch = this.matchBranch
            )
        }
    }

    data class FileSystemContractSource(
        val directory: TemplateOrValue<String> = wrap(".")
    ) : ContractSource {
        constructor(source: Source) : this(source.directory ?: wrap("."))

        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            return Source(
                provider = wrap(SourceProvider.filesystem),
                directory = this.directory,
                test = provides,
                stub = consumes,
            )
        }
    }

    data class WebContractSource(
        val url: TemplateOrValue<String>? = null
    ) : ContractSource {
        override fun transform(provides: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?, consumes: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>?): Source {
            url?.resolve()?.takeIf { it.isNotBlank() } ?: throw ContractException("Missing required field 'url' in 'web' contract source in Specmatic configuration")
            return Source(
                provider = wrap(SourceProvider.web),
                webBaseUrl = url,
                test = provides,
                stub = consumes,
            )
        }
    }
}
