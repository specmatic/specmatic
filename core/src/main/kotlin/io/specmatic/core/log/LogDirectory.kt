package io.specmatic.core.log

import java.io.File

class LogDirectory(
    directory: File,
    name: String,
) : LogFile {
    constructor(
        directory: String,
        prefix: String,
        suffix: String,
    ) : this(
        File(directory),
        getLogFileName(prefix, suffix),
    )

    companion object {
        private fun getLogFileName(
            prefix: String,
            suffix: String,
        ): String {
            val dateComponent = CurrentDate().toFileNameString()
            return "$prefix-$dateComponent$suffix"
        }
    }

    val file: File

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }

        file = directory.resolve(name)
        if (!file.exists()) {
            file.createNewFile()
            println("Logging to file ${file.canonicalFile}")
        }
    }

    override fun appendText(text: String) {
        file.appendText(text)
    }
}
