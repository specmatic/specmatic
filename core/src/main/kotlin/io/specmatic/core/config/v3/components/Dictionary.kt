package io.specmatic.core.config.v3.components

import io.specmatic.core.config.ConfigPathMapper
import java.io.File

class Dictionary(val path: String) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Dictionary {
        return Dictionary(mapper.map(path, configDirectory))
    }
}
