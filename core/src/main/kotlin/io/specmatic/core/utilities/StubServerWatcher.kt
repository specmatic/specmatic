package io.specmatic.core.utilities

import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.DATA_DIR_SUFFIX
import io.specmatic.core.log.StringLog
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchService

class StubServerWatcher(
    private val contractPaths: List<String>
) {
    fun watchForChanges(onChange: () -> Unit) {
        getFileSystemChanges().forEach { change ->
            if (change.hasNoEvents) {
                logger.debug("Uninteresting file system event, skipping restart")
                return@forEach
            }

            if (change.interestingEvents.isEmpty()) {
                logger.debug("Uninteresting file system events for ${change.filesWithEvents}, skipping restart")
                return@forEach
            }

            logger.boundary()
            consoleLog(StringLog("""Detected event(s) for ${change.filesWithEvents}, restarting stub server."""))

            onChange()
        }
    }

    private fun registerForFileSystemChanges(watchService: WatchService) {
        val paths: List<Path> = getPaths(contractPaths).distinct().sorted().map { File(it).toPath() }

        paths.forEach { contractPath ->
            contractPath.register(watchService, ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE)
        }
    }

    private fun getFileSystemChanges(): Sequence<FileSystemChanges> {
        return generateSequence {
            FileSystems.getDefault().newWatchService().use { watchService ->
                registerForFileSystemChanges(watchService)

                watchService.take()?.let { key ->
                    key.reset()
                    FileSystemChanges(key)
                }
            }
        }
    }

    private fun getPaths(contractPaths: List<String>): List<String> {
        return contractPaths.map { File(it) }.flatMap {
            when {
                it.isFile && it.extension.lowercase() in CONTRACT_EXTENSIONS ->
                    listOf(it.absoluteFile.parentFile.path).plus(getPaths(listOf(dataDirOf(it))))
                it.isFile && it.extension.equals("yaml", ignoreCase = true) ->
                    listOf(it.absolutePath)
                it.isFile && it.extension.equals("json", ignoreCase = true) ->
                    listOf(it.absoluteFile.parentFile.path)
                it.isDirectory ->
                    listOf(it.absolutePath).plus(getPaths(it.listFiles()?.toList()?.map { file -> file.absolutePath } ?: emptyList()))
                else -> emptyList()
            }
        }
    }

    internal fun dataDirOf(contractFile: File): String {
        val examplesDir = examplesDirFor("${contractFile.absoluteFile.parent}/${contractFile.name}", DATA_DIR_SUFFIX)
        return "${examplesDir.absoluteFile.parent}/${examplesDir.name}"
    }
}
