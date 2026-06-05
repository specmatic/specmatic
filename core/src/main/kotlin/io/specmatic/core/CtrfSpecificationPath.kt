package io.specmatic.core

import io.specmatic.core.git.SystemGit
import java.io.File

internal fun normalizeFilesystemSpecificationPath(
    specificationPath: String?,
    sourceProvider: String?,
    resolvedSpecFile: File,
): String? {
    if (sourceProvider != SourceProvider.filesystem.name) {
        return specificationPath
    }

    if (specificationPath.isNullOrBlank()) {
        return specificationPath
    }

    return runCatching {
        val canonicalSpecFile = resolvedSpecFile.canonicalFile
        val git = SystemGit(canonicalSpecFile.parentFile.absolutePath)
        if (!git.workingDirectoryIsGitRepo()) {
            return specificationPath
        }

        val repoRoot = File(git.gitRoot()).canonicalFile
        canonicalSpecFile.relativeTo(repoRoot).invariantSeparatorsPath
    }.getOrDefault(specificationPath)
}
