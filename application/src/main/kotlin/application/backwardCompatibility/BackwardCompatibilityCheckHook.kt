package application.backwardCompatibility

import io.specmatic.core.Results

interface BackwardCompatibilityCheckHook {
    fun check(
        backwardCompatibilityResult: Results,
        centralRepoUrl: String,
        specFilePath: String,
    ): CompatibilityResult = CompatibilityResult.FAILED
}
