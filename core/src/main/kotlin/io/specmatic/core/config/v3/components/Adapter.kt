package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class Adapter(@JsonValue val hooks: Map<String, String>) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Adapter {
        return copy(
            hooks = hooks.mapValues { (name, path) ->
                mapper.child("hooks").child(name).map(path, configDirectory)
            }
        )
    }
}
