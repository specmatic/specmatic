package application

import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.log.logException
import io.specmatic.core.pattern.ContractException
import io.specmatic.license.core.cli.Category
import io.specmatic.reporter.commands.GitReportDefaultValueProvider
import io.specmatic.reporter.commands.InsightsReportOptions
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(name = "compare",
        mixinStandardHelpOptions = true,
        description = [
"""
Checks if two contracts are equivalent.
DEPRECATED: This command will be removed in the next major release. Use 'backward-compatibility-check' command instead.
"""
        ],
        defaultValueProvider = GitReportDefaultValueProvider::class
)
@Category("Specmatic core")
class CompareCommand : Callable<Unit> {
    @Parameters(index = "0", description = ["Older contract file path"])
    lateinit var olderContractFilePath: String

    @Parameters(index = "1", description = ["Newer contract file path"])
    lateinit var newerContractFilePath: String

    @Option(names = ["--mirror"], required = false)
    var mirror: Boolean = false

    @field:ArgGroup(exclusive = false, heading = "%nInsights reporting options:%n")
    val insightsReportOptions = InsightsReportOptions()

    override fun call() {
        insightsReportOptions.validate()
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
            val report = backwardCompatible(olderContract, newerContract, insightsReportOptions)
            println(report.message())

            if(!mirror)
                exitProcess(report.exitCode)

            logger.newLine()
            logger.log("Comparing newer with older...")
            val mirrorReport = backwardCompatible(newerContract, olderContract, insightsReportOptions)
            println(mirrorReport.message())
        }

        exitProcess(exitCode)
    }
}

fun backwardCompatible(olderContract: Feature, newerContract: Feature, insightsReportOptions: InsightsReportOptions): CompatibilityReport =
        try {
            testBackwardCompatibility(olderContract, newerContract, insightsReportOptions).let { results ->
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
