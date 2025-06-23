package application.backwardCompatibility

import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.SystemExit
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.regex.Pattern
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

abstract class BackwardCompatibilityCheckBaseCommand : Callable<Unit> {
    private lateinit var gitCommand: GitCommand
    private val newLine = System.lineSeparator()
    private var areLocalChangesStashed = false

    @Option(
        names = ["--base-branch"],
        description = [
            "Base branch to compare the changes against",
            "Default value is the local origin HEAD of the current branch"
        ],
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
        description = ["The directory of the repository in which to run the backward compatibility check. If not provided, the check will run in the current working directory."],
        required = false
    )
    var repoDir: String = "."

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
        gitCommand = SystemGit(workingDirectory = Paths.get(repoDir).absolutePathString())
        addShutdownHook()
        val filteredSpecs = getChangedSpecs()
        val result = try {
            runBackwardCompatibilityCheckFor(
                files = filteredSpecs,
                baseBranch = baseBranch()
            )
        } catch(e: Throwable) {
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
            it.contains(Path(targetPath).toString())
            && File(it).isValidSpec()
            && getSpecsReferringTo(setOf(it)).isEmpty()
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

        val collectedFiles = filesChangedInCurrentBranch +
                filesReferringToChangedSchemaFiles +
                specificationsOfChangedExternalisedExamples

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
            val combinedPattern = Pattern.compile(queue.toSet().joinToString(prefix = "\\b(?:", separator = "|", postfix = ")\\b") { specFile ->
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
        specificationsOfChangedExternalisedExamples
            .printSummaryOfChangedSpecs("Specs whose externalised examples were changed")
        untrackedFiles.printSummaryOfChangedSpecs("Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs)")
        logger.log("-".repeat(20))
        logger.log(newLine)
    }

    private fun Set<String>.printSummaryOfChangedSpecs(message: String) {
        if(this.isNotEmpty()) {
            logger.log("${ONE_INDENT}- $message: ")
            this.forEachIndexed { index, it ->
                logger.log(it.prependIndent("$TWO_INDENTS${index.inc()}. "))
            }
            logger.log(newLine)
        }
    }

    private fun getCurrentBranch(): String {
        val branchWithChanges = gitCommand.currentBranch()
        return if (branchWithChanges == HEAD) gitCommand.detachedHEAD() else branchWithChanges
    }

    private fun runBackwardCompatibilityCheckFor(files: Set<String>, baseBranch: String): CompatibilityReport {
        val treeishWithChanges = getCurrentBranch()

        try {
            val results = files.mapIndexed { index, specFilePath ->
                try {
                    logger.log("${index.inc()}. Running the check for $specFilePath:")

                    if (with(File(specFilePath)) {
                            exists() && isValidSpec().not()
                        }) {
                        logger.log("${ONE_INDENT}Skipping $specFilePath as it is not a valid spec file.$newLine")
                        return@mapIndexed null
                    }

                    // newer => the file with changes on the branch
                    val newer = getFeatureFromSpecPath(specFilePath)
                    val unusedExamples = getUnusedExamples(newer)

                    val olderFile = gitCommand.getFileInBranch(
                        specFilePath,
                        treeishWithChanges,
                        baseBranch
                    )
                    if (olderFile == null) {
                        logger.log("$ONE_INDENT$specFilePath is a new file.$newLine")
                        return@mapIndexed CompatibilityResult.PASSED
                    }

                    areLocalChangesStashed = gitCommand.stash()
                    gitCommand.checkout(baseBranch)
                    // older => the same file on the default (e.g. main) branch
                    val older = getFeatureFromSpecPath(olderFile.path)

                    val backwardCompatibilityResult = checkBackwardCompatibility(older, newer)

                    return@mapIndexed getCompatibilityResult(
                        backwardCompatibilityResult,
                        specFilePath,
                        newer,
                        unusedExamples
                    )
                } finally {
                    gitCommand.checkout(treeishWithChanges)
                    if (areLocalChangesStashed) {
                        gitCommand.stashPop()
                        areLocalChangesStashed = false
                    }
                }
            }

            return CompatibilityReport(results.filterNotNull())
        } finally {
            gitCommand.checkout(treeishWithChanges)
        }
    }

    private fun baseBranch() = baseBranch ?: gitCommand.currentRemoteBranch()

    private fun getCompatibilityResult(
        backwardCompatibilityResult: Results,
        specFilePath: String,
        newer: IFeature,
        unusedExamples: Set<String>
    ): CompatibilityResult {
        if(backwardCompatibilityResult.success().not()) {
            logger.log("_".repeat(40).prependIndent(ONE_INDENT))
            logger.log("The Incompatibility Report:$newLine".prependIndent(ONE_INDENT))
            logger.log(backwardCompatibilityResult.report().prependIndent(TWO_INDENTS))
            logVerdictFor(
                specFilePath,
                "(INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from ${baseBranch()}".prependIndent(ONE_INDENT)
            )
            return CompatibilityResult.FAILED
        }

        val errorsFound = printExampleValiditySummaryAndReturnResult(newer, unusedExamples, specFilePath)

        val message = if(errorsFound) {
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
        newer: IFeature,
        unusedExamples: Set<String>,
        specFilePath: String
    ): Boolean {
        var errorsFound = false
        val areExamplesInvalid = areExamplesValid(newer, "newer").not()

        if(areExamplesInvalid || unusedExamples.isNotEmpty()) {
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
        Runtime.getRuntime().addShutdownHook(object: Thread() {
            override fun run() {
                runCatching {
                    gitCommand.checkout(getCurrentBranch())
                    if(areLocalChangesStashed) gitCommand.stashPop()
                }
            }
        })
    }

    companion object {
        private const val HEAD = "HEAD"
        private const val MARGIN_SPACE = "  "
        internal const val ONE_INDENT = "  "
        private const val TWO_INDENTS = "${ONE_INDENT}${ONE_INDENT}"
    }
}
