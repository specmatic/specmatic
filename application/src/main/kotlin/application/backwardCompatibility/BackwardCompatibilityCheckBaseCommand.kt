package application.backwardCompatibility

import io.specmatic.core.Feature
import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.TrackingFeature
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
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

abstract class BackwardCompatibilityCheckBaseCommand : Callable<Unit> {
    private lateinit var gitCommand: GitCommand
    private val newLine = System.lineSeparator()
    private var areLocalChangesStashed = false

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
    var targetPath: String = ""

    @Option(
        names = ["--repo-dir"],
        description = ["The directory of the repository in which to run the backward compatibility check.", "If not provided, the check will run in the current working directory."],
        required = false
    )
    var repoDir: String = "."

    @Option(names = ["--debug"], description = ["Write verbose logs to console for debugging"])
    var debugLog = false

    @Option(
        names = ["--strict"],
        description = [
            "In strict mode, irrespective of the API's usage, if a change to an API breaks backward compatibility then it would result in a failure.",
            "When this flag is not specified, backward breaking changes to APIs that have no usages will only generate warnings and won't cause the check to fail.",
            "This flag is only applicable when using Specmatic Insights.",
        ]
    )
    var strictMode = false

    abstract fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): Results
    abstract fun File.isValidFileFormat(): Boolean
    abstract fun File.isValidSpec(): Boolean
    abstract fun getFeatureFromSpecPath(path: String): IFeature

    abstract fun getSpecsOfChangedExternalisedExamples(
        filesChangedInCurrentBranch: Set<String>
    ): Set<String>

    open fun regexForMatchingReferred(schemaFileName: String): String = ""
    open fun areExamplesValid(feature: IFeature, which: String): Boolean = true
    open fun getUnusedExamples(feature: IFeature): Set<String> = emptySet()

    final override fun call() {
        if (debugLog) logger = Verbose()

        gitCommand = SystemGit(workingDirectory = Paths.get(repoDir).absolutePathString())

        addShutdownHook()
        val filteredSpecs = getChangedSpecs()
        val result = try {
            runBackwardCompatibilityCheckFor(
                files = filteredSpecs, baseBranch = baseBranch()
            )
        } catch (e: Throwable) {
            logger.newLine()
            logger.newLine()
            logger.log(e)
            SystemExit.exitWith(1)
        }

        logger.log(result.report)
        SystemExit.exitWith(result.exitCode)
    }

    private fun getChangedSpecs(): Set<String> {
        val filesChangedInCurrentBranch = getChangedSpecsInCurrentBranch().filter {
            it.contains(Path(targetPath).toString())
        }.toSet()

        val untrackedFiles = gitCommand.getUntrackedFiles().filter {
            it.contains(Path(targetPath).toString()) && File(it).isValidSpec() && getSpecsReferringTo(setOf(it)).isEmpty()
        }.toSet()

        if (filesChangedInCurrentBranch.isEmpty() && untrackedFiles.isEmpty()) {
            logger.log("$newLine No specs were changed, skipping the check.$newLine")
            SystemExit.exitWith(0)
        }

        val filesReferringToChangedSchemaFiles = getSpecsReferringTo(filesChangedInCurrentBranch)

        val specificationsOfChangedExternalisedExamples =
            getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch)

        logFilesToBeCheckedForBackwardCompatibility(
            filesChangedInCurrentBranch,
            filesReferringToChangedSchemaFiles,
            specificationsOfChangedExternalisedExamples,
            untrackedFiles
        )

        val collectedFiles =
            filesChangedInCurrentBranch + filesReferringToChangedSchemaFiles + specificationsOfChangedExternalisedExamples

        return collectedFiles.map { path -> File(path).canonicalPath }.toSet()
    }

    private fun getChangedSpecsInCurrentBranch(): Set<String> {
        return gitCommand.getFilesChangedInCurrentBranch(
            baseBranch()
        ).filter {
            File(it).exists() && File(it).isValidFileFormat()
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
        return File(repoDir).walk().toList().filterNot {
            ".git" in it.path
        }.filter { it.isFile && it.isValidFileFormat() }
    }

    private fun logFilesToBeCheckedForBackwardCompatibility(
        changedFiles: Set<String>,
        filesReferringToChangedFiles: Set<String>,
        specificationsOfChangedExternalisedExamples: Set<String>,
        untrackedFiles: Set<String>
    ) {
        logger.log("Checking backward compatibility of the following specs:$newLine")
        changedFiles.printSummaryOfChangedSpecs("Specs that have changed")
        filesReferringToChangedFiles.printSummaryOfChangedSpecs("Specs referring to the changed specs")
        specificationsOfChangedExternalisedExamples.printSummaryOfChangedSpecs("Specs whose externalised examples were changed")
        untrackedFiles.printSummaryOfChangedSpecs("Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs)")
        logger.log("-".repeat(20))
        logger.log(newLine)
    }

    private fun Set<String>.printSummaryOfChangedSpecs(message: String) {
        if (this.isNotEmpty()) {
            logger.log("${ONE_INDENT}- $message: ")
            this.forEachIndexed { index, it ->
                logger.log(it.prependIndent("$TWO_INDENTS${index.inc()}. "))
            }
            logger.boundary()
        }
    }

    private fun getCurrentBranch(): String {
        val branchWithChanges = gitCommand.currentBranch()
        return if (branchWithChanges == HEAD) gitCommand.detachedHEAD() else branchWithChanges
    }

    val unknownResult =
        Pair<CompatibilityResult, List<OperationUsageResponse>>(CompatibilityResult.UNKNOWN, emptyList())

    data class ProcessedSpec(
        val specFilePath: String,
        val backwardCompatibilityResult: Results,
        val newer: IFeature,
        val unusedExamples: Set<String>,
        val precomputedCompatibilityResult: CompatibilityResult,
        val computedCompatibilityCheckHookResult: Pair<CompatibilityResult, List<OperationUsageResponse>?> = Pair(
            CompatibilityResult.UNKNOWN, emptyList()
        ),
        val isNewFile: Boolean
    )

    private fun runBackwardCompatibilityCheckFor(files: Set<String>, baseBranch: String): CompatibilityReport {
        val treeishWithChanges = getCurrentBranch()

        try {
            // FIRST PASS: collect results without logging. This includes reading newer/older features and running the lightweight compatibility check.
            val processedSpecs = files.mapNotNull { specFilePath ->
                try {
                    if (with(File(specFilePath)) { exists() && isValidSpec().not() }) {
                        // skip non-spec files
                        return@mapNotNull null
                    }

                    val newer = getFeatureFromSpecPath(specFilePath)
                    val unusedExamples = getUnusedExamples(newer)

                    val olderFileExists =
                        gitCommand.exists(baseBranch, File(specFilePath).relativeTo(File(repoDir).absoluteFile).path)

                    if (!olderFileExists) {
                        // new file: mark as passed immediately
                        return@mapNotNull ProcessedSpec(
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

                    val backwardCompatibilityResult = checkBackwardCompatibility(older, newer)
                    val result =
                        if (backwardCompatibilityResult.success()) CompatibilityResult.PASSED else CompatibilityResult.FAILED

                    LicenseResolver.utilize(
                        product = LicensedProduct.OPEN_SOURCE,
                        feature = TrackingFeature.BACKWARD_COMPATIBILITY_CHECK,
                        protocol = listOfNotNull((older as? Feature)?.protocol)
                    )

                    return@mapNotNull ProcessedSpec(
                        specFilePath = specFilePath,
                        backwardCompatibilityResult = backwardCompatibilityResult,
                        newer = newer,
                        unusedExamples = unusedExamples,
                        precomputedCompatibilityResult = result,
                        isNewFile = false
                    )
                } finally {
                    gitCommand.checkout(treeishWithChanges)
                    if (areLocalChangesStashed) {
                        gitCommand.stashPop()
                        areLocalChangesStashed = false
                    }
                }
            }

            // SECOND PASS: for all specs that failed the compatibility check, call the potentially long-running ServiceLoader hooks in batches of 5
            val specsValidatedByHook = validateSpecsWithHook(processedSpecs)

            // THIRD PASS: do the actual logging and produce final CompatibilityResult list
            val results = specsValidatedByHook.mapIndexed { index, processed ->
                logger.log("${index.inc()}. Running the check for ${processed.specFilePath}:")

                if (processed.isNewFile) {
                    logger.log("${ONE_INDENT}${processed.specFilePath} is a new file.$newLine")
                    CompatibilityResult.PASSED
                } else {
                    getCompatibilityResultAndLogResults(processed)
                }
            }

            return CompatibilityReport(results)
        } finally {
            gitCommand.checkout(treeishWithChanges)
        }
    }

    val hook = ServiceLoader.load(BackwardCompatibilityCheckHook::class.java).firstOrNull()

    private fun validateSpecsWithHook(processedSpecs: List<ProcessedSpec>): List<ProcessedSpec> {
        val failedSpecs = processedSpecs.filter { it.backwardCompatibilityResult.success().not() }

        if (failedSpecs.isEmpty() || hook == null)
            return processedSpecs

        val poolSize = 5
        hook.logStartedMessage(failedSpecs)

        val executor = Executors.newFixedThreadPool(poolSize)

        try {
            val futures = failedSpecs.map { processed ->
                processed to executor.submit(Callable {
                    try {
                        hook.check(
                            processed.backwardCompatibilityResult,
                            gitCommand.getRemoteUrl(),
                            File(processed.specFilePath).relativeTo(File(repoDir).absoluteFile).path
                        )
                    } catch (e: Throwable) {
                        logger.log(e)
                        unknownResult
                    }
                })
            }

            return futures.map { (processed, future) ->
                try {
                    val compatibilityResult = future.get()
                    processed.copy(computedCompatibilityCheckHookResult = compatibilityResult)
                } catch (e: Throwable) {
                    logger.log(e)
                    processed.copy(computedCompatibilityCheckHookResult = unknownResult)
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            hook.logCompletedMessage()
        }
    }

    private fun baseBranch() = baseBranch ?: gitCommand.currentRemoteBranch()

    private fun getCompatibilityResultAndLogResults(processedSpec: ProcessedSpec): CompatibilityResult {
        val backwardCompatibilityResult = processedSpec.backwardCompatibilityResult
        val specFilePath = processedSpec.specFilePath
        val newer = processedSpec.newer
        val unusedExamples = processedSpec.unusedExamples

        if (backwardCompatibilityResult.success().not()) {
            logger.log("_".repeat(40).prependIndent(ONE_INDENT))
            logger.log("The Incompatibility Report:$newLine".prependIndent(ONE_INDENT))
            logger.log(backwardCompatibilityResult.withoutViolationReport().report().prependIndent(TWO_INDENTS))

            val verdict = failedVerdictMessage(processedSpec, hook, strictMode, baseBranch())

            logVerdictFor(specFilePath, verdict.second.prependIndent(ONE_INDENT))

            return verdict.first
        }

        val errorsFound = printExampleValiditySummaryAndReturnResult(newer, unusedExamples, specFilePath)

        val message = if (errorsFound) {
            "(INCOMPATIBLE) The spec is backward compatible but the examples are NOT backward compatible or are INVALID."
        } else {
            "(COMPATIBLE) The spec is backward compatible with the corresponding spec from ${baseBranch()}"
        }
        logVerdictFor(specFilePath, message.prependIndent(ONE_INDENT), startWithNewLine = errorsFound)

        return if (errorsFound) CompatibilityResult.FAILED
        else CompatibilityResult.PASSED
    }

    private fun logVerdictFor(specFilePath: String, message: String, startWithNewLine: Boolean = true) {
        if (startWithNewLine) logger.log(newLine)
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log("Verdict for spec $specFilePath:".prependIndent(ONE_INDENT))
        logger.log("$ONE_INDENT$message")
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log(newLine)
    }

    private fun printExampleValiditySummaryAndReturnResult(
        newer: IFeature, unusedExamples: Set<String>, specFilePath: String
    ): Boolean {
        var errorsFound = false
        val areExamplesInvalid = areExamplesValid(newer, "newer").not()

        if (areExamplesInvalid || unusedExamples.isNotEmpty()) {
            logger.log("_".repeat(40).prependIndent(ONE_INDENT))
            logger.log("The Examples Validity Summary:$newLine".prependIndent(ONE_INDENT))
        }
        if (areExamplesInvalid) {
            logger.log("Examples in $specFilePath are not valid.$newLine".prependIndent(TWO_INDENTS))
            errorsFound = true
        }

        if (unusedExamples.isNotEmpty()) {
            logger.log("Some examples for $specFilePath could not be loaded.$newLine".prependIndent(TWO_INDENTS))
            errorsFound = true
        }
        return errorsFound
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
