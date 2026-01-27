package io.specmatic.core.log

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts
import io.specmatic.stub.SpecmaticConfigSource
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault

sealed interface LoggingConfigSource {
    data object None : LoggingConfigSource
    data class FromConfig(val config: LoggingConfiguration) : LoggingConfigSource
    data class FromConfigFile(val path: String) : LoggingConfigSource
}

fun configureLogging(opts: LoggingFromOpts = LoggingFromOpts(), source: LoggingConfigSource = LoggingConfigSource.None) {
    val baseConfig = when (source) {
        is LoggingConfigSource.FromConfig -> source.config
        is LoggingConfigSource.None -> loadSpecmaticConfigIfAvailableElseDefault().getLogConfigurationOrDefault()
        is LoggingConfigSource.FromConfigFile -> SpecmaticConfigSource.fromPath(source.path).load().config.getLogConfigurationOrDefault()
    }
    val effectiveConfig = baseConfig.overrideMergeWith(LoggingConfiguration.from(opts))
    setLoggerUsing(effectiveConfig)
}
