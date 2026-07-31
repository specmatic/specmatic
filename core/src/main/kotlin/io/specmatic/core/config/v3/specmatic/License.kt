package io.specmatic.core.config.v3.specmatic

import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class License(val path: String) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): License {
        return copy(path = mapper.map(path, configDirectory))
    }
}
