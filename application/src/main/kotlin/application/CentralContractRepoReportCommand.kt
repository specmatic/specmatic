package application

import `in`.specmatic.core.log.logger
import `in`.specmatic.core.utilities.saveJsonFile
import `in`.specmatic.reports.CentralContractRepoReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "central-contract-repo-report",
    mixinStandardHelpOptions = true,
    description = ["Generate the Central Contract Repo Report"]
)
class CentralContractRepoReportCommand : Callable<Unit> {

    companion object {
        const val REPORT_PATH = "./build/reports/specmatic"
        const val REPORT_FILE_NAME = "central_contract_repo_report.json"
    }

    override fun call() {
        val report = CentralContractRepoReport().generate()
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