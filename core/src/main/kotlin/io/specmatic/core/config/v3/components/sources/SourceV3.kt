package io.specmatic.core.config.v3.components.sources

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(JsonSubTypes.Type(value = SourceV3.Git::class, name = "git"), JsonSubTypes.Type(value = SourceV3.FileSystem::class, name = "filesystem"))
sealed interface SourceV3 {
    fun transform(): Source

    data class Git(val url: String, val branch: String? = null, val matchBranch: Boolean? = null, val auth: GitAuthentication? = null) : SourceV3 {
        constructor(source: Source): this(
            url = source.repository ?: throw IllegalArgumentException("Source doesn't contain a repository url"),
            branch = source.branch,
            matchBranch = source.matchBranch,
            auth = null, // TODO: Where is this in Base
        )

        override fun transform(): Source = Source(
            provider = SourceProvider.git,
            repository = url,
            branch = branch,
            matchBranch = matchBranch
        )
    }

    data class FileSystem(val directory: String? = null) : SourceV3 {
        constructor(source: Source): this(directory = source.directory)
        override fun transform(): Source = Source(provider = SourceProvider.filesystem, directory = directory)
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
