@file:JvmName("GitOperations")

package io.specmatic.core.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.azure.AzureAuthCredentials
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.utilities.GitRepo
import io.specmatic.core.utilities.getTransportCallingCallback
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.ktor.http.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.eclipse.jgit.util.HttpSupport
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URL


fun clone(workingDirectory: File, gitRepo: GitRepo): File {
    val cloneDirectory = gitRepo.directoryRelativeTo(workingDirectory)

    resetCloneDirectory(cloneDirectory)
    clone(gitRepo.gitRepositoryURL, cloneDirectory)

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

private fun clone(gitRepositoryURI: String, cloneDirectory: File) {
    try {
        SystemGit(cloneDirectory.parent, "-", AzureAuthCredentials).clone(gitRepositoryURI, cloneDirectory)
    } catch(exception: Exception) {
        logger.debug("Falling back to jgit after trying shallow clone")
        logger.debug(exception.localizedMessage ?: exception.message ?: "")
        logger.debug(exception.stackTraceToString())

        jgitClone(gitRepositoryURI, cloneDirectory)
    }
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

private fun jgitClone(gitRepositoryURI: String, cloneDirectory: File) {
    val preservedConnectionFactory: HttpConnectionFactory = HttpTransport.getConnectionFactory()

    try {
        HttpTransport.setConnectionFactory(InsecureHttpConnectionFactory())

        val evaluatedGitRepoURI = evaluateEnvVariablesInGitRepoURI(gitRepositoryURI, System.getenv())

        val cloneCommand = Git.cloneRepository().apply {
            setTransportConfigCallback(getTransportCallingCallback())
            setURI(evaluatedGitRepoURI)
            setDirectory(cloneDirectory)
        }

        val accessTokenText = getPersonalAccessToken()

        if (accessTokenText != null) {
            val credentialsProvider: CredentialsProvider = UsernamePasswordCredentialsProvider(accessTokenText, "")
            cloneCommand.setCredentialsProvider(credentialsProvider)
        } else {
            val ciBearerToken = getBearerToken()

            if (ciBearerToken != null) {
                cloneCommand.setTransportConfigCallback(getTransportCallingCallback(ciBearerToken.encodeOAuth()))
            }
        }

        logger.log("Cloning: $gitRepositoryURI -> ${cloneDirectory.canonicalPath}")

        cloneCommand.call()
    } finally {
        HttpTransport.setConnectionFactory(preservedConnectionFactory)
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

fun getBearerToken(): String? {
    val specmaticConfigFile = File(getConfigFilePath())

    return when {
        specmaticConfigFile.exists() ->
            readConfig(specmaticConfigFile).let { config ->
                readBearerFromEnvVariable(config) ?: readBearerFromFile(config)
            }
        else -> null.also {
            logger.log("Returning bearer token as null since Specmatic configuration file is not found.")
            logger.log("Current working directory is ${File(".").absolutePath}")
        }
    }
}

private fun readBearerFromEnvVariable(config: Value): String? {
    return loadFromPath(config, listOf("auth", "bearer-environment-variable"))?.toStringLiteral()?.let { bearerName ->
        logger.log("Found bearer environment variable name \"$bearerName\"")

        System.getenv(bearerName).also {
            if(it == null)
                logger.log("$bearerName environment variable has not been set")
        }
    }
}

private fun readBearerFromFile(config: Value): String? {
    return loadFromPath(config, listOf("auth", "bearer-file"))?.toStringLiteral()?.let { bearerFileName ->
        logger.log("Found bearer file name $bearerFileName")

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

fun getPersonalAccessToken(): String? {
    return getPersonalAccessTokenProperty() ?: getPersonalAccessTokenEnvVariable() ?: getPersonalAccessTokenConfig()
}

private fun getPersonalAccessTokenConfig(): String? {
    val homeDir = File(System.getProperty("user.home"))
    val configFile = homeDir.resolve("specmatic-azure.json")

    if (configFile.exists()) {
        val config = readConfig(configFile)

        "azure-access-token".let { azureAccessTokenKey ->
            if (config is JSONObjectValue && config.jsonObject.containsKey(azureAccessTokenKey)) {
                return config.getString(azureAccessTokenKey).also {
                    println("Using personal access token from home directory config")
                }
            }
        }

        "personal-access-token".let { azureAccessTokenKey ->
            if (config is JSONObjectValue && config.jsonObject.containsKey(azureAccessTokenKey)) {
                return config.getString(azureAccessTokenKey).also {
                    println("Using personal access token from home directory config")
                }
            }
        }
    }

    return null
}

private fun getPersonalAccessTokenEnvVariable(): String? {
    val environmentVariableName = "PERSONAL_ACCESS_TOKEN"

    return System.getenv(environmentVariableName)?.also {
        println("Using personal access token from environment variable")
    }
}

private fun getPersonalAccessTokenProperty(): String? {
    val accessTokenVariableName = "personalAccessToken"

    return System.getProperty(accessTokenVariableName)?.also {
        println("Using personal access token from property")
    }
}

private fun readConfig(configFile: File): Value {
    try {
        val config = loadSpecmaticConfig(configFile.path)
        return parsedJSON(ObjectMapper().writeValueAsString(config))
    } catch(e: Throwable) {
        throw ContractException("Error loading Specmatic configuration: ${e.message}")
    }
}
