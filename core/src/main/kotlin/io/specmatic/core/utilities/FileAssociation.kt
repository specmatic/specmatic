package io.specmatic.core.utilities

import java.io.File

sealed interface FileAssociation<T> {
    data class Global<T>(val data: T) : FileAssociation<T>
    data class FileScoped<T>(val file: File, val data: T) : FileAssociation<T> {
        fun matches(file: File): Boolean {
            return runCatching { this.file.canonicalPath == file.canonicalPath }.getOrElse {
                this.file.absolutePath == file.absolutePath
            }
        }
    }
}
