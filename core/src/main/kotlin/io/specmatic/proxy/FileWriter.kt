package io.specmatic.proxy

interface FileWriter {
    fun createDirectory()
    fun clearDirectory()
    fun writeText(path: String, content: String)
    fun subDirectory(path: String): FileWriter = throw NotImplementedError()
    fun fileName(path: String): String = throw NotImplementedError()
}