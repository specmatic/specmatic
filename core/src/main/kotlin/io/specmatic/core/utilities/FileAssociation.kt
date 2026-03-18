package io.specmatic.core.utilities

import java.io.File

sealed interface FileAssociation<T> {
    val data: T

    data class Global<T>(override val data: T) : FileAssociation<T>
    data class FileScoped<T>(val file: File, override val data: T) : FileAssociation<T> {
        fun matches(file: File): Boolean {
            return runCatching { this.file.canonicalPath == file.canonicalPath }.getOrElse {
                this.file.absolutePath == file.absolutePath
            }
        }
    }
}
