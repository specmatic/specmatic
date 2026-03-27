package io.specmatic.core.git

import io.ktor.http.encodeOAuth
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.getTransportCallingCallback
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import java.io.File

internal class JGitCloneCmd(
    private val cloneCommandFactory: () -> CloneCommand = { Git.cloneRepository() },
    private val environmentVariables: Map<String, String> = System.getenv(),
    private val httpTransportFactoryManager: HttpTransportFactoryManager = DefaultHttpTransportFactoryManager
) {
    fun execute(gitRepositoryURI: String, cloneDirectory: File, specmaticConfig: SpecmaticConfig) {
        val preservedConnectionFactory = httpTransportFactoryManager.getConnectionFactory()

        try {
            httpTransportFactoryManager.setConnectionFactory(InsecureHttpConnectionFactory())

            val evaluatedGitRepoURI = evaluateEnvVariablesInGitRepoURI(gitRepositoryURI, environmentVariables)

            val cloneCommand = cloneCommandFactory().apply {
                setTransportConfigCallback(getTransportCallingCallback())
                setURI(evaluatedGitRepoURI)
                setDirectory(cloneDirectory)
            }

            val accessTokenText = getPersonalAccessToken(specmaticConfig, gitRepositoryURI)

            if (accessTokenText != null) {
                val credentialsProvider: CredentialsProvider = UsernamePasswordCredentialsProvider(accessTokenText, "")
                cloneCommand.setCredentialsProvider(credentialsProvider)
            } else {
                val ciBearerToken = getBearerToken(specmaticConfig, gitRepositoryURI)

                if (ciBearerToken != null) {
                    cloneCommand.setTransportConfigCallback(getTransportCallingCallback(ciBearerToken.encodeOAuth()))
                }
            }

            logger.log("Cloning: $gitRepositoryURI -> ${cloneDirectory.canonicalPath}")

            cloneCommand.call()
        } finally {
            httpTransportFactoryManager.setConnectionFactory(preservedConnectionFactory)
        }
    }
}

internal interface HttpTransportFactoryManager {
    fun getConnectionFactory(): HttpConnectionFactory
    fun setConnectionFactory(connectionFactory: HttpConnectionFactory)
}

internal object DefaultHttpTransportFactoryManager : HttpTransportFactoryManager {
    override fun getConnectionFactory(): HttpConnectionFactory = HttpTransport.getConnectionFactory()

    override fun setConnectionFactory(connectionFactory: HttpConnectionFactory) {
        HttpTransport.setConnectionFactory(connectionFactory)
    }
}
