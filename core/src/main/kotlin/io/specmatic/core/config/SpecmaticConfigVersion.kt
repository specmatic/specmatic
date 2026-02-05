package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2

enum class SpecmaticConfigVersion(@JsonValue val value: Int, val configLoader: SpecmaticVersionedConfigLoader) {
    VERSION_1(1, SpecmaticConfigV1.Companion),
    VERSION_2(2, SpecmaticConfigV2.Companion);

    fun isLessThanOrEqualTo(otherVersion: SpecmaticConfigVersion): Boolean {
        return this.ordinal <= otherVersion.ordinal
    }

    companion object {
        @JsonCreator
        fun getByValue(value: Int): SpecmaticConfigVersion? {
            return entries.find { it.value == value }
        }

        fun getLatestVersion(): SpecmaticConfigVersion {
            return entries.maxBy { it.value }
        }

        fun isValidVersion(version: SpecmaticConfigVersion): Boolean {
            return entries.any { it == version }
        }

        fun convertToLatestVersionedConfig(config: SpecmaticConfigV1V2Common): SpecmaticVersionedConfig {
            val latestVersion = getLatestVersion()
            return latestVersion
                .configLoader
                .loadFrom(
                    config.dropExcludedEndpointsAfterVersion1(latestVersion)
                )
        }
    }
}