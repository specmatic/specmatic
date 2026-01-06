package io.specmatic.core

import net.minidev.json.annotate.JsonIgnore
import java.io.File

data class ProxyConfig(val port: Int, val targetUrl: String, val consumes: List<String> = emptyList()) {
    @JsonIgnore
    fun mockSpecifications(): List<File> = consumes.map(::File).map(File::getCanonicalFile)
}
