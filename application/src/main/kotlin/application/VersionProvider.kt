package application

import io.specmatic.specmatic.executable.VersionInfo
import picocli.CommandLine

class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        return arrayOf("Specmatic Core ${VersionInfo.describe()}")
    }
}
