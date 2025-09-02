package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.v3.SpecsWithPort
import io.specmatic.core.config.v3.ConsumesDeserializer
import io.specmatic.core.config.v3.ProvidesDeserializer

data class ContractConfig(
    @JsonIgnore
    val contractSource: ContractSource? = null,
    @JsonDeserialize(using = ProvidesDeserializer::class)
    val provides: List<SpecsWithPort>? = null,
    val consumes: List<SpecsWithPort>? = null
) {
    @Suppress("unused")
    constructor(
        @JsonProperty("git") git: GitContractSource? = null,
        @JsonProperty("filesystem") filesystem: FileSystemContractSource? = null,
        @JsonDeserialize(using = ProvidesDeserializer::class) @JsonProperty("provides") provides: List<SpecsWithPort>? = null,
        @JsonDeserialize(using = ConsumesDeserializer::class) @JsonProperty("consumes") consumes: List<SpecsWithPort>? = null
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
        provides = source.testConsumes ?: source.test?.map { SpecsWithPort.StringValue(it) },
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
        val testSpecs: List<String>? = provides?.flatMap { consume ->
            when (consume) {
                is SpecsWithPort.StringValue -> listOf(consume.value)
                is SpecsWithPort.ObjectValue -> consume.specs
            }
        }
        val testSpecsWithPortOrNull = when {
            provides?.any { it is SpecsWithPort.ObjectValue } == true -> provides
            else -> null
        }
        return this.contractSource?.transform(provides, consumes)
            ?: Source(test = testSpecs, stub = consumes, testConsumes = testSpecsWithPortOrNull)
    }

    fun interface ContractSource {
        fun transform(provides: List<SpecsWithPort>?, consumes: List<SpecsWithPort>?): Source
    }

    data class GitContractSource(
        val url: String? = null,
        val branch: String? = null
    ) : ContractSource {
        constructor(source: Source) : this(source.repository, source.branch)

        override fun transform(provides: List<SpecsWithPort>?, consumes: List<SpecsWithPort>?): Source {
            val testSpecs = provides?.flatMap { p -> when(p) { is SpecsWithPort.StringValue -> listOf(p.value); is SpecsWithPort.ObjectValue -> p.specs } }
            val testSpecsWithPortOrNull = when {
                provides?.any { it is SpecsWithPort.ObjectValue } == true -> provides
                else -> null
            }
            return Source(
                provider = SourceProvider.git,
                repository = this.url,
                branch = this.branch,
                test = testSpecs,
                stub = consumes.orEmpty(),
                testConsumes = testSpecsWithPortOrNull
            )
        }
    }

    data class FileSystemContractSource(
        val directory: String = "."
    ) : ContractSource {
        constructor(source: Source) : this(source.directory ?: ".")

        override fun transform(provides: List<SpecsWithPort>?, consumes: List<SpecsWithPort>?): Source {
            val testSpecs = provides?.flatMap { p -> when(p) { is SpecsWithPort.StringValue -> listOf(p.value); is SpecsWithPort.ObjectValue -> p.specs } }
            val testSpecsWithPortOrNull = when {
                provides?.any { it is SpecsWithPort.ObjectValue } == true -> provides
                else -> null
            }
            return Source(
                provider = SourceProvider.filesystem,
                directory = this.directory,
                test = testSpecs,
                stub = consumes.orEmpty(),
                testConsumes = testSpecsWithPortOrNull
            )
        }
    }
}
