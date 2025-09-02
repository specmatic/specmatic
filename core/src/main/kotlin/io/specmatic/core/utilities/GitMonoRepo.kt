package io.specmatic.core.utilities

import io.specmatic.core.git.SystemGit
import java.io.File

data class GitMonoRepo(override val testContracts: List<ContractSourceEntry>, override val stubContracts: List<ContractSourceEntry>,
                       override val type: String?) : ContractSource, GitSource {
    override fun pathDescriptor(path: String): String = path
    override fun install(workingDirectory: File) {
        println("Checking list of mono repo paths...")

        val contracts = testContracts + stubContracts

        for (contract in contracts) {
            val existenceMessage = when {
                File(contract.path).exists() -> "${contract.path} exists"
                else -> "${contract.path} NOT FOUND!"
            }

            println(existenceMessage)
        }
    }

    override fun directoryRelativeTo(workingDirectory: File) = workingDirectory.resolve(gitRootDir())

    override fun getLatest(sourceGit: SystemGit) {
        // In mono repos, we can't pull latest arbitrarily
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        // In mono repos, we can't push arbitrarily
    }

    override fun loadContracts(selector: ContractsSelectorPredicate, workingDirectory: String, configFilePath: String): List<ContractPathData> {
        val monoRepoBaseDir = File(SystemGit().gitRoot())
        val configFileLocation = File(configFilePath).absoluteFile.parentFile

        return selector.select(this).map {
            ContractPathData(
                monoRepoBaseDir.canonicalPath,
                configFileLocation.resolve(it.path).canonicalPath,
                provider = type,
                specificationPath = it.path,
                baseUrl = it.baseUrl,
                generative = it.generative
            )
        }
    }

    override fun stubDirectoryToContractPath(contractPathDataList: List<ContractPathData>): List<Pair<String, String>> {
        return stubContracts.mapNotNull { contractSourceEntry ->
            val directory = contractPathDataList.firstOrNull {
                it.specificationPath.orEmpty() == contractSourceEntry.path
            }?.baseDir ?: return@mapNotNull null

            directory to contractSourceEntry.path
        }
    }
}
