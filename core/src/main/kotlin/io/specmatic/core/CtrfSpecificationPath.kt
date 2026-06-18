package io.specmatic.core

import io.specmatic.core.git.SystemGit
import java.io.File

internal fun normalizeFilesystemSpecificationPath(
    specificationPath: String,
    sourceProvider: SourceProvider?,
    resolvedSpecFile: File,
): String {
    if (sourceProvider != SourceProvider.filesystem) {
        return specificationPath
    }

    if (specificationPath.isBlank()) {
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
