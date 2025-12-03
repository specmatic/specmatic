package application.backwardCompatibility

import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand.ProcessedSpec
import io.specmatic.core.Results
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse

interface BackwardCompatibilityCheckHook {
    fun check(
        backwardCompatibilityResult: Results,
        centralRepoUrl: String,
        specFilePath: String,
    ): Pair<CompatibilityResult, List<OperationUsageResponse>?>

    fun logStartedMessage(failedSpecs: List<ProcessedSpec>)

    fun logCompletedMessage()

    fun failedVerdictAndMessage(processedSpec: ProcessedSpec, strictMode: Boolean): Pair<CompatibilityResult, String>
}
