package application

import io.specmatic.core.*
import io.specmatic.core.config.v2.invalidContractExtensionMessage
import io.specmatic.core.log.logger
import io.specmatic.core.log.logException
import io.specmatic.core.pattern.ContractException
import io.specmatic.license.core.cli.Category
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(name = "compare",
        mixinStandardHelpOptions = true,
        description = [
"""
Checks if two contracts are equivalent.
DEPRECATED: This command will be removed in the next major release. Use 'backward-compatibility-check' command instead.
"""
        ])
@Category("Specmatic core")
class CompareCommand : Callable<Unit> {
    @Parameters(index = "0", description = ["Older contract file path"])
    lateinit var olderContractFilePath: String

    @Parameters(index = "1", description = ["Newer contract file path"])
    lateinit var newerContractFilePath: String

    @Option(names = ["--mirror"], required = false)
    var mirror: Boolean = false

    override fun call() {
        if(!olderContractFilePath.isContractFile()) {
            logger.log(invalidContractExtensionMessage(olderContractFilePath))
            exitProcess(1)
        }

        if(!newerContractFilePath.isContractFile()) {
            logger.log(invalidContractExtensionMessage(newerContractFilePath))
            exitProcess(1)
        }

        val exitCode = logException {
            val olderContract = olderContractFilePath.loadContract()
            val newerContract = newerContractFilePath.loadContract()

            if(mirror)
                logger.log("Comparing older with newer...")
            val report = backwardCompatible(olderContract, newerContract)
            println(report.message())

            if(!mirror)
                exitProcess(report.exitCode)

            logger.newLine()
            logger.log("Comparing newer with older...")
            val mirrorReport = backwardCompatible(newerContract, olderContract)
            println(mirrorReport.message())
        }

        exitProcess(exitCode)
    }
}

private fun String.isContractFile(): Boolean {
    return File(this).extension in CONTRACT_EXTENSIONS
}

private fun String.loadContract(): Feature {
    if(!this.isContractFile())
        throw ContractException(invalidContractExtensionMessage(this))

    return parseContractFileToFeature(File(this))
}

fun backwardCompatible(olderContract: Feature, newerContract: Feature): CompatibilityReport =
        try {
            testBackwardCompatibility(olderContract, newerContract).let { results ->
                when {
                    results.failureCount > 0 -> {
                        IncompatibleReport(results)
                    }
                    else -> CompatibleReport()
                }
            }
        } catch(e: ContractException) {
            ContractExceptionReport(e)
        } catch(e: Throwable) {
            ExceptionReport(e)
        }
