package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.Flags
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckOpenAPIBackwardCompatibilityTest {

    @BeforeAll
    fun setUp() {
        logger = Verbose()
//        System.setProperty(Flags.IGNORE_INLINE_EXAMPLES, "true")
//        System.setProperty(Flags.MAX_TEST_REQUEST_COMBINATIONS, "1")
    }

    val oldFile = resource("backward-compatibility/mandate-management-service/old.yaml")
    val newFile = resource("backward-compatibility/mandate-management-service/new.yaml")

    @Test
    fun `should not hang`() {
//        assertTimeoutPreemptively<Results>(Duration.ofSeconds(60)) {
            val oldFeature = OpenApiSpecification.fromFile(oldFile.absolutePath).toFeature()
            val newFeature = OpenApiSpecification.fromFile(newFile.absolutePath).toFeature()
            testBackwardCompatibility(oldFeature, newFeature)
//        }
    }

    @Test
    fun `contract tests should not hang`() {
        val oldFeature = OpenApiSpecification.fromFile(oldFile.absolutePath)
            .toFeature()
            .enableGenerativeTesting()

        val results = oldFeature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.ok("")
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    companion object {
        private fun resource(path: String): File {
            val url = CheckOpenAPIBackwardCompatibilityTest::class.java.classLoader.getResource(path)
                ?: error("Resource not found on classpath: $path")
            return File(url.toURI())
        }
    }
}
