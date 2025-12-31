package io.specmatic.proxy

import java.io.File

class RealFileWriter(private val dataDir: File): FileWriter {
    constructor(dataDir: String): this(File(dataDir))

    override fun createDirectory() {
        if (!dataDir.exists()) dataDir.mkdirs()
    }

    override fun clearDirectory() {
        dataDir.deleteRecursively()
    }

    override fun writeText(path: String, content: String) {
        dataDir.resolve(path).writeText(content)
    }

    override fun subDirectory(path: String): FileWriter {
        return RealFileWriter(dataDir.resolve(path))
    }

    override fun fileName(path: String): String {
        return dataDir.resolve(path).path
    }
}