package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RefOrValueTest {
    @Test
    fun `resolveElseThrow should deserialize merged ref and extras into typed test service config`() {
        val resolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Map<*, *> {
                return mapOf(
                    "definitions" to emptyList<Map<String, Any>>(),
                    "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-reference")),
                    "settings" to mapOf("strictMode" to false)
                )
            }
        }

        val ref: RefOrValue<CommonServiceConfig<TestRunOptions, TestSettings>> = RefOrValue.Reference(
            ref = "#/components/services/myTestService",
            extra = mapOf(
                "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-extra")),
                "settings" to mapOf("timeoutInMilliseconds" to 1234)
            )
        )

        val resolved = ref.resolveElseThrow(resolver)
        val runOptions = resolved.runOptions!!.resolveElseThrow(resolver)
        val settings = resolved.settings!!.resolveElseThrow(resolver)
        assertThat(runOptions).isInstanceOf(TestRunOptions::class.java)
        assertThat(runOptions.openapi?.baseUrl).isEqualTo("http://from-extra")
        assertThat(settings).isInstanceOf(TestSettings::class.java)
        assertThat(settings.timeoutInMilliseconds).isEqualTo(1234)
    }

    @Test
    fun `resolveElseThrow should deserialize merged ref and extras into typed mock service config`() {
        val resolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Map<*, *> {
                return mapOf(
                    "definitions" to emptyList<Map<String, Any>>(),
                    "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-reference")),
                    "settings" to mapOf("delayInMilliseconds" to 100)
                )
            }
        }

        val ref: RefOrValue<CommonServiceConfig<MockRunOptions, MockSettings>> = RefOrValue.Reference(
            ref = "#/components/services/myMockService",
            extra = mapOf(
                "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-extra")),
                "settings" to mapOf("strictMode" to true)
            )
        )

        val resolved = ref.resolveElseThrow(resolver)
        val runOptions = resolved.runOptions!!.resolveElseThrow(resolver)
        val settings = resolved.settings!!.resolveElseThrow(resolver)
        assertThat(runOptions).isInstanceOf(MockRunOptions::class.java)
        assertThat(runOptions.openapi?.baseUrl).isEqualTo("http://from-extra")
        assertThat(settings).isInstanceOf(MockSettings::class.java)
        assertThat(settings.strictMode).isTrue()
    }

    @Test
    fun `toExampleDirs should resolve referenced examples as typed RefOrValue entries`() {
        val resolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Any {
                return listOf(
                    mapOf("directories" to listOf("examples-from-ref"))
                )
            }
        }

        val data = Data(
            examples = RefOrValue.Reference(
                ref = "#/components/examples/testExamples"
            )
        )

        assertThat(data.toExampleDirs(resolver)).containsExactly("examples-from-ref")
    }
}
