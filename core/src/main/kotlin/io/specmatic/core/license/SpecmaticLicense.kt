package io.specmatic.core.license

import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.license.core.providers.LicensedProductProvider
import kotlinx.coroutines.asContextElement
import java.util.ServiceLoader
import java.util.UUID

/** Attributes embedded work to its host. Entitlements and usage policy remain in License. */
object SpecmaticLicense {
    private val host = ThreadLocal<LicensedProduct?>()
    private val distributionProduct by lazy {
        ServiceLoader.load(LicensedProductProvider::class.java).maxBy { it.priority }.product
    }

    fun product(default: LicensedProduct = LicensedProduct.OPEN_SOURCE): LicensedProduct = host.get() ?: when {
        default == LicensedProduct.OPEN_SOURCE -> distributionProduct
        else -> default
    }

    fun <T> withProduct(product: LicensedProduct, work: () -> T): T {
        val previous = host.get()
        host.set(product)
        return try { work() } finally {
            if (previous == null) host.remove() else host.set(previous)
        }
    }

    fun coroutineContext(product: LicensedProduct = product()) = host.asContextElement(product)

    /** Does not consume usage; the existing operation's charge point still owns consumption. */
    fun preflight(product: LicensedProduct) {
        LicenseResolver.license().blowUpIfLimitWillBeBreached(0, product)
    }

    fun utilize(
        product: LicensedProduct,
        feature: SpecmaticFeature,
        protocol: List<SpecmaticProtocol> = emptyList(),
        labels: Set<String> = emptySet(),
        context: UUID? = null,
    ) = LicenseResolver.utilize(product(product), feature, protocol, labels, context)
}
