package application.backwardCompatibility

import io.specmatic.core.Feature
import io.specmatic.core.IFeature
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.generateBackwardCompatibilityReport
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.logger
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ServiceLoader
import java.util.concurrent.Callable
import java.util.regex.Pattern
import kotlin.collections.ArrayDeque
import kotlin.io.path.absolutePathString

abstract class BackwardCompatibilityCheckBaseCommand(
    @field:picocli.CommandLine.Mixin
    val options: BackwardCompatibilityCheckOptions = BackwardCompatibilityCheckOptions()
): Callable<Int> {
    protected val specmaticConfig: SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    protected val backwardCompConfig = specmaticConfig.getBackwardCompatibilityConfig()
    protected val effectiveRepoDir: String by lazy { options.repoDir ?: backwardCompConfig?.repoDirectory ?: "." }
    protected val gitCommand: GitCommand by lazy { SystemGit(workingDirectory = Paths.get(effectiveRepoDir).absolutePathString()) }
    protected val effectiveBaseBranch: String by lazy { options.baseBranch ?: backwardCompConfig?.baseBranch ?: gitCommand.currentRemoteBranch() }
    protected val effectiveTargetPath: String by lazy { options.targetPath ?: backwardCompConfig?.targetPath.orEmpty() }
    protected val effectiveStrictMode: Boolean by lazy { options.strictMode ?: backwardCompConfig?.strictMode ?: false }

    val hook = ServiceLoader.load(BackwardCompatibilityCheckHook::class.java).firstOrNull()

    private val backwardCompatibilityLogger = BackwardCompatibilityCheckLogger()

    private var areLocalChangesStashed = false

    abstract fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): BackwardCompatibilityCheckResult
    abstract fun File.isValidFileFormat(): Boolean
    abstract fun File.isValidSpec(): Boolean
    abstract fun File.isExternalisedExample(): Boolean
    abstract fun getFeatureFromSpecPath(path: String): IFeature

    abstract fun getSpecsOfChangedExternalisedExamples(
        filesChangedInCurrentBranch: Set<String>
    ): Set<String>

    open fun regexForMatchingReferred(schemaFileName: String): String = ""
    open fun areExamplesValid(feature: IFeature, which: String): Boolean = true
    open fun getUnusedExamples(feature: IFeature): Set<String> = emptySet()
    open fun validateExamples(feature: IFeature, paths: Set<Path>): List<ExampleValidationResult> = emptyList()
    open fun exampleValidationReportRecords(
        feature: IFeature,
        exampleValidationResults: List<ExampleValidationResult>
    ): List<CtrfBackwardCompatibilityRecord> = emptyList()

    final override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = options.debugLog))
        addShutdownHook()

        val changedFiles = getChangedFilesInCurrentBranch()
        val untrackedFiles = getUntrackedFiles()
        if (changedFiles.all.isEmpty() && untrackedFiles.isEmpty()) {
            val newLine = System.lineSeparator()
            logger.log("$newLine No specs were changed, skipping the check.$newLine")
            return 0
        }

        val externalisedExamplesToValidate = getExternalisedExamplesToValidate(changedFiles)
        val specsToCheck = getSpecsToCheck(changedFiles, untrackedFiles, externalisedExamplesToValidate)
        if (specsToCheck.isEmpty()) {
            logger.log(CompatibilityReport.emptyReport())
            return 0
        }

        val result = try {
            runBackwardCompatibilityCheckFor(specsToCheck, externalisedExamplesToValidate, effectiveBaseBranch)
        } catch (e: Throwable) {
            logger.newLine()
            logger.newLine()
            logger.log(e)
            return 1
        }

        logger.log(result.report)
        return result.exitCode
    }

    private fun getUntrackedFiles(): Set<String> = gitCommand.getUntrackedFiles().filter {
        it.contains(Paths.get(effectiveTargetPath).toString()) && File(it).isValidSpec() && getSpecsReferringTo(setOf(it)).isEmpty()
    }.toSet()

    private fun getSpecsToCheck(
        changedFiles: ChangedFiles,
        untrackedFiles: Set<String>,
        externalisedExamplesToValidate: List<ExternalisedExamplesToValidate>
    ): Set<Path> {
        val specsReferringToChangedFiles = getSpecsReferringTo(changedFiles.specs)
            .filter { File(it).isValidSpec() }
            .toSet()

        val specsAssociatedWithChangedExternalisedExamples = externalisedExamplesToValidate
            .map { it.specPath.toString() }
            .toSet()

        backwardCompatibilityLogger.logFilesToBeCheckedForBackwardCompatibility(
            changedFiles,
            specsReferringToChangedFiles,
            specsAssociatedWithChangedExternalisedExamples,
            untrackedFiles
        )

        return (changedFiles.specs + specsReferringToChangedFiles + specsAssociatedWithChangedExternalisedExamples)
            .map { File(it).canonicalFile.toPath() }
            .toSet()
    }

    private fun getExternalisedExamplesToValidate(changedFiles: ChangedFiles): List<ExternalisedExamplesToValidate> {
        return changedFiles.externalisedExamples.flatMap { externalisedExamplePath ->
            getSpecsOfChangedExternalisedExamples(setOf(externalisedExamplePath)).mapNotNull { specPath ->
                val specFile = File(specPath)
                if (specFile.exists() && specFile.isValidSpec().not()) null
                else specFile.canonicalFile.toPath() to File(externalisedExamplePath).canonicalFile.toPath()
            }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        ).map { (specPath, paths) ->
            ExternalisedExamplesToValidate(specPath, paths.toSet())
        }
    }

    private fun getChangedFilesInCurrentBranch(): ChangedFiles {
        val changedFiles = gitCommand.getFilesChangedInCurrentBranch(
            effectiveBaseBranch
        ).filter {
            File(it).exists() && File(it).isValidFileFormat()
        }.filter {
            it.contains(Paths.get(effectiveTargetPath).toString())
        }.toSet()

        val externalisedExamples = changedFiles.filter { File(it).isExternalisedExample() }.toSet()
        val specs = changedFiles.filter { it !in externalisedExamples && File(it).isValidSpec() }.toSet()

        return ChangedFiles(
            specs = specs,
            externalisedExamples = externalisedExamples
        )
    }

    open fun getSpecsReferringTo(specFilePaths: Set<String>): Set<String> {
        if (specFilePaths.isEmpty()) return emptySet()
        val specFiles = specFilePaths.map { File(it) }
        val allSpecFileContent = allSpecFiles().associateWith { it.readText() }

        val referringSpecsSoFar = mutableSetOf<File>()
        val queue = ArrayDeque(specFiles)

        while (queue.isNotEmpty()) {
            val combinedPattern = Pattern.compile(
                queue.toSet().joinToString(prefix = "\\b(?:", separator = "|", postfix = ")\\b") { specFile ->
                    regexForMatchingReferred(specFile.name).let { Regex.escape(it) }
                })

            queue.clear()

            val referringSpecs = allSpecFileContent.entries.filter { (specFile, content) ->
                specFile !in referringSpecsSoFar && combinedPattern.matcher(content).find()
            }.map {
                it.key
            }.filter { referringSpecFile ->
                referringSpecsSoFar.add(referringSpecFile)
            }

            queue.addAll(referringSpecs)
        }

        return referringSpecsSoFar.filter {
            it !in specFiles
        }.map {
            it.canonicalPath
        }.toSet()
    }

    internal fun allSpecFiles(): List<File> {
        return File(effectiveRepoDir).walk().toList().filterNot {
            ".git" in it.path
        }.filter { it.isFile && it.isValidFileFormat() }
    }

    private fun getCurrentBranch(): String {
        val branchWithChanges = gitCommand.currentBranch()
        return if (branchWithChanges == HEAD) gitCommand.detachedHEAD() else branchWithChanges
    }

    private fun runBackwardCompatibilityCheckFor(
        specsToCheck: Set<Path>,
        externalisedExamplesToValidate: List<ExternalisedExamplesToValidate>,
        baseBranch: String
    ): CompatibilityReport {
        val treeishWithChanges = getCurrentBranch()
        val reportStartTime = System.currentTimeMillis()

        try {
            // FIRST PASS: collect results without logging. This includes reading newer/older features and running the lightweight compatibility check.
            val processedSpecs = specsToCheck.mapNotNull { specPath ->
                runBackwardCompatibilityCheckForSpec(specPath, baseBranch, treeishWithChanges)
            }

            // SECOND PASS: for all specs that failed the compatibility check, call the potentially long-running ServiceLoader hooks in batches of 5
            val specsValidatedByHook =
                BackwardCompatibilityCheckHookValidator(hook, gitCommand, effectiveRepoDir).validate(processedSpecs)

            // THIRD PASS: validate changed externalised examples for compatible specs.
            val examplesToValidateBySpec = externalisedExamplesToValidate.associateBy { it.specPath }
            val specsWithValidatedExamples = specsValidatedByHook.map { processedSpec ->
                validateChangedExternalisedExamplesIfApplicable(
                    processedSpec, examplesToValidateBySpec[Paths.get(processedSpec.specFilePath)]
                )
            }

            // FOURTH PASS: do the actual logging and produce final CompatibilityResult list.
            val results = specsWithValidatedExamples.mapIndexed { index, processedSpec ->
                logCompatibilityResultAndReturn(index, processedSpec)
            }

            generateBackwardCompatibilityReport(
                specsWithValidatedExamples.flatMap { it.reportRecords },
                reportStartTime,
                System.currentTimeMillis()
            )

            return CompatibilityReport(results)
        } finally {
            gitCommand.checkout(treeishWithChanges)
        }
    }

    private fun logCompatibilityResultAndReturn(index: Int, processedSpec: ProcessedSpec): CompatibilityResult {
        backwardCompatibilityLogger.logCheckStart(index, processedSpec)

        if (processedSpec.isNewFile) {
            backwardCompatibilityLogger.logNewFile(processedSpec)
            backwardCompatibilityLogger.logChangedExternalisedExampleValidation(processedSpec)
            return processedSpec.exampleValidationResults.toCompatibilityResult()
        }

        if (processedSpec.backwardCompatibilityResult.successExcludingIgnorableFailures().not()) {
            val (result, verdictMessage) = failedVerdictMessage(processedSpec, hook, effectiveStrictMode, effectiveBaseBranch)
            backwardCompatibilityLogger.logIncompatibleSpec(processedSpec, verdictMessage)
            return result
        }

        backwardCompatibilityLogger.logWipScenarios(processedSpec.backwardCompatibilityResult)
        val exampleValidationStatus = BCCExampleValidationStatus(
            areExamplesInvalid = areExamplesValid(processedSpec.newer, "newer").not(),
            hasUnloadableExamples = processedSpec.unusedExamples.isNotEmpty(),
            hasInvalidChangedExternalisedExamples = processedSpec.exampleValidationResults.hasCompleteFailures()
        )
        val (result, verdictMessage) = backwardCompatibleVerdict(exampleValidationStatus)
        backwardCompatibilityLogger.logBackwardCompatibleSpec(
            processedSpec = processedSpec,
            exampleValidationStatus = exampleValidationStatus,
            verdictMessage = verdictMessage
        )

        return result
    }

    private fun backwardCompatibleVerdict(exampleValidationStatus: BCCExampleValidationStatus): Pair<CompatibilityResult, String> {
        return if (exampleValidationStatus.hasErrors) {
            CompatibilityResult.FAILED to
                "(INCOMPATIBLE) The spec is backward compatible but the examples are NOT backward compatible or are INVALID."
        } else {
            CompatibilityResult.PASSED to
                "(COMPATIBLE) The spec is backward compatible with the corresponding spec from $effectiveBaseBranch"
        }
    }

    private fun runBackwardCompatibilityCheckForSpec(
        specPath: Path,
        baseBranch: String,
        treeishWithChanges: String
    ): ProcessedSpec? {
        try {
            val specFilePath = specPath.toString()
            if (with(File(specFilePath)) { exists() && isValidSpec().not() }) {
                // skip non-spec files
                return null
            }

            val newer = getFeatureFromSpecPath(specFilePath)
            // The newer feature is parsed while the worktree has the current branch files, but below we
            // checkout the base branch before running the comparison. OpenAPI change tracking resolves
            // external refs when scenariosForChangeTracking() is first evaluated, so delaying this until
            // after checkout races with the branch switch and can build the newer fingerprints from the
            // base branch's external files. Materialize it here while the current branch is still checked out.
            (newer as? Feature)?.scenariosForChangeTracking()
            val unusedExamples = getUnusedExamples(newer)

            val repoDirFile = File(effectiveRepoDir).absoluteFile
            val olderFileExists =
                gitCommand.exists(baseBranch, File(specFilePath).relativeTo(repoDirFile).invariantSeparatorsPath)

            if (!olderFileExists) {
                // new file: mark as passed immediately
                return ProcessedSpec(
                    specFilePath = specFilePath,
                    backwardCompatibilityResult = Results(),
                    newer = newer,
                    unusedExamples = unusedExamples,
                    precomputedCompatibilityResult = CompatibilityResult.PASSED,
                    isNewFile = true
                )
            }

            areLocalChangesStashed = gitCommand.stash()
            gitCommand.checkout(baseBranch)

            val older = getFeatureFromSpecPath(specFilePath)

            val checkResult = checkBackwardCompatibility(older, newer)
            val backwardCompatibilityResult = checkResult.results
            val result =
                if (backwardCompatibilityResult.successExcludingIgnorableFailures()) CompatibilityResult.PASSED else CompatibilityResult.FAILED
            LicenseResolver.utilize(
                product = LicensedProduct.OPEN_SOURCE,
                feature = SpecmaticFeature.BACKWARD_COMPATIBILITY_CHECK,
                protocol = listOfNotNull((older as? Feature)?.protocol)
            )

            return ProcessedSpec(
                specFilePath = specFilePath,
                backwardCompatibilityResult = backwardCompatibilityResult,
                newer = newer,
                unusedExamples = unusedExamples,
                precomputedCompatibilityResult = result,
                isNewFile = false,
                reportRecords = checkResult.reportRecords
            )
        } finally {
            gitCommand.checkout(treeishWithChanges)
            if (areLocalChangesStashed) {
                gitCommand.stashPop()
                areLocalChangesStashed = false
            }
        }
    }

    private fun addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                runCatching {
                    gitCommand.checkout(getCurrentBranch())
                    if (areLocalChangesStashed) gitCommand.stashPop()
                }
            }
        })
    }

    private fun validateChangedExternalisedExamplesIfApplicable(
        processedSpec: ProcessedSpec,
        examplesToValidate: ExternalisedExamplesToValidate?
    ): ProcessedSpec {
        if (examplesToValidate == null) return processedSpec
        if (processedSpec.isNewFile.not() && processedSpec.backwardCompatibilityResult.successExcludingIgnorableFailures().not()) {
            return processedSpec
        }

        val exampleValidationResults = validateExamples(
            feature = processedSpec.newer,
            paths = examplesToValidate.externalisedExamplePaths
        )

        return processedSpec.copy(
            exampleValidationResults = exampleValidationResults,
            reportRecords = processedSpec.reportRecords + exampleValidationReportRecords(
                feature = processedSpec.newer,
                exampleValidationResults = exampleValidationResults
            )
        )
    }

    internal data class ChangedFiles(
        val specs: Set<String>,
        val externalisedExamples: Set<String>
    ) {
        val all: Set<String>
            get() = specs + externalisedExamples
    }

    data class BackwardCompatibilityCheckResult(
        val results: Results,
        val reportRecords: List<CtrfBackwardCompatibilityRecord> = emptyList()
    )

    data class ProcessedSpec(
        val specFilePath: String,
        val backwardCompatibilityResult: Results,
        val newer: IFeature,
        val unusedExamples: Set<String>,
        val precomputedCompatibilityResult: CompatibilityResult,
        val computedCompatibilityCheckHookResult: Pair<CompatibilityResult, List<OperationUsageResponse>?> = Pair(
            CompatibilityResult.UNKNOWN, emptyList()
        ),
        val isNewFile: Boolean,
        val exampleValidationResults: List<ExampleValidationResult> = emptyList(),
        val reportRecords: List<CtrfBackwardCompatibilityRecord> = emptyList()
    )

    data class ExternalisedExamplesToValidate(
        val specPath: Path,
        val externalisedExamplePaths: Set<Path>
    )

    data class ExampleValidationResult(
        val examplePath: Path,
        val result: Result
    )

    companion object {
        private const val HEAD = "HEAD"

        fun failedVerdictMessage(
            processedSpec: ProcessedSpec, hook: BackwardCompatibilityCheckHook?, strictMode: Boolean, baseBranch: String
        ): Pair<CompatibilityResult, String> {
            val defaultMessage =
                "(INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from $baseBranch"

            return hook?.failedVerdictAndMessage(processedSpec, strictMode)
                ?: Pair(processedSpec.precomputedCompatibilityResult, defaultMessage)
        }
    }
}

private fun List<BackwardCompatibilityCheckBaseCommand.ExampleValidationResult>.hasCompleteFailures() =
    any { it.result is Result.Failure && !it.result.isPartialFailure() }

private fun List<BackwardCompatibilityCheckBaseCommand.ExampleValidationResult>.toCompatibilityResult() =
    if (hasCompleteFailures()) CompatibilityResult.FAILED else CompatibilityResult.PASSED
