package io.specmatic.core.config.v3.spi

import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptionType

sealed interface ProtocolConfig<out RunOpts : IRunOptions> {
    val runOptionType: RunOptionType
    val runOptions: RunOpts

    data class Global<out RunOpts : IRunOptions>(
        override val runOptions: RunOpts,
        override val runOptionType: RunOptionType,
    ) : ProtocolConfig<RunOpts>

    data class Override<out RunOpts : IRunOptions>(
        override val runOptions: RunOpts,
        val spec: IRunOptionSpecification,
        override val runOptionType: RunOptionType,
    ) : ProtocolConfig<RunOpts>
}
