package io.specmatic.core.config

import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.license.core.providers.LicenseConfigValuesProvider
import io.specmatic.reporter.config.ReportConfigProvider
import java.nio.file.Path

class ConfigValuesProviderImpl : LicenseConfigValuesProvider, ReportConfigProvider {
    override fun licensePath(): Path? {
        val specmaticConfig = loadSpecmaticConfigOrDefault(getConfigFilePath())
        return specmaticConfig.getLicensePath()
    }

    override fun getReportDirPath(): Path {
        val specmaticConfig = loadSpecmaticConfigOrDefault(getConfigFilePath())
        return specmaticConfig.getReportDirPath()
    }
}