package application

import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.azure.PersonalAccessToken
import io.specmatic.core.git.getPersonalAccessToken
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.exitWithMessage
import picocli.CommandLine
import picocli.CommandLine.Option
import java.util.concurrent.Callable

private const val AZURE = "azure"

@CommandLine.Command(name = "graph",
    mixinStandardHelpOptions = true,
    description = ["Dependency graph"])
class GraphCommand: Callable<Unit> {
    @CommandLine.Command(
        name = "consumers",
        description = ["Display a list of services depending on contracts in this repo"]
    )
    fun consumers(
        @Option(names = ["--verbose"], description = ["Print verbose logs"]) verbose: Boolean = false,
        @Option(
            names = ["--azureBaseURL"],
            description = ["Azure base URL"],
            required = true
        ) azureBaseURL: String
    ) {
        if (verbose)
            logger = Verbose(CompositePrinter())

        val specmaticConfig = loadSpecmaticConfig()

        val azureAuthToken = PersonalAccessToken(
            getPersonalAccessToken() ?: throw ContractException(
                "Access token not found, put it in ${
                    System.getProperty(
                        "user.home"
                    )
                }/specmatic.json"
            )
        )

        val exitMessage = """specmatic.json needs to contain a the repository information, as below:
                    |{
                    |  "repository": {
                    |    "provider": "azure"
                    |    "collectionName": "NameOfTheCollectionContainingThisProject"
                    |  }
                    |}
                """.trimMargin()

        val repositoryProvider = specmaticConfig.getRepositoryProvider() ?: exitWithMessage(exitMessage)
        if(repositoryProvider != AZURE) {
            exitWithMessage(exitMessage)
        }

        val collection = specmaticConfig.getRepositoryCollectionName()
            ?: exitWithMessage(
                """specmatic.json needs to contain a the repository information, as below:
                    |{
                    |  "repository": {
                    |    "provider": "azure"
                    |    "collectionName": "NameOfTheCollectionContainingThisProject"
                    |  }
                    |}
                """.trimMargin()
            )

        val azure = AzureAPI(azureAuthToken, azureBaseURL, collection)

        logger.log("Dependency projects")
        logger.log("-------------------")

        specmaticConfig.sources.forEach { source ->
            logger.log("In central repo ${source.repository}")

            source.test?.forEach { relativeContractPath ->
                logger.log("  Consumers of $relativeContractPath")
                val consumers = azure.referencesToContract(relativeContractPath)

                if (consumers.isEmpty()) {
                    logger.log("    ** no consumers found **")
                } else {
                    consumers.forEach {
                        logger.log("  - ${it.description}")
                    }
                }

                logger.newLine()
            }
        }
    }

    override fun call() {
    }

}