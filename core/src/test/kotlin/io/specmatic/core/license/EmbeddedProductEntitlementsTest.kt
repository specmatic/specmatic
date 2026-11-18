package io.specmatic.core.license

import io.ktor.http.Url
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.specmatic.core.HttpRequest
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicenseType
import io.specmatic.license.core.LicenseV3
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.UserType
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID

class EmbeddedProductEntitlementsTest {
    @AfterEach
    fun restoreDiscovery() = unmockkObject(LicenseResolver)

    @Test
    fun `Studio and Core mocks share the same pool and the next request stops at exhaustion`() {
        val license = useLicense(setOf("core", "studio"), rateLimit = 1)
        val feature = pets()
        fun mock(product: LicensedProduct) = SpecmaticLicense.withProduct(product) {
            HttpStub(feature, port = ServerSocket(0).use { it.localPort })
        }
        mock(LicensedProduct.STUDIO).use { studio ->
            mock(LicensedProduct.CORE).use { core ->
                assertThat(studio.client.execute(HttpRequest("GET", "/pets")).status).isEqualTo(200)
                val rejected = core.client.execute(HttpRequest("GET", "/pets"))
                assertThat(rejected.status).isEqualTo(500)
                assertThat(rejected.body.toStringLiteral()).contains("exceeded the limit of 1 invocations")
                assertThat(license.remainingLimitValue).isZero()
            }
        }
    }

    @ParameterizedTest
    @CsvSource("OPEN_SOURCE,OSS", "STUDIO,ENTERPRISE", "CORE,ENTERPRISE")
    fun `independent and embedded mocks preserve one charge per served request`(product: LicensedProduct, type: LicenseType) {
        val license = useLicense(setOf(product.id), rateLimit = 2, type = type)
        val feature = pets()
        val port = ServerSocket(0).use { it.localPort }
        val stub = SpecmaticLicense.withProduct(product) { HttpStub(feature, port = port) }
        stub.use {
            assertThat(it.client.execute(HttpRequest("GET", "/pets")).status).isEqualTo(200)
            assertThat(license.remainingLimitValue).isEqualTo(1)
        }
    }

    @Test
    fun `Studio mock retains host identity after startup and rejects a Core-only license`() {
        val license = useLicense(setOf("core"), rateLimit = 3)
        val feature = pets()
        val port = ServerSocket(0).use { it.localPort }
        val stub = SpecmaticLicense.withProduct(LicensedProduct.STUDIO) { HttpStub(feature, port = port) }

        stub.use {
            val response = it.client.execute(HttpRequest("GET", "/pets"))
            assertThat(response.status).isEqualTo(500)
            assertThat(response.body.toStringLiteral()).contains("Your license does not include Specmatic Studio")
            assertThat(license.remainingLimitValue).isEqualTo(3)
        }
    }

    private fun useLicense(products: Set<String>, rateLimit: Int, type: LicenseType = LicenseType.ENTERPRISE): LicenseV3 {
        val license = LicenseV3(
            "customer", Instant.now().minusSeconds(60), Duration.ofDays(1), type,
            rateLimit, "organization", Url("http://localhost:9000"), UUID.randomUUID(), UserType.INDIVIDUAL, products,
        )
        mockkObject(LicenseResolver)
        every { LicenseResolver.license() } returns license
        return license
    }

    private fun pets() = parseGherkinStringToFeature("""
        Feature: Pets
          Scenario: Get pets
            When GET /pets
            Then status 200
    """.trimIndent())
}
