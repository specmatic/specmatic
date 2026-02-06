package io.specmatic.core.utilities

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.git.NonZeroExitError
import io.specmatic.core.git.SystemGit
import io.specmatic.core.git.exitErrorMessageContains
import java.io.File

data class ContractSourceEntry(
    val path: String,
    val baseUrl: String? = null,
    val generative: ResiliencyTestSuite? = null,
    val exampleDirPaths: List<String>? = null,
)

sealed interface ContractSource {
    val type:String?
    val testContracts: List<ContractSourceEntry>
    val stubContracts: List<ContractSourceEntry>
    fun pathDescriptor(path: String): String
    fun install(workingDirectory: File)
    fun directoryRelativeTo(workingDirectory: File): File
    fun getLatest(sourceGit: SystemGit)
    fun pushUpdates(sourceGit: SystemGit)
    fun loadContracts(selector: ContractsSelectorPredicate, workingDirectory: String, configFilePath: String): List<ContractPathData>
    fun stubDirectoryToContractPath(contractPathDataList: List<ContractPathData>): List<Pair<String, String>>
}

fun commitAndPush(sourceGit: SystemGit) {
    val pushRequired = try {
        sourceGit.commit()
        true
    } catch (e: NonZeroExitError) {
        if (!exitErrorMessageContains(e, listOf("nothing to commit")))
            throw e

        exitErrorMessageContains(e, listOf("branch is ahead of"))
    }

    when {
        pushRequired -> {
            println("Pushing changes")
            sourceGit.push()
        }
        else -> println("No changes were made to the repo, so nothing was pushed.")
    }
}
