package io.specmatic.core.utilities

import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.EXAMPLES_DIR_SUFFIX
import io.specmatic.core.JSON
import io.specmatic.core.log.logger
import java.io.File
import java.nio.file.Path
import java.nio.file.WatchKey

class FileSystemChanges(
    private val key: WatchKey,
) {
    private val rawEvents = key.pollEvents()
    private val events =
        rawEvents
            .map {
                it.context()?.toString().orEmpty()
            }.filter { it.isNotBlank() }

    val hasNoEvents: Boolean
        get() = events.isEmpty()

    val filesWithEvents = events.joinToString(", ")

    val interestingEvents: List<String> = events.filter { event -> interesting(event) }

    private fun interesting(event: String): Boolean {
        try {
            val file = File(event)
            val extension = file.extension.lowercase()

            return extension in CONTRACT_EXTENSIONS ||
                extension == JSON ||
                file.nameWithoutExtension.endsWith(EXAMPLES_DIR_SUFFIX) ||
                parentDirectoryIsExamplesDir(key.watchable() as? Path)
        } catch (e: Throwable) {
            return false
        }
    }

    private fun parentDirectoryIsExamplesDir(path: Path?): Boolean {
        if (path == null) return false

        logger.debug("Checking if $path is or is in an examples directory")

        return path.toString().endsWith(EXAMPLES_DIR_SUFFIX) ||
            path.toString().contains(EXAMPLES_DIR_SUFFIX + File.separator)
    }
}
