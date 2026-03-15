package application

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.logger
import io.specmatic.license.core.cli.Category
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Command(
    name = "logs",
    mixinStandardHelpOptions = true,
    description = ["Utilities for support log artifacts."],
    subcommands = [LogsCommand.Export::class],
)
@Category("Support")
class LogsCommand : Callable<Int> {
    override fun call(): Int {
        logger.log("Please use one of the subcommands. Use --help to view the list of available subcommands.")
        return 0
    }

    @Command(
        name = "export",
        mixinStandardHelpOptions = true,
        description = ["Bundle logs into one zip file for sharing with support/dev team."],
    )
    class Export : Callable<Int> {
        @Option(names = ["--source"], description = ["Directory containing log files"], required = true)
        lateinit var sourceDirectory: File

        @Option(names = ["--output"], description = ["Output zip file path"], required = false)
        var outputPath: File? = null

        @Option(names = ["--debug"], required = false, defaultValue = "false")
        var debugLog: Boolean = false

        override fun call(): Int {
            configureLogging(
                LoggingConfiguration.Companion.LoggingFromOpts(
                    debug = debugLog,
                    commandName = "logs-export",
                    component = "application",
                ),
            )

            if (!sourceDirectory.exists() || !sourceDirectory.isDirectory) {
                logger.log("Log source directory does not exist or is not a directory: ${sourceDirectory.path}")
                return 1
            }

            val allFiles = sourceDirectory.walkTopDown().filter { it.isFile }.toList()
            if (allFiles.isEmpty()) {
                logger.log("No files found under ${sourceDirectory.path}; nothing to export.")
                return 1
            }

            val outputZip = outputPath ?: File(sourceDirectory, defaultBundleName())
            outputZip.parentFile?.mkdirs()

            zipLogs(sourceDirectory.toPath(), allFiles, outputZip.toPath())

            logger.log("Log bundle created at ${outputZip.canonicalPath} with ${allFiles.size} files.")
            return 0
        }

        private fun zipLogs(sourceRoot: Path, files: List<File>, outputZip: Path) {
            ZipOutputStream(FileOutputStream(outputZip.toFile())).use { zip ->
                val manifestEntry = ZipEntry("manifest.txt")
                zip.putNextEntry(manifestEntry)
                val manifest =
                    buildString {
                        appendLine("generatedAt=${Instant.now()}")
                        appendLine("source=${sourceRoot.toAbsolutePath()}")
                        appendLine("fileCount=${files.size}")
                    }
                zip.write(manifest.toByteArray())
                zip.closeEntry()

                files.forEach { file ->
                    val relative = sourceRoot.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    val entry = ZipEntry(relative)
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }

        private fun defaultBundleName(): String {
            val timestamp = Instant.now().toString().replace(":", "-")
            return "specmatic-log-bundle-$timestamp.zip"
        }
    }
}
