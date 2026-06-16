package application.backwardCompatibility

import io.specmatic.core.Results
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord

data class ProcessedSpec(
    val specFilePath: String,
    val backwardCompatibilityResult: Results,
    val compatibilityLogOutput: String = "",
    val externalisedExamples: ProcessedExternalisedExamples = ProcessedExternalisedExamples(),
    val isChangedSpec: Boolean = false,
    val ownsChangedExternalisedExamples: Boolean = false,
    val precomputedCompatibilityResult: CompatibilityResult,
    val computedCompatibilityCheckHookResult: Pair<CompatibilityResult, List<OperationUsageResponse>?> = Pair(
        CompatibilityResult.UNKNOWN, emptyList()
    ),
    val isNewFile: Boolean,
    val reportRecords: List<CtrfBackwardCompatibilityRecord> = emptyList()
)
