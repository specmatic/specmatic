package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration

class CheckOpenAPIBackwardCompatibilityTest {

    @Test
    fun `should not hang`() {
        val oldFile = resource("backward-compatibility/mandate-management-service/old.yaml")
        val newFile = resource("backward-compatibility/mandate-management-service/new.yaml")

        assertTimeoutPreemptively<Results>(Duration.ofSeconds(60)) {
            val oldFeature = OpenApiSpecification.fromFile(oldFile.absolutePath).toFeature()
            val newFeature = OpenApiSpecification.fromFile(newFile.absolutePath).toFeature()
            testBackwardCompatibility(oldFeature, newFeature)
        }
    }

    companion object {
        private fun resource(path: String): File {
            val url = CheckOpenAPIBackwardCompatibilityTest::class.java.classLoader.getResource(path)
                ?: error("Resource not found on classpath: $path")
            return File(url.toURI())
        }
    }
}
