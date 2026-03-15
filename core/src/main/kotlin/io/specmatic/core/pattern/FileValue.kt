package io.specmatic.core.pattern

import io.specmatic.core.log.logger
import java.io.File

class FileValue(private val relativePath: String) : RowValue {
    override fun fetch(): String {
        return File(relativePath).canonicalFile.also { logger.debug(it.canonicalPath) }.readText()
    }
}
