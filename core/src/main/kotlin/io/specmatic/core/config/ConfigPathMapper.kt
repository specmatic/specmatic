package io.specmatic.core.config

import java.io.File

interface ConfigPathMapper {
    fun child(index: Int): ConfigPathMapper
    fun child(segment: String): ConfigPathMapper
    fun map(path: String, baseDirectory: File): String
}
