package io.specmatic.core.pattern

import java.io.File

class FileValue(private val relativePath: String) : RowValue {
    override fun fetch(): String {
        return File(relativePath).canonicalFile.also<File> { println(it.canonicalPath) }.readText()
    }
}
