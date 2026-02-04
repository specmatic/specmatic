package io.specmatic.core.config

data class VirtualServiceConfiguration(
    private val host: String? = null,
    private val port: Int? = null,
    private val specs: List<String>? = null,
    private val specsDirPath: String? = null,
    private val logsDirPath: String? = null,
    private val logMode: VSLogMode? = null,
    private val nonPatchableKeys: Set<String> = emptySet()
) {
    enum class VSLogMode {
        ALL,
        REQUEST_RESPONSE
    }

    fun getHost(): String? {
        return host
    }

    fun getPort(): Int? {
        return port
    }

    fun getSpecs(): List<String>? {
        return specs
    }

    fun getSpecsDirPath(): String? {
        return specsDirPath
    }

    fun getLogsDirPath(): String? {
        return logsDirPath
    }

    fun getLogMode(): VSLogMode? {
        return logMode
    }

    fun getNonPatchableKeys(): Set<String> {
        return nonPatchableKeys
    }
}
