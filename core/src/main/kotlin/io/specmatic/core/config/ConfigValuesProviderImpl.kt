package io.specmatic.core.config

import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.license.core.providers.LicenseConfigValuesProvider
import java.nio.file.Path

class ConfigValuesProviderImpl : LicenseConfigValuesProvider {
    override fun licensePath(): Path? {
        val specmaticConfig = loadSpecmaticConfigOrDefault(getConfigFilePath())
        return specmaticConfig.getLicensePath()
    }
}