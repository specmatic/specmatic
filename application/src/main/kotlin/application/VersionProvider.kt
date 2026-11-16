package application

import io.specmatic.specmatic.core.VersionInfo
import picocli.CommandLine

class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        return arrayOf("Specmatic Version: ${VersionInfo.describe()}")
    }
}
