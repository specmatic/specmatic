package application

import picocli.CommandLine.*
import io.specmatic.conversions.postmanCollectionToContracts
import io.specmatic.conversions.runTests
import io.specmatic.conversions.toFragment
import io.specmatic.core.*
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.logger
import io.specmatic.core.log.logException
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.utilities.openApiFromTraffic
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.license.core.cli.Category
import io.specmatic.mock.mockFromJSON
import io.swagger.v3.core.util.Yaml
import java.io.File
import java.util.concurrent.Callable

@Command(name = "import",
        mixinStandardHelpOptions = true,
        description = ["Converts a $APPLICATION_NAME example file into an OpenAPI spec file, or a Postman file into an OpenAPI spec file with examples if present"])
@Category("Contract conversion")
class ImportCommand : Callable<Int> {
    @Parameters(description = ["File to convert"], index = "0")
    lateinit var path: String

    @Option(names = ["--output"], description = ["Write the specification into this file"], required = false)
    var userSpecifiedOutFile: String? = null

    @Option(names = ["--debug"], required = false, defaultValue = "false")
    var verbose: Boolean? = null

    override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = verbose))
        return logException {
            when {
                path.endsWith(".postman_collection.json") ->
                    convertPostman(path, userSpecifiedOutFile)
                path.endsWith(".json") ->
                    convertStub(path, userSpecifiedOutFile)
                else -> {
                    throw Exception("File type not recognized. You can import Postman collections (extension .postman_collection.json) and Specmatic example files.")
                }
            }
        }
    }
}

fun convertStub(path: String, userSpecifiedOutFile: String?) {
    val inputFile = File(path)
    val stub = mockFromJSON(jsonStringToValueMap(inputFile.readText()))
    val openApi = openApiFromTraffic("New Feature", listOf(NamedStub("New scenario", stub)))
    val openApiYAML = Yaml.pretty(openApi)

    val outFile = userSpecifiedOutFile ?: "${inputFile.nameWithoutExtension}.yaml"

    LicenseResolver.utilize(
        product = LicensedProduct.OPEN_SOURCE,
        feature = SpecmaticFeature.EXAMPLES_IMPORTED_FROM_STUB,
        protocol = listOfNotNull(stub.protocol)
    )

    writeOut(openApiYAML, outFile)
}

fun convertPostman(path: String, userSpecifiedOutPath: String?) {
    val inputFile = File(path)
    val contracts = postmanCollectionToContracts(inputFile.readText())

    for (contract in contracts) runTests(contract)

    when (contracts.size) {
        1 -> {
            val outPath = userSpecifiedOutPath ?: "${inputFile.nameWithoutExtension}.yaml"
            writeOut(Yaml.pretty(contracts.first().feature.toOpenApi()), outPath, toFragment(contracts.first().baseURLInfo))
        }
        else -> {
            for (contract in contracts) {
                val (_, feature, baseURLInfo, _) = contract
                val outFilePath = userSpecifiedOutPath ?: "${inputFile.nameWithoutExtension}.yaml"
                writeOut(Yaml.pretty(feature.toOpenApi()), outFilePath, toFragment(baseURLInfo))
            }
        }
    }
}

private fun writeOut(content: String, outputFilePath: String, hostAndPort: String? = null) {
    val outputFile = File(outputFilePath)

    val tag = if(hostAndPort != null) "-${hostAndPort.replace(":", "-")}" else ""

    fileWithTag(outputFile, tag).writeText(content)
    logger.log("Written to file ${fileWithTag(outputFile, tag).path}")
}

private fun fileWithTag(file: File, tag: String): File {
    val taggedFilePath = "${file.absoluteFile.parentFile.path.removeSuffix(File.separator)}${File.separator}${file.nameWithoutExtension}$tag.${file.extension}"
    return File(taggedFilePath)
}
