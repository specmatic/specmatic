package application

import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.log.logException
import io.specmatic.core.pattern.ContractException
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(name = "similar",
        mixinStandardHelpOptions = true,
        description = [
"""
Show the difference between two contracts.
DEPRECATED: This command will be removed in the next major release. Use 'backward-compatibility-check' command instead.
"""
        ]
)
class DifferenceCommand : Callable<Unit> {
    @Parameters(index = "0", description = ["Older contract file path"])
    lateinit var olderContractFilePath: String

    @Parameters(index = "1", description = ["Newer contract file path"])
    lateinit var newerContractFilePath: String

    override fun call() {
        if(!olderContractFilePath.isContractFile()) {
            logger.log(invalidContractExtensionMessage(olderContractFilePath))
            exitProcess(1)
        }

        if(!newerContractFilePath.isContractFile()) {
            logger.log(invalidContractExtensionMessage(newerContractFilePath))
            exitProcess(1)
        }

        logException {
            val olderContract = olderContractFilePath.loadContract()
            val newerContract = newerContractFilePath.loadContract()

            val report = difference(olderContract, newerContract)
            println(report.message())
            exitProcess(report.exitCode)
        }
    }
}

fun difference(olderContract: Feature, newerContract: Feature): CompatibilityReport =
        try {
            findDifferences(olderContract, newerContract).let { results ->
                when {
                    results.failureCount > 0 -> {
                        IncompatibleReport(results, "The two contracts are not similar.")
                    }
                    else -> CompatibleReport("The two contracts are similar.")
                }
            }
        } catch(e: ContractException) {
            ContractExceptionReport(e)
        } catch(e: Throwable) {
            ExceptionReport(e)
        }

