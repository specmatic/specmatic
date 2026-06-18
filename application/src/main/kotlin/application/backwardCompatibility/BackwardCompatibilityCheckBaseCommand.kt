package application.backwardCompatibility

import io.specmatic.core.Feature
import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.examples.module.ValidationResults
import io.specmatic.core.generateBackwardCompatibilityReport
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.logger
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogPrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.ThreadSafeLog
import io.specmatic.core.log.withLogger
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Paths
import java.util.ServiceLoader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.collections.ArrayDeque
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

class BackwardCompatibilityCheckOptions {
    @Option(
        names = ["--base-branch"],
        description = ["Base branch to compare the changes against", "Default value is the local origin HEAD of the current branch"],
        required = false
    )
    var baseBranch: String? = null

    @Option(
        names = ["--target-path"],
        description = ["Specify the file or directory to limit the backward compatibility check scope. If omitted, all changed files will be checked."],
        required = false
    )
    var targetPath: String? = null

    @Option(
        names = ["--repo-dir"],
        description = ["The directory of the repository in which to run the backward compatibility check.", "If not provided, the check will run in the current working directory."],
        required = false
    )
    var repoDir: String? = null

    @Option(names = ["--debug"], description = ["Write verbose logs to console for debugging"])
    var debugLog: Boolean? = null

    @Option(
        names = ["--strict"],
        description = [
            "In strict mode, irrespective of the API's usage, if a change to an API breaks backward compatibility then it would result in a failure.",
            "When this flag is not specified, backward breaking changes to APIs that have no usages will only generate warnings and won't cause the check to fail.",
            "This flag is only applicable when using Specmatic Insights.",
        ]
    )
    var strictMode: Boolean? = null
}

abstract class BackwardCompatibilityCheckBaseCommand(
    @field:picocli.CommandLine.Mixin
    val options: BackwardCompatibilityCheckOptions = BackwardCompatibilityCheckOptions()
): Callable<Int> {
    protected val specmaticConfig: SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    protected val backwardCompConfig = specmaticConfig.getBackwardCompatibilityConfig()
    private val newLine = System.lineSeparator()
    private var areLocalChangesStashed = false

    protected val effectiveRepoDir: String by lazy { options.repoDir ?: backwardCompConfig?.repoDirectory ?: "." }
    protected val gitCommand: GitCommand by lazy { SystemGit(workingDirectory = Paths.get(effectiveRepoDir).absolutePathString()) }
    protected val effectiveBaseBranch: String by lazy { options.baseBranch ?: backwardCompConfig?.baseBranch ?: gitCommand.currentRemoteBranch() }
    protected val effectiveTargetPath: String by lazy { options.targetPath ?: backwardCompConfig?.targetPath.orEmpty() }
    protected val effectiveStrictMode: Boolean by lazy { options.strictMode ?: backwardCompConfig?.strictMode ?: false }

    abstract fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): BackwardCompatibilityCheckResult
    abstract fun File.isValidFileFormat(): Boolean
    abstract fun File.isValidSpec(): Boolean
    abstract fun File.isExternalisedExample(): Boolean
    abstract fun getFeatureFromSpecPath(path: String): IFeature

    abstract fun getSpecsOfChangedExternalisedExamples(
        filesChangedInCurrentBranch: Set<String>
    ): Set<String>

    open fun regexForMatchingReferred(schemaFileName: String): String = ""
    open fun getExternalExampleValidationResults(feature: IFeature): ValidationResults = ValidationResults.forNoExamples()
    open fun getExternalExampleDirectories(feature: IFeature): Set<String> = emptySet()
    open fun getUnusedExamples(feature: IFeature): Set<String> = emptySet()

    final override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = options.debugLog))
        addShutdownHook()

        val filesChangedInCurrentBranch = changedSpecsInCurrentBranch()
        val untrackedFiles = untrackedFiles()

        if (filesChangedInCurrentBranch.isEmpty() && untrackedFiles.isEmpty()) {
            logger.log("$newLine No specs were changed, skipping the check.$newLine")
            logger.log(CompatibilityReport.emptyReport())
            return 0
        }

        val changedExternalisedExampleFiles = filesChangedInCurrentBranch.filter {
            File(it).isExternalisedExample()
        }.toSet()
        val changedSpecFiles = changedSpecFiles(filesChangedInCurrentBranch, changedExternalisedExampleFiles)
        val filesReferringToChangedSpecFiles = getSpecsReferringTo(filesChangedInCurrentBranch).filter {
            File(it).isValidSpec()
        }.toSet()
        val specsOwningChangedExternalisedExamples = getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch).canonicalPaths()
        val allSpecsToCheck =
            (changedSpecFiles + filesReferringToChangedSpecFiles + specsOwningChangedExternalisedExamples).canonicalPaths()

        logFilesToBeCheckedForBackwardCompatibility(
            changedSpecFiles,
            changedExternalisedExampleFiles,
            filesReferringToChangedSpecFiles,
            specsOwningChangedExternalisedExamples,
            untrackedFiles
        )

        val result = try {
            runBackwardCompatibilityCheckFor(
                allChangedSpecFiles = allSpecsToCheck,
                changedSpecFiles = changedSpecFiles,
                changedExternalisedExampleFiles = changedExternalisedExampleFiles,
                specsWhoseExternalisedExamplesShouldBeValidated = changedSpecFiles + specsOwningChangedExternalisedExamples,
                baseBranch = effectiveBaseBranch
            )
        } catch (e: Throwable) {
            logger.newLine()
            logger.newLine()
            logger.log(e)
            return 1
        }

        logger.log(result.report)
        return result.exitCode
    }

    private fun untrackedFiles(): Set<String> {
        return gitCommand.getUntrackedFiles()
            .filter { it.contains(effectiveTargetPath) }
            .filter { File(it).isValidSpec() }
            .filter { getSpecsReferringTo(setOf(it)).isEmpty() }
            .toSet()
    }

    private fun changedSpecsInCurrentBranch(): Set<String> {
        return gitCommand.getFilesChangedInCurrentBranch(
            effectiveBaseBranch
        ).filter {
            File(it).exists() && File(it).isValidFileFormat()
        }.filter {
            it.contains(Path(effectiveTargetPath).toString())
        }.toSet()
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

    private fun logFilesToBeCheckedForBackwardCompatibility(
        changedSpecFiles: Set<String>,
        changedExternalisedExampleFiles: Set<String>,
        filesReferringToChangedFiles: Set<String>,
        specificationsOfChangedExternalisedExamples: Set<String>,
        untrackedFiles: Set<String>
    ) {
        logger.log("Checking backward compatibility of the following specs:$newLine")
        changedSpecFiles.printSummary("Specs that have changed")
        changedExternalisedExampleFiles.printExternalisedExampleSummary("Externalised example directories whose spec has changed or which contain changed examples")
        filesReferringToChangedFiles.printSummary("Specs referring to the changed specs")
        logSpecsSelectedForExternalisedExampleValidation(changedSpecFiles, specificationsOfChangedExternalisedExamples)
        untrackedFiles.printSummary("Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs)")
        logger.log("-".repeat(20))
        logger.log(newLine)
    }

    private fun Set<String>.printSummary(header: String) {
        if (this.isNotEmpty()) {
            logger.log("${ONE_INDENT}- $header: ")
            this.forEachIndexed { index, it ->
                logger.log(displayPath(it).prependIndent("$TWO_INDENTS${index.inc()}. "))
            }
            logger.boundary()
        }
    }

    private fun Set<String>.printExternalisedExampleSummary(header: String) {
        if (isEmpty()) return

        val groupedByDirectory = groupBy { externalisedExamplesDirectoryOf(it) ?: displayPath(it) }
            .toSortedMap()

        logger.log("${ONE_INDENT}- $header: ")
        groupedByDirectory.entries.forEachIndexed { index, (directory, files) ->
            val fileCount = if (files.size == 1) "1 file changed" else "${files.size} files changed"
            logger.log("$TWO_INDENTS${index.inc()}. $directory ($fileCount)")
        }
        logger.boundary()
    }

    private fun logSpecsSelectedForExternalisedExampleValidation(
        changedSpecFiles: Set<String>,
        specificationsOfChangedExternalisedExamples: Set<String>
    ) {
        val specsToValidate = changedSpecFiles.map { it to "changed spec" } +
            (specificationsOfChangedExternalisedExamples - changedSpecFiles).map {
                it to "changed externalised examples associated with unchanged spec"
            }

        if (specsToValidate.isEmpty()) return

        logger.log("${ONE_INDENT}- Specs whose externalised examples will be validated: ")
        specsToValidate.sortedBy { (path, _) -> displayPath(path) }.forEachIndexed { index, (path, reason) ->
            logger.log("$TWO_INDENTS${index.inc()}. ${displayPath(path)} ($reason)")
        }
        logger.boundary()
    }

    private fun changedSpecFiles(
        filesChangedInCurrentBranch: Set<String>,
        changedExternalisedExampleFiles: Set<String>
    ): Set<String> {
        return (filesChangedInCurrentBranch - changedExternalisedExampleFiles)
            .filter { File(it).isValidSpec() }
            .toSet()
            .canonicalPaths()
    }

    private fun Set<String>.canonicalPaths(): Set<String> = map { path -> File(path).canonicalPath }.toSet()

    private fun getCurrentBranch(): String {
        val branchWithChanges = gitCommand.currentBranch()
        return if (branchWithChanges == HEAD) gitCommand.detachedHEAD() else branchWithChanges
    }

    private val unknownResult =
        Pair<CompatibilityResult, List<OperationUsageResponse>>(CompatibilityResult.UNKNOWN, emptyList())

    data class BackwardCompatibilityCheckResult(
        val results: Results,
        val reportRecords: List<CtrfBackwardCompatibilityRecord> = emptyList()
    )

    private fun runBackwardCompatibilityCheckFor(
        allChangedSpecFiles: Set<String>,
        changedSpecFiles: Set<String>,
        changedExternalisedExampleFiles: Set<String>,
        specsWhoseExternalisedExamplesShouldBeValidated: Set<String>,
        baseBranch: String
    ): CompatibilityReport {
        val treeishWithChanges = getCurrentBranch()
        val reportStartTime = System.currentTimeMillis()

        try {
            // FIRST PASS: collect results without logging. This includes reading newer/older features and running the lightweight compatibility check.
            val checkedSpecs = runBackwardCompatibilityChecks(
                allChangedSpecFiles,
                changedSpecFiles,
                changedExternalisedExampleFiles,
                specsWhoseExternalisedExamplesShouldBeValidated,
                baseBranch,
                treeishWithChanges
            )
            // SECOND PASS: for all specs that failed the compatibility check, call the potentially long-running ServiceLoader hooks in batches of 5
            val specsValidatedByHook = validateSpecsWithHook(checkedSpecs)
            // THIRD PASS: do the actual logging and produce final CompatibilityResult list
            val results = logSpecsAndCollectResults(specsValidatedByHook)

            generateBackwardCompatibilityReport(
                specsValidatedByHook.flatMap { it.reportRecords },
                reportStartTime,
                System.currentTimeMillis()
            )

            return CompatibilityReport(
                results = results,
                summary = CompatibilityReport.Summary(
                    changedSpecsCount = changedSpecFiles.size,
                    changedExternalisedExampleFilesCount = changedExternalisedExampleFiles.size,
                    specBackwardCompatibilityFailureCount = specsValidatedByHook.count { it.hasBackwardCompatibilityFailure() },
                    specExternalExampleValidationFailureCount = specsValidatedByHook.count { it.hasExternalExampleValidationFailure() }
                )
            )
        } finally {
            gitCommand.checkout(treeishWithChanges)
        }
    }

    private fun runBackwardCompatibilityChecks(
        allChangedSpecFiles: Set<String>,
        changedSpecFiles: Set<String>,
        changedExternalisedExampleFiles: Set<String>,
        specsWhoseExternalisedExamplesShouldBeValidated: Set<String>,
        baseBranch: String,
        treeishWithChanges: String
    ): List<ProcessedSpec> {
        return allChangedSpecFiles.mapNotNull { specFilePath ->
            try {
                if (with(File(specFilePath)) { exists() && isValidSpec().not() }) {
                    return@mapNotNull null
                }

                runBackwardCompatibilityCheckForSpec(
                    specFilePath,
                    changedSpecFiles,
                    changedExternalisedExampleFiles,
                    specsWhoseExternalisedExamplesShouldBeValidated,
                    baseBranch
                )
            } finally {
                gitCommand.checkout(treeishWithChanges)
                if (areLocalChangesStashed) {
                    gitCommand.stashPop()
                    areLocalChangesStashed = false
                }
            }
        }
    }

    private fun runBackwardCompatibilityCheckForSpec(
        specFilePath: String,
        changedSpecFiles: Set<String>,
        changedExternalisedExampleFiles: Set<String>,
        specsWhoseExternalisedExamplesShouldBeValidated: Set<String>,
        baseBranch: String
    ): ProcessedSpec {
        val (processedSpec, compatibilityLogOutput) = captureCompatibilityOutput {
            val newer = getFeatureFromSpecPath(specFilePath)
            // The newer feature is parsed while the worktree has the current branch files, but below we
            // checkout the base branch before running the comparison. OpenAPI change tracking resolves
            // external refs when scenariosForChangeTracking() is first evaluated, so delaying this until
            // after checkout races with the branch switch and can build the newer fingerprints from the
            // base branch's external files. Materialize it here while the current branch is still checked out.
            (newer as? Feature)?.scenariosForChangeTracking()

            val processedExternalisedExamples = evaluateExternalisedExamples(newer, changedExternalisedExampleFiles)

            if (isNewSpecFile(specFilePath, baseBranch)) {
                return@captureCompatibilityOutput createResultForNewSpec(
                    specFilePath = specFilePath,
                    externalisedExamples = processedExternalisedExamples,
                    changedSpecFiles = changedSpecFiles,
                    specsWhoseExternalisedExamplesShouldBeValidated = specsWhoseExternalisedExamplesShouldBeValidated
                )
            }

            areLocalChangesStashed = gitCommand.stash()
            gitCommand.checkout(baseBranch)

            val older = getFeatureFromSpecPath(specFilePath)
            val checkResult = checkBackwardCompatibility(older, newer)

            LicenseResolver.utilize(
                product = LicensedProduct.OPEN_SOURCE,
                feature = SpecmaticFeature.BACKWARD_COMPATIBILITY_CHECK,
                protocol = listOfNotNull((older as? Feature)?.protocol)
            )

            createResultForExistingSpec(
                specFilePath = specFilePath,
                externalisedExamples = processedExternalisedExamples,
                changedSpecFiles = changedSpecFiles,
                specsWhoseExternalisedExamplesShouldBeValidated = specsWhoseExternalisedExamplesShouldBeValidated,
                checkResult = checkResult
            )
        }

        return processedSpec.copy(compatibilityLogOutput = compatibilityLogOutput)
    }

    private fun evaluateExternalisedExamples(
        newer: IFeature,
        changedExternalisedExampleFiles: Set<String>
    ): ProcessedExternalisedExamples {
        val exampleDirectoriesForSpec = getExternalExampleDirectories(newer)
        val changedExamplesForSpec = changedExternalisedExampleFiles.filter { changedExamplePath ->
            belongsToSpecExampleDirectory(changedExamplePath, exampleDirectoriesForSpec)
        }

        return ProcessedExternalisedExamples(
            validationResults = getExternalExampleValidationResults(newer),
            directories = exampleDirectoriesForSpec,
            changedFileCount = changedExamplesForSpec.size,
            unloadableExamples = getUnusedExamples(newer)
        )
    }

    private fun belongsToSpecExampleDirectory(
        changedExamplePath: String,
        exampleDirectoriesForSpec: Set<String>
    ): Boolean {
        val relativeChangedExamplePath = displayPath(changedExamplePath)
        return exampleDirectoriesForSpec.any { exampleDirectory ->
            relativeChangedExamplePath == exampleDirectory || relativeChangedExamplePath.startsWith("$exampleDirectory/")
        }
    }

    private fun isNewSpecFile(specFilePath: String, baseBranch: String): Boolean {
        val repoDirFile = File(effectiveRepoDir).absoluteFile
        val relativeSpecPath = File(specFilePath).relativeTo(repoDirFile).invariantSeparatorsPath
        return !gitCommand.exists(baseBranch, relativeSpecPath)
    }

    private fun createResultForNewSpec(
        specFilePath: String,
        externalisedExamples: ProcessedExternalisedExamples,
        changedSpecFiles: Set<String>,
        specsWhoseExternalisedExamplesShouldBeValidated: Set<String>
    ): ProcessedSpec {
        return ProcessedSpec(
            specFilePath = specFilePath,
            backwardCompatibilityResult = Results(),
            externalisedExamples = externalisedExamples,
            isChangedSpec = specFilePath in changedSpecFiles,
            ownsChangedExternalisedExamples = specFilePath in (specsWhoseExternalisedExamplesShouldBeValidated - changedSpecFiles),
            precomputedCompatibilityResult = CompatibilityResult.PASSED,
            isNewFile = true
        )
    }

    private fun createResultForExistingSpec(
        specFilePath: String,
        externalisedExamples: ProcessedExternalisedExamples,
        changedSpecFiles: Set<String>,
        specsWhoseExternalisedExamplesShouldBeValidated: Set<String>,
        checkResult: BackwardCompatibilityCheckResult
    ): ProcessedSpec {
        val backwardCompatibilityResult = checkResult.results
        val compatibilityResult =
            if (backwardCompatibilityResult.successExcludingIgnorableFailures()) CompatibilityResult.PASSED else CompatibilityResult.FAILED

        return ProcessedSpec(
            specFilePath = specFilePath,
            backwardCompatibilityResult = backwardCompatibilityResult,
            externalisedExamples = externalisedExamples,
            isChangedSpec = specFilePath in changedSpecFiles,
            ownsChangedExternalisedExamples = specFilePath in (specsWhoseExternalisedExamplesShouldBeValidated - changedSpecFiles),
            precomputedCompatibilityResult = compatibilityResult,
            isNewFile = false,
            reportRecords = checkResult.reportRecords
        )
    }

    private fun logSpecsAndCollectResults(specsValidatedByHook: List<ProcessedSpec>): List<CompatibilityResult> {
        return specsValidatedByHook.mapIndexed { index, processed ->
            logSpecHeading(index.inc(), processed)
            logCapturedCompatibilityOutput(processed)

            if (processed.isNewFile) {
                logger.log("${ONE_INDENT}${displayPath(processed.specFilePath)} is a new file.$newLine")
                CompatibilityResult.PASSED
            } else {
                getCompatibilityResultAndLogResults(processed)
            }
        }
    }

    val hook = ServiceLoader.load(BackwardCompatibilityCheckHook::class.java).firstOrNull()

    private fun validateSpecsWithHook(processedSpecs: List<ProcessedSpec>): List<ProcessedSpec> {
        val failedSpecs = processedSpecs.filter { it.backwardCompatibilityResult.successExcludingIgnorableFailures().not() }

        if (failedSpecs.isEmpty() || hook == null)
            return processedSpecs

        val poolSize = 5
        hook.logStartedMessage(failedSpecs)

        val executor = Executors.newFixedThreadPool(poolSize)

        try {
            val repoDirFile = File(effectiveRepoDir).absoluteFile
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

    private fun getCompatibilityResultAndLogResults(processedSpec: ProcessedSpec): CompatibilityResult {
        return if (processedSpec.backwardCompatibilityResult.successExcludingIgnorableFailures().not()) {
            logIncompatibleSpecAndGetResult(processedSpec)
        } else {
            logCompatibleSpecAndGetResult(processedSpec)
        }
    }

    private fun logSpecHeading(index: Int, processedSpec: ProcessedSpec) {
        logger.log("=".repeat(79))
        logger.log("${index}. Running the check for ${displayPath(processedSpec.specFilePath)}:")
        logger.log("=".repeat(79))
    }

    private fun logCapturedCompatibilityOutput(processedSpec: ProcessedSpec) {
        if (processedSpec.compatibilityLogOutput.isBlank()) return

        logger.log(processedSpec.compatibilityLogOutput.trim { it == '\n' || it == '\r' })
    }

    private fun logIncompatibleSpecAndGetResult(processedSpec: ProcessedSpec): CompatibilityResult {
        val backwardCompatibilityResult = processedSpec.backwardCompatibilityResult
        val specFilePath = displayPath(processedSpec.specFilePath)

        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("The Incompatibility Report:$newLine".prependIndent(ONE_INDENT))
        logger.log(
            backwardCompatibilityResult.withoutIgnorableFailures().withoutViolationReport().distinctReport()
                .prependIndent(TWO_INDENTS)
        )

        logWipScenarios(backwardCompatibilityResult)
        logger.log(
            "Externalised example validation skipped because spec itself is backward incompatible."
                .prependIndent(TWO_INDENTS)
        )

        val verdict = failedVerdictMessage(processedSpec, hook, effectiveStrictMode, effectiveBaseBranch)

        logVerdictFor(specFilePath, verdict.second.prependIndent(ONE_INDENT))

        return verdict.first
    }

    private fun logCompatibleSpecAndGetResult(processedSpec: ProcessedSpec): CompatibilityResult {
        val specFilePath = displayPath(processedSpec.specFilePath)
        val scopeDescription = processedSpec.exampleValidationScopeDescription()

        logWipScenarios(processedSpec.backwardCompatibilityResult)

        val examplesSpecificErrorsFound = logExampleValidationSummaryAndReturnResult(processedSpec, scopeDescription)

        val message = if (examplesSpecificErrorsFound) {
            "(INCOMPATIBLE) The spec is backward compatible but ${scopeDescription ?: "the examples"} are NOT backward compatible or are INVALID."
        } else {
            val scopeSuffix = when {
                !processedSpec.isChangedSpec && processedSpec.ownsChangedExternalisedExamples ->
                    " Changed externalised examples associated with unchanged spec are valid."
                else -> ""
            }
            "(COMPATIBLE) The spec is backward compatible with the corresponding spec from $effectiveBaseBranch$scopeSuffix"
        }
        logVerdictFor(specFilePath, message.prependIndent(ONE_INDENT), startWithNewLine = examplesSpecificErrorsFound)

        return if (examplesSpecificErrorsFound) CompatibilityResult.FAILED
        else CompatibilityResult.PASSED
    }

    private fun logWipScenarios(backwardCompatibilityResult: Results) {
        if (!backwardCompatibilityResult.hasIgnorableFailures()) return

        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("WIP scenarios (incompatible, not breaking the check):$newLine".prependIndent(ONE_INDENT))
        logger.log(
            backwardCompatibilityResult.ignorableFailures().withoutViolationReport().distinctReport()
                .prependIndent(TWO_INDENTS)
        )
    }

    private fun logVerdictFor(specFilePath: String, message: String, startWithNewLine: Boolean = true) {
        if (startWithNewLine) logger.log(newLine)
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log("Verdict for spec $specFilePath:".prependIndent(ONE_INDENT))
        logger.log("$ONE_INDENT$message")
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log(newLine)
    }

    private fun ProcessedSpec.exampleValidationScopeDescription(): String? {
        val scopes = buildList {
            if (isChangedSpec) add("externalised examples of changed spec")
            if (!isChangedSpec && ownsChangedExternalisedExamples) add("changed externalised examples associated with unchanged spec")
        }

        return scopes.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    }

    private fun ProcessedSpec.hasBackwardCompatibilityFailure(): Boolean {
        if (backwardCompatibilityResult.successExcludingIgnorableFailures()) return false
        return failedVerdictMessage(this, hook, effectiveStrictMode, effectiveBaseBranch).first == CompatibilityResult.FAILED
    }

    private fun ProcessedSpec.hasExternalExampleValidationFailure(): Boolean {
        if (!backwardCompatibilityResult.successExcludingIgnorableFailures()) return false
        return !externalisedExamples.validationResults.success || externalisedExamples.unloadableExamples.isNotEmpty()
    }

    private fun logExternalisedExampleScope(processedSpec: ProcessedSpec, scopeDescription: String?) {
        val examples = processedSpec.externalisedExamples
        val shouldLogScope = examples.validationResults.exampleValidationResults.isNotEmpty() ||
            examples.changedFileCount > 0 ||
            !examples.validationResults.success ||
            examples.unloadableExamples.isNotEmpty()

        if (!shouldLogScope) return

        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("Externalised Example Scope:$newLine".prependIndent(ONE_INDENT))

        scopeDescription?.let {
            logger.log("Validation triggered by $it.".prependIndent(TWO_INDENTS))
        }

        if (examples.directories.isNotEmpty()) {
            logger.log(
                "Example directories: ${examples.directories.sorted().joinToString(", ")}"
                    .prependIndent(TWO_INDENTS)
            )
        }

        logger.log(
            "Loaded externalised examples: ${examples.validationResults.exampleValidationResults.size}"
                .prependIndent(TWO_INDENTS)
        )

        if (examples.changedFileCount > 0) {
            logger.log(
                "Changed externalised example files in scope: ${examples.changedFileCount}"
                    .prependIndent(TWO_INDENTS)
            )
        }
    }

    private fun logExampleValidationSummaryAndReturnResult(processedSpec: ProcessedSpec, scopeDescription: String?): Boolean {
        val externalExampleValidationResults = processedSpec.externalisedExamples.validationResults
        val hasExampleValidationErrors = !externalExampleValidationResults.success
        val hasUnloadableExamples = processedSpec.externalisedExamples.unloadableExamples.isNotEmpty()
        val shouldLogExampleValidationSummary = processedSpec.shouldLogExternalisedExampleSummary(scopeDescription)

        if (shouldLogExampleValidationSummary || hasUnloadableExamples) {
            logExternalisedExampleScope(processedSpec, scopeDescription)
            logger.log("_".repeat(40).prependIndent(ONE_INDENT))
            logger.log("The Examples Validation Summary:$newLine".prependIndent(ONE_INDENT))
        }

        if (shouldLogExampleValidationSummary) {
            logExternalExampleValidationResult(externalExampleValidationResults)
        }

        if (hasUnloadableExamples) {
            logUnloadableExamples(processedSpec.externalisedExamples.unloadableExamples)
        }

        return hasExampleValidationErrors || hasUnloadableExamples
    }

    private fun ProcessedSpec.shouldLogExternalisedExampleSummary(scopeDescription: String?): Boolean {
        if (scopeDescription == null) return false

        val examples = externalisedExamples
        return examples.directories.isNotEmpty() ||
            examples.validationResults.exampleValidationResults.isNotEmpty() ||
            examples.changedFileCount > 0 ||
            examples.unloadableExamples.isNotEmpty()
    }

    private fun logExternalExampleValidationResult(validationResults: ValidationResults) {
        val exampleValidationResults = validationResults.exampleValidationResults
        val hasValidationErrors = exampleValidationResults.any { (_, result) -> !result.isSuccess() }
        val summaryTitle = "=============== External Example Validation Summary ==============="

        if (hasValidationErrors) {
            logger.boundary()
            logger.log("=============== External Example Validation Results ===============".prependIndent(TWO_INDENTS))
            exampleValidationResults.forEach { (exampleFileName, result) ->
                if (!result.isSuccess()) {
                    val errorPrefix = if (result.isPartialFailure()) "Warning" else "Error"
                    logger.boundary()
                    logger.log("$errorPrefix(s) found in the example file - '${displayPath(exampleFileName)}':".prependIndent(TWO_INDENTS))
                    logger.log(result.reportString().prependIndent(TWO_INDENTS))
                }
            }
        }

        logger.boundary()
        logger.log(summaryTitle.prependIndent(TWO_INDENTS))
        if (!hasValidationErrors && exampleValidationResults.isNotEmpty()) {
            logger.log("Validated ${exampleValidationResults.size} externalised example(s).".prependIndent(TWO_INDENTS))
        }
        logger.log(Results(exampleValidationResults.values.toList()).summary().prependIndent(TWO_INDENTS))
        logger.log("=".repeat(summaryTitle.length).prependIndent(TWO_INDENTS))
    }

    private fun logUnloadableExamples(unusedExamples: Set<String>) {
        logger.boundary()
        logger.log("=============== External Example Loading Results ===============".prependIndent(TWO_INDENTS))
        logger.log("The following externalised example files could not be loaded:".prependIndent(TWO_INDENTS))
        unusedExamples.sorted().forEach { unusedExample ->
            logger.log(displayPath(unusedExample).prependIndent("$TWO_INDENTS- "))
        }
    }

    private fun externalisedExamplesDirectoryOf(path: String): String? {
        val segments = displayPath(path).split('/')
        val directoryIndex = segments.indexOfLast { it.endsWith("_examples") }
        if (directoryIndex == -1) return null

        return segments.take(directoryIndex + 1).joinToString("/")
    }

    private fun displayPath(path: String): String {
        val pathFile = File(path)
        val repoDirFile = File(effectiveRepoDir).canonicalFile
        val canonicalPath = runCatching { pathFile.canonicalFile }.getOrElse { pathFile.absoluteFile }

        return runCatching {
            canonicalPath.relativeTo(repoDirFile).invariantSeparatorsPath
        }.getOrElse {
            path.replace(File.separatorChar, '/')
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

    companion object {
        private const val HEAD = "HEAD"
        internal const val ONE_INDENT = "  "
        private const val TWO_INDENTS = "${ONE_INDENT}${ONE_INDENT}"

        fun failedVerdictMessage(
            processedSpec: ProcessedSpec, hook: BackwardCompatibilityCheckHook?, strictMode: Boolean, baseBranch: String
        ): Pair<CompatibilityResult, String> {
            val defaultMessages =
                "(INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from $baseBranch"

            if (hook == null) {
                return Pair(
                    processedSpec.precomputedCompatibilityResult, defaultMessages
                )
            }

            return hook.failedVerdictAndMessage(processedSpec, strictMode)
        }
    }
}

private fun <T> captureCompatibilityOutput(block: () -> T): Pair<T, String> {
    val capturedOutput = StringBuilder()
    val capturedLogger = capturedCompatibilityLogger(capturedOutput)

    return withLogger(capturedLogger) {
        block()
    } to capturedOutput.toString()
}

private fun capturedCompatibilityLogger(capturedOutput: StringBuilder): ThreadSafeLog {
    val printer = object : LogPrinter {
        override fun print(msg: LogMessage, indentation: String) {
            val renderedMessage = msg.toLogString().prependIndent(indentation)

            if (renderedMessage.shouldBeSkippedFromCapturedCompatibilityOutput()) {
                return
            }

            capturedOutput.appendLine(renderedMessage)
        }
    }

    return ThreadSafeLog(NonVerbose(CompositePrinter(listOf(printer))))
}

private fun String.shouldBeSkippedFromCapturedCompatibilityOutput(): Boolean {
    return startsWith("WARNING: Ignoring request example named ") ||
        startsWith("WARNING: Ignoring response example named ") ||
        startsWith("Using Specmatic Open Source license")
}
