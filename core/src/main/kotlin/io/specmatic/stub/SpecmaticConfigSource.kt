package io.specmatic.stub

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfig
import java.io.File

interface SpecmaticConfigSource {
    fun load(): LoadedSpecmaticConfig

    data class LoadedSpecmaticConfig(val config: SpecmaticConfig, val path: String?)

    companion object {
        val None: SpecmaticConfigSource = object : SpecmaticConfigSource {
            override fun load(): LoadedSpecmaticConfig = LoadedSpecmaticConfig(SpecmaticConfig(), null)
        }

        fun fromConfigFile(path: String?): SpecmaticConfigSource = when (path) {
            null -> None
            else -> SpecmaticConfigFromPath(path)
        }

        fun fromConfigObject(config: SpecmaticConfig, path: String? = null): SpecmaticConfigSource = SpecmaticConfigFromObject(config, path)
    }
}

private class SpecmaticConfigFromPath(private val path: String) : SpecmaticConfigSource {
    override fun load(): SpecmaticConfigSource.LoadedSpecmaticConfig {
        val file = File(path)

        val resolvedPath = if (file.exists()) file.canonicalPath else path
        val config = if (file.exists()) loadSpecmaticConfig(resolvedPath) else SpecmaticConfig()

        return SpecmaticConfigSource.LoadedSpecmaticConfig(config, resolvedPath)
    }
}

private class SpecmaticConfigFromObject(private val config: SpecmaticConfig, private val path: String?) : SpecmaticConfigSource {
    override fun load(): SpecmaticConfigSource.LoadedSpecmaticConfig =
        SpecmaticConfigSource.LoadedSpecmaticConfig(config, path)
}
