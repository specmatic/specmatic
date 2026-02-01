package io.specmatic.loader

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.contains
import io.specmatic.core.JSON
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.mock.MOCK_HTTP_REQUEST
import io.specmatic.mock.MOCK_HTTP_RESPONSE
import io.specmatic.mock.PARTIAL
import io.specmatic.stub.isSupportedAPISpecification
import java.io.File

interface LoaderStrategy {
    fun isCompatibleSpecification(file: File, specmaticConfig: SpecmaticConfig): Boolean
    fun isCompatibleExample(file: File, specmaticConfig: SpecmaticConfig): Boolean
}

class OpenApiLoaderStrategy : LoaderStrategy {
    override fun isCompatibleSpecification(file: File, specmaticConfig: SpecmaticConfig): Boolean {
        return isSupportedAPISpecification(file.canonicalPath)
    }

    override fun isCompatibleExample(file: File, specmaticConfig: SpecmaticConfig): Boolean {
        if (!file.isFile || !file.canRead() || file.extension != JSON) return false
        return try {
            file.reader().use { reader ->
                val tree = objectMapper.readTree(reader)
                tree.contains(MOCK_HTTP_REQUEST) || tree.contains(MOCK_HTTP_RESPONSE) || tree.contains(PARTIAL)
            }
        } catch (e: Throwable) {
            logger.debug(e, "Could not parse potential example at ${file.canonicalPath}")
            false
        }
    }

    companion object { private val objectMapper = ObjectMapper() }
}
