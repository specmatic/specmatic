package io.specmatic.core.config

import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification

class RawRunOptionSpecification(private val config: Map<String, Any>): IRunOptionSpecification {
    override fun getId(): String? = null
    override fun getOverlayFilePath(): String? = null
    override fun getConfig(): Map<String, Any> = config
}

class SpecmaticSpecConfig(val baseUrl: String? = null, val spec: IRunOptionSpecification? = null, val config: Map<String, Any> = emptyMap()) {
    constructor(raw: Map<String, Any>): this(spec = RawRunOptionSpecification(raw))

    fun resolvedConfig(): Map<String, Any> {
        return if(spec?.getConfig().isNullOrEmpty()) config
        else spec?.getConfig().orEmpty()
    }
}
