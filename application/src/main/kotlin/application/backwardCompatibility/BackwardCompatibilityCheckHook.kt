package application.backwardCompatibility

import io.specmatic.core.Results
import io.specmatic.core.log.LogStrategy

interface BackwardCompatibilityCheckHook {
    fun check(
        logger: LogStrategy,
        backwardCompatibilityResult: Results,
        centralRepoUrl: String,
        specFilePath: String,
        indent: String
    ): CompatibilityResult = CompatibilityResult.FAILED
}
