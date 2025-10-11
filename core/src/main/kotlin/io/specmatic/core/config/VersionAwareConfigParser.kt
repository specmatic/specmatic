package io.specmatic.core.config

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.yamlObjectMapper
import java.io.File

private const val SPECMATIC_CONFIG_VERSION = "version"

private val objectMapper = yamlObjectMapper.copy().registerKotlinModule()

fun File.toSpecmaticConfig(): SpecmaticConfig {
    val configYaml = this.readText()
    return when (configYaml.getVersion()) {
        SpecmaticConfigVersion.VERSION_1 -> {
            objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        }

        SpecmaticConfigVersion.VERSION_2 -> {
            objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        }

        else -> {
            throw ContractException("Unsupported Specmatic config version")
        }
    }
}

fun String.getVersion(): SpecmaticConfigVersion? {
    val version = objectMapper.readTree(this)[SPECMATIC_CONFIG_VERSION]?.asInt()
    if (version == null || version == 0) return SpecmaticConfigVersion.VERSION_1
    return SpecmaticConfigVersion.getByValue(version)
}