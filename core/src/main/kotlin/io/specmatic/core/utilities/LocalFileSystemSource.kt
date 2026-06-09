package io.specmatic.core.utilities

import io.specmatic.core.normalizeFilesystemSpecificationPath
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import java.io.File

data class LocalFileSystemSource(
    val directory: String = ".",
    override val testContracts: List<ContractSourceEntry>,
    override val stubContracts: List<ContractSourceEntry>
) : ContractSource {
    override val type = "filesystem"

    override fun pathDescriptor(path: String): String = path

    override fun directoryRelativeTo(workingDirectory: File): File {
        if(File(directory).isAbsolute)
            return File(directory)

        return workingDirectory.resolve(directory)
    }

    override fun getLatest(sourceGit: SystemGit) {
        logger.log("No need to get latest as this source is a directory on the local file system")
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        logger.log("No need to push updates as this source is a directory on the local file system")
    }

    override fun loadContracts(
        selector: ContractsSelectorPredicate,
        workingDirectory: String,
        configFilePath: String
    ): List<ContractPathData> {
        val configDirectory = configFilePath.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile.parentFile }
        val sourceRoot = directoryRelativeTo(configDirectory ?: File(workingDirectory)).canonicalFile

        return selector.select(this).map {
            val resolvedPath = sourceRoot.resolve(it.path).canonicalFile
            val normalizedSpecificationPath =
                normalizeFilesystemSpecificationPath(
                    specificationPath = it.path,
                    sourceProvider = type,
                    resolvedSpecFile = resolvedPath,
                )

            ContractPathData(
                baseDir = sourceRoot.path,
                path = resolvedPath.path,
                provider = type,
                specificationPath = normalizedSpecificationPath,
                baseUrl = it.baseUrl,
                generative = it.generative,
                exampleDirPaths = it.exampleDirPaths
            )
        }
    }

    override fun stubDirectoryToContractPath(contractPathDataList: List<ContractPathData>): List<Pair<String, String>> {
        return stubContracts.map { contractSourceEntry ->
            directory to contractSourceEntry.path
        }
    }
}
