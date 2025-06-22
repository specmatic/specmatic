package io.specmatic.core.log

import java.io.File

class LogDirectory(
    directory: File,
    prefix: String,
    tag: String,
    extension: String,
    includeDateInFileName: Boolean = true
): LogFile {
    constructor(
        directory: String,
        prefix: String,
        tag: String,
        extension: String,
        includeDateInFileName: Boolean = true
    ) : this(
        File(directory),
        prefix,
        tag,
        extension,
        includeDateInFileName
    )

    val file: File

    init {
        if(!directory.exists())
            directory.mkdirs()

        val currentDate = CurrentDate()

        val name = when {
            includeDateInFileName -> "$prefix-${currentDate.toFileNameString()}${logFileNameSuffix(tag, extension)}"
            else -> "$prefix${logFileNameSuffix(tag, extension)}"
        }

        file = directory.resolve(name)
        if(!file.exists()) {
            file.createNewFile()
            println("Logging to file ${file.canonicalFile}")
        }
    }

    override fun appendText(text: String) {
        file.appendText(text)
    }
}

fun logFileNameSuffix(tag: String, extension: String): String {
    return tag.let {
        if(it.isNotBlank()) "-$it" else ""
    } + extension.let {
        if(it.isNotBlank()) ".$it" else ""
    }
}

