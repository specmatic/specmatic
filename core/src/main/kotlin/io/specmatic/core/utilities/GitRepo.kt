package io.specmatic.core.utilities

import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.Configuration
import io.specmatic.core.git.SystemGit
import io.specmatic.core.git.checkout
import io.specmatic.core.git.clone
import io.specmatic.core.log.logger
import java.io.File

interface GitSource

data class GitRepo(
    val gitRepositoryURL: String,
    val branchName: String?,
    override val testContracts: List<ContractSourceEntry>,
    override val stubContracts: List<ContractSourceEntry>,
    override val type: String?,
    val useCurrentBranchForCentralRepo: Boolean = false
) : ContractSource, GitSource {
    private val repoName = gitRepositoryURL.split("/").last().removeSuffix(".git")
    override fun pathDescriptor(path: String): String {
        return "${repoName}:${path}"
    }

    override fun directoryRelativeTo(workingDirectory: File) =
        workingDirectory.resolve(repoName)

    override fun getLatest(sourceGit: SystemGit) {
        sourceGit.pull()
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        commitAndPush(sourceGit)
    }

    override fun loadContracts(
        selector: ContractsSelectorPredicate,
        workingDirectory: String,
        configFilePath: String
    ): List<ContractPathData> {
        val userHome = File(System.getProperty("user.home"))
        val defaultSpecmaticWorkingDir = userHome.resolve(".$APPLICATION_NAME_LOWER_CASE/repos")
        val defaultRepoDir = directoryRelativeTo(defaultSpecmaticWorkingDir)

        val bundleDir = File(Configuration.TEST_BUNDLE_RELATIVE_PATH).resolve(repoName)

        val repoDir = when {
            bundleDir.exists() -> {
                logger.log("Using contracts from ${bundleDir.path}")
                bundleDir
            }

            defaultRepoDir.exists() -> {
                logger.log("Using contracts in home dir")
                defaultRepoDir
            }

            else -> {
                val reposBaseDir = localRepoDir(workingDirectory)
                val contractsRepoDir =  this.directoryRelativeTo(reposBaseDir)
                logger.log("Looking for a contract repo checkout at: ${contractsRepoDir.canonicalPath}")
                when {
                    !contractsRepoDir.exists() -> {
                        logger.log("Contract repo dir does not exist.")
                        cloneRepoAndCheckoutBranch(reposBaseDir, this)
                    }
                    isNotOnBranch(contractsRepoDir) -> {
                        logger.log("Contract repo exists but is not on the correct branch.")
                        cloneRepoAndCheckoutBranch(reposBaseDir, this)
                    }
                    isBehind(contractsRepoDir) -> {
                        logger.log("Contract repo exists but is behind the remote.")
                        cloneRepoAndCheckoutBranch(reposBaseDir, this)
                    }
                    isClean(contractsRepoDir) -> {
                        logger.log("Contract repo exists, is clean, and is up to date with remote.")
                        contractsRepoDir
                    }
                    else -> {
                        logger.log("Contract repo exists, but it is not clean.")
                        cloneRepoAndCheckoutBranch(reposBaseDir, this)
                    }
                }
            }
        }

        return selector.select(this).map {
            ContractPathData(
                repoDir.path,
                repoDir.resolve(it.path).path,
                type,
                gitRepositoryURL,
                branchName,
                it.path,
                it.baseUrl,
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

    private fun isNotOnBranch(contractRepoDir: File): Boolean {
        val sourceGit = getSystemGit(contractRepoDir.path)
        val expectedBranch = branchName ?: sourceGit.getOriginDefaultBranchName()
        val currentBranch = sourceGit.currentBranch()
        return expectedBranch != currentBranch
    }

    private fun isClean(contractsRepoDir: File): Boolean {
        val sourceGit = getSystemGit(contractsRepoDir.path)
        return sourceGit.statusPorcelain().isEmpty()
    }

    private fun isBehind(contractsRepoDir: File): Boolean {
        val sourceGit = getSystemGitWithAuth(contractsRepoDir.path)
        sourceGit.fetch()
        return sourceGit.revisionsBehindCount() > 0
    }

    private fun cloneRepoAndCheckoutBranch(reposBaseDir: File, gitRepo: GitRepo): File {
        logger.log("Cloning $gitRepositoryURL into ${reposBaseDir.path}")
        reposBaseDir.mkdirs()
        val repositoryDirectory = clone(reposBaseDir, gitRepo)
        when (branchName) {
            null -> logger.log("No branch specified, using default branch")
            else -> checkout(repositoryDirectory, branchName, useCurrentBranchForCentralRepo)
        }
        return repositoryDirectory
    }

    private fun localRepoDir(workingDirectory: String): File = File(workingDirectory).resolve("repos")

    override fun install(workingDirectory: File) {
        val baseReposDirectory = workingDirectory.resolve("repos")
        val sourceDir = baseReposDirectory.resolve(repoName)
        val sourceGit = SystemGit(sourceDir.path)

        try {
            println("Checking ${sourceDir.path}")
            if (!sourceDir.exists())
                sourceDir.mkdirs()

            if (!sourceGit.workingDirectoryIsGitRepo() || isEmptyNestedGitDirectory(sourceGit, sourceDir)) {
                println("Found it, not a git dir, recreating...")
                sourceDir.deleteRecursively()
                sourceDir.mkdirs()
                println("Cloning ${this.gitRepositoryURL} into ${sourceDir.canonicalPath}")
                this.cloneRepoAndCheckoutBranch(sourceDir.canonicalFile.parentFile, this)
            } else {
                println("Git repo already exists at ${sourceDir.path}, so ignoring it and moving on")
            }
        } catch (e: Throwable) {
            println("Could not clone ${this.gitRepositoryURL}\n${e.javaClass.name}: ${exceptionCauseMessage(e)}")
        }
    }

    private fun isEmptyNestedGitDirectory(sourceGit: SystemGit, sourceDir: File) =
        (sourceGit.workingDirectoryIsGitRepo() && sourceGit.getRemoteUrl() != this.gitRepositoryURL && sourceDir.listFiles()?.isEmpty() == true)
}
