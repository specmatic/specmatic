package application.backwardCompatibility

import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand.ProcessedSpec
import io.specmatic.core.git.GitCommand
import io.specmatic.core.log.logger
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class BackwardCompatibilityCheckHookValidator(
    private val hook: BackwardCompatibilityCheckHook?,
    private val gitCommand: GitCommand,
    private val repoDir: String
) {
    fun validate(processedSpecs: List<ProcessedSpec>): List<ProcessedSpec> {
        val failedSpecs = processedSpecs.filter { it.backwardCompatibilityResult.successExcludingIgnorableFailures().not() }
        val hook = hook ?: return processedSpecs

        if (failedSpecs.isEmpty()) return processedSpecs

        hook.logStartedMessage(failedSpecs)
        val executor = Executors.newFixedThreadPool(POOL_SIZE)

        try {
            val repoDirFile = File(repoDir).absoluteFile
            val futures = failedSpecs.map { processed ->
                processed to executor.submit(Callable {
                    try {
                        hook.check(
                            processed.backwardCompatibilityResult,
                            gitCommand.getRemoteUrl(),
                            File(processed.specFilePath).relativeTo(repoDirFile).invariantSeparatorsPath
                        )
                    } catch (e: Throwable) {
                        logger.log(e)
                        unknownResult
                    }
                })
            }

            val hookResultsBySpec = futures.associate { (processed, future) ->
                processed.specFilePath to try {
                    future.get()
                } catch (e: Throwable) {
                    logger.log(e)
                    unknownResult
                }
            }

            return processedSpecs.map { processed ->
                hookResultsBySpec[processed.specFilePath]
                    ?.let { processed.copy(computedCompatibilityCheckHookResult = it) }
                    ?: processed
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            hook.logCompletedMessage()
        }
    }

    private companion object {
        const val POOL_SIZE = 5
        val unknownResult = Pair<CompatibilityResult, List<OperationUsageResponse>?>(CompatibilityResult.UNKNOWN, emptyList())
    }
}
