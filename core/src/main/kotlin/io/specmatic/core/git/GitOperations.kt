@file:JvmName("GitOperations")

package io.specmatic.core.git

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.azure.AzureAuthCredentials
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.GitRepo
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.eclipse.jgit.util.HttpSupport
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URL


fun clone(workingDirectory: File, gitRepo: GitRepo, specmaticConfig: SpecmaticConfig): File {
    val cloneDirectory = gitRepo.directoryRelativeTo(workingDirectory)

    resetCloneDirectory(cloneDirectory)
    clone(gitRepo.gitRepositoryURL, cloneDirectory, specmaticConfig)

    return cloneDirectory
}

fun checkout(workingDirectory: File, branchName: String, useCurrentBranchForCentralRepo: Boolean = false) {
    logger.log("Checking out branch: $branchName")
    try {
        val git = SystemGit(workingDirectory.path)
        
        if (useCurrentBranchForCentralRepo) {
            // Check if the branch exists in origin
            val branchExists = git.remoteBranchExists(branchName)
            
            if (branchExists) {
                logger.debug("Branch $branchName exists in origin, using regular checkout")
                git.checkout(branchName)
            } else {
                logger.log("Creating branch '$branchName' in the local checkout of the central repo")
                git.checkoutWithCreate(branchName)
            }
        } else {
            git.checkout(branchName)
        }
    } catch(exception: Exception) {
        logger.debug("Could not checkout branch $branchName")
        logger.debug(exception.localizedMessage ?: exception.message ?: "")
        logger.debug(exception.stackTraceToString())
    }
}

private fun clone(gitRepositoryURI: String, cloneDirectory: File, specmaticConfig: SpecmaticConfig) {
    val systemGit: GitCommand = SystemGit(cloneDirectory.parent, "-", AzureAuthCredentials(specmaticConfig, gitRepositoryURI))
    clone(gitRepositoryURI, cloneDirectory, specmaticConfig, systemGit, JGitCloneCmd())
}

internal fun clone(
    gitRepositoryURI: String,
    cloneDirectory: File,
    specmaticConfig: SpecmaticConfig,
    systemGit: GitCommand,
    jGitCloneCommand: JGitCloneCmd
) {
    try {
        try {
            systemGit.clone(gitRepositoryURI, cloneDirectory)
        } catch(exception: Exception) {
            logger.debug("Falling back to jgit after trying shallow clone")
            logger.debug(exception.localizedMessage ?: exception.message ?: "")
            logger.debug(exception.stackTraceToString())

            jGitCloneCommand.execute(gitRepositoryURI, cloneDirectory, specmaticConfig)
        }
    } catch (e: Throwable) {
        throw enrichContractRepoAccessError(e.message ?: exceptionCauseMessage(e), gitRepositoryURI)
    }
}

private fun enrichContractRepoAccessError(originalMessage: String, gitRepositoryURI: String): ContractException {
    if (!looksLikeContractRepoReadAccessIssue(originalMessage)) {
        return ContractException(originalMessage)
    }

    val enrichedMessage =
        """
        Failed to read contract repository $gitRepositoryURI
        Specmatic only needs read access to load contracts.
        Check that the supplied git credentials are valid, have read access to this repository, and are authorized for the organization or SSO if required.
        
        Original (misleading) error from Git repository hosting service:
        $originalMessage
        """.trimIndent()

    return ContractException(enrichedMessage)
}

private fun looksLikeContractRepoReadAccessIssue(message: String): Boolean {
    val normalizedMessage = message.lowercase()

    return "requested url returned error: 403" in normalizedMessage ||
            "requested url returned error: 401" in normalizedMessage ||
            "write access to repository not granted" in normalizedMessage ||
            "authentication failed" in normalizedMessage
}

private fun resetCloneDirectory(cloneDirectory: File) {
    logger.log("Resetting ${cloneDirectory.absolutePath}")
    if (cloneDirectory.exists())
        cloneDirectory.deleteRecursively()
    cloneDirectory.mkdirs()
}

internal class InsecureHttpConnectionFactory : HttpConnectionFactory {
    @Throws(IOException::class)
    override fun create(url: URL?): HttpConnection {
        return create(url, null)
    }

    @Throws(IOException::class)
    override fun create(url: URL?, proxy: Proxy?): HttpConnection {
        val connection: HttpConnection = JDKHttpConnectionFactory().create(url, proxy)
        HttpSupport.disableSslVerify(connection)
        return connection
    }
}

fun evaluateEnvVariablesInGitRepoURI(gitRepositoryURI: String, environmentVariables: Map<String, String>): String {
    var evaluatedGitRepoUrl = gitRepositoryURI
    val envVariableRegex = Regex("\\$\\{([^}]+)}")
    val envVariableMatches = envVariableRegex.findAll(gitRepositoryURI)
    envVariableMatches.forEach { matchResult ->
        val envVariable = matchResult.groupValues[1]
        environmentVariables[envVariable]?.let { envVariableValue ->
            logger.log("Evaluating $envVariable in $gitRepositoryURI")
            evaluatedGitRepoUrl = evaluatedGitRepoUrl.replace("\${$envVariable}", envVariableValue)
        }
            ?: logger.log("$envVariable in $gitRepositoryURI resembles an environment variable, but skipping evaluation since value for the same is not set.")
    }
    return evaluatedGitRepoUrl
}

fun loadFromPath(json: Value?, path: List<String>): Value? {
    if (json !is JSONObjectValue)
        return null

    return when(path.size) {
        0 -> null
        1 -> json.jsonObject[path.first()]
        else -> loadFromPath(json.jsonObject[path.first()], path.drop(1))
    }
}

fun getBearerToken(specmaticConfig: SpecmaticConfig, repositoryUrl: String): String? {
    return specmaticConfig.getAuthBearerEnvironmentVariable(repositoryUrl)?.let {
        System.getenv(it).also { value ->
            if (value == null) {
                logger.log("$it environment variable was provided but has not been set")
            } else {
                logger.log("Found bearer token in environment variable $it")
            }
        }
    } ?: specmaticConfig.getAuthBearerFile(repositoryUrl)?.let { bearerFileName ->
        val bearerFile = File(bearerFileName).absoluteFile

        when {
            bearerFile.exists() -> {
                logger.log("Found bearer file ${bearerFile.absolutePath}")
                bearerFile.readText().trim()
            }

            else -> {
                logger.log("Could not find bearer file ${bearerFile.absolutePath}")
                null
            }
        }
    }
}

fun getPersonalAccessToken(specmaticConfig: SpecmaticConfig, repositoryUrl: String): String? {
    return specmaticConfig.getAuthPersonalAccessToken(repositoryUrl)?.takeIf { it.isNotBlank() }
}
