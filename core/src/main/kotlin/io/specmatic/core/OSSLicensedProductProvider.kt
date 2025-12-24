package io.specmatic.core

import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.providers.LicensedProductProvider
import io.specmatic.specmatic.core.VersionInfo

class OSSLicensedProductProvider : LicensedProductProvider(
        priority = 0,
        product = LicensedProduct.OPEN_SOURCE,
        groupId = VersionInfo.group,
        artifactId = VersionInfo.name,
        version = VersionInfo.version,
    )
