package io.specmatic.core.config

import io.specmatic.core.SpecmaticConfigV1V2Common

interface SpecmaticVersionedConfig {
    fun transform(): SpecmaticConfigV1V2Common
}