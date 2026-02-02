package io.specmatic.core.config

import io.specmatic.core.SpecmaticConfig
import java.io.File

interface SpecmaticVersionedConfig {
    fun transform(file: File? = null): SpecmaticConfig
}