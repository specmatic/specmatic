package io.specmatic.core.config.v3.spi

import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.full.safeCast

interface ProtocolConfigServerExtractor {
    fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin?

    companion object {
        private val prioritizedExtractors = CopyOnWriteArrayList<ProtocolConfigServerExtractor>()
        private val serviceLoadedExtractors by lazy {
            ServiceLoader.load(ProtocolConfigServerExtractor::class.java).toList()
        }

        fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin? {
            val extractors = prioritizedExtractors.toList().plus(serviceLoadedExtractors)
            return extractors.firstNotNullOfOrNull { it.extractNetworkOrigin(configuration) }
        }

        internal fun register(extractor: ProtocolConfigServerExtractor): AutoCloseable {
            prioritizedExtractors.add(0, extractor)
            return AutoCloseable {
                prioritizedExtractors.removeIf { it === extractor }
            }
        }

        internal fun <T> with(extractor: ProtocolConfigServerExtractor, block: () -> T): T {
            val registration = register(extractor)
            return try {
                block()
            } finally {
                registration.close()
            }
        }
    }
}

abstract class TypedProtocolConfigServerExtractor<G : IRunOptions>(
    private val globalType: KClass<G>,
    private val runOptionType: RunOptionType,
) : ProtocolConfigServerExtractor {
    protected open fun extractGlobalNetworkOrigin(configuration: G): ServerOrigin? = null

    protected open fun extractOverrideNetworkOrigin(configuration: IRunOptionSpecification): ServerOrigin? = null

    final override fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin? {
        if (configuration.runOptionType != runOptionType) return null
        val runOptions = globalType.safeCast(configuration.runOptions) ?: return null
        return when (configuration) {
            is ProtocolConfig.Global<*> -> runOptions.let(::extractGlobalNetworkOrigin)
            is ProtocolConfig.Override<*> -> extractOverrideNetworkOrigin(configuration.spec)
        }
    }
}
