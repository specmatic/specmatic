package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class Specmatic(
    val license: License? = null,
    val governance: Governance? = null,
    val settings: RefOrValue<ConcreteSettings>? = null
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Specmatic {
        return copy(
            governance = governance?.mapPaths(mapper.child("governance"), configDirectory),
            settings = settings?.mapValue { it.mapPaths(mapper.child("settings"), configDirectory) },
            license = license?.mapPaths(mapper.child("license").child("path"), configDirectory),
        )
    }
}
