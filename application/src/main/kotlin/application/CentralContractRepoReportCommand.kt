package application

import io.specmatic.core.log.logger
import io.specmatic.core.utilities.saveJsonFile
import io.specmatic.license.core.cli.Category
import io.specmatic.reports.CentralContractRepoReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "central-contract-repo-report",
    mixinStandardHelpOptions = true,
    description = ["Generate the Central Contract Repo Report"]
)
@Category("Specmatic core")
class CentralContractRepoReportCommand : Callable<Unit> {

    companion object {
        const val REPORT_PATH = "./build/reports/specmatic"
        const val REPORT_FILE_NAME = "central_contract_repo_report.json"
    }

    @CommandLine.Option(names = ["--baseDir"], description = ["Directory to treated as the root for API specifications"], defaultValue = "")
    lateinit var baseDir: String

    override fun call() {
        val report = CentralContractRepoReport().generate(baseDir)
        if(report.specifications.isEmpty()) {
            logger.log("No specifications found, hence the Central Contract Repo Report has not been generated.")
        }
        else {
            logger.log("Saving Central Contract Repo Report json to $REPORT_PATH ...")
            val json = Json {
                encodeDefaults = false
            }
            val reportJson = json.encodeToString(report)
            saveJsonFile(reportJson, REPORT_PATH, REPORT_FILE_NAME)
        }
    }
}
