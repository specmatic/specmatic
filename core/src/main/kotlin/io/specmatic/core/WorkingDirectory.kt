package io.specmatic.core

import java.io.File

class WorkingDirectory(private val filePath: File) {
    constructor(path: String = DEFAULT_WORKING_DIRECTORY): this(File(path))
    val path: String
        get() {
            return filePath.path
        }
}
