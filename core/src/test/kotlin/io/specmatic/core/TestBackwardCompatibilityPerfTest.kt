package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.File
import java.time.Duration.ofSeconds
import kotlin.system.measureTimeMillis

class TestBackwardCompatibilityPerfTest {
    @Test
    fun `backward compatibility check performance should not regress`() {
        val specFile = File(javaClass.getResource("/openapi/multi_res_perf.yaml")!!.toURI())
        val oldFeature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()
        val newFeature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        var results: Results? = null
        assertTimeoutPreemptively(ofSeconds(5)) {
            val timeTaken = measureTimeMillis { results = testBackwardCompatibility(oldFeature, newFeature) }
            System.err.println("Backward compatibility check took ${timeTaken}ms")
        }

        val compatibilityResults = results ?: error("Backward compatibility check did not complete")
        assertThat(compatibilityResults.success()).withFailMessage(compatibilityResults.report()).isTrue
    }
}
