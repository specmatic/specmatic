package application

import picocli.CommandLine.*
import io.specmatic.conversions.postmanCollectionToGherkin
import io.specmatic.conversions.runTests
import io.specmatic.conversions.toFragment
import io.specmatic.core.*
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.log.logException
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.utilities.parseXML
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.license.core.cli.Category
import io.specmatic.mock.mockFromJSON
import io.swagger.v3.core.util.Yaml
import java.io.File
import java.util.concurrent.Callable

@Command(name = "import",
        mixinStandardHelpOptions = true,
        description = ["Converts a $APPLICATION_NAME stub, Postman or WSDL file into a $APPLICATION_NAME spec file"])
@Category("Contract conversion")
class ImportCommand : Callable<Int> {
    @Parameters(description = ["File to convert"], index = "0")
    lateinit var path: String

    @Option(names = ["--output"], description = ["Write the contract into this file"], required = false)
    var userSpecifiedOutFile: String? = null

    @Option(names = ["--debug"], required = false, defaultValue = "false")
    var verbose: Boolean = false

    override fun call(): Int {
        if(verbose)
            logger = Verbose()

        return logException {
            when {
                path.endsWith(".postman_collection.json") ->
                    convertPostman(path, userSpecifiedOutFile)
                path.endsWith(".json") ->
                    convertStub(path, userSpecifiedOutFile)
                path.endsWith(".wsdl") ->
                    convertWSDL(path, userSpecifiedOutFile)
                else -> {
                    throw Exception("File type not recognized. Support exceptions include .postman_collection.json (Postman), .json ($APPLICATION_NAME stub), .wsdl (WSDL)")
                }
            }
        }
    }
}

fun convertStub(path: String, userSpecifiedOutFile: String?) {
    val inputFile = File(path)
    val stub = mockFromJSON(jsonStringToValueMap(inputFile.readText()))
    val gherkin = toGherkinFeature(NamedStub("New scenario", stub))
    val openApiYAML = gherkinToOpenApiYAML(gherkin)

    val outFile = userSpecifiedOutFile ?: "${inputFile.nameWithoutExtension}.yaml"

    writeOut(openApiYAML, outFile)
}

private fun gherkinToOpenApiYAML(gherkin: String): String {
    val openApi = parseGherkinStringToFeature(gherkin).toOpenApi()
    return Yaml.pretty(openApi)
}

fun convertPostman(path: String, userSpecifiedOutPath: String?) {
    val inputFile = File(path)
    val contracts = postmanCollectionToGherkin(inputFile.readText())

    for (contract in contracts) runTests(contract)

    when (contracts.size) {
        1 -> {
            val outPath = userSpecifiedOutPath ?: "${inputFile.nameWithoutExtension}.yaml"
            writeOut(gherkinToOpenApiYAML(contracts.first().gherkin), outPath, toFragment(contracts.first().baseURLInfo))
        }
        else -> {
            for (contract in contracts) {
                val (_, gherkin, baseURLInfo, _) = contract
                val outFilePath = userSpecifiedOutPath ?: "${inputFile.nameWithoutExtension}.yaml"
                writeOut(gherkinToOpenApiYAML(gherkin), outFilePath, toFragment(baseURLInfo))
            }
        }
    }
}

fun convertWSDL(path: String, userSpecifiedOutFile: String?) {
    val inputFile = File(path)
    val inputFileContent = inputFile.readText()
    val wsdlXML = toXMLNode(parseXML(inputFileContent))
    val contract = WSDL(wsdlXML, path).convertToGherkin()

    val outFile = userSpecifiedOutFile ?: "${inputFile.nameWithoutExtension}.$CONTRACT_EXTENSION"

    writeOut(contract, outFile)
}

private fun writeOut(gherkin: String, outputFilePath: String, hostAndPort: String? = null) {
    val outputFile = File(outputFilePath)

    val tag = if(hostAndPort != null) "-${hostAndPort.replace(":", "-")}" else ""

    fileWithTag(outputFile, tag).writeText(gherkin)
    logger.log("Written to file ${fileWithTag(outputFile, tag).path}")
}

private fun fileWithTag(file: File, tag: String): File {
    val taggedFilePath = "${file.absoluteFile.parentFile.path.removeSuffix(File.separator)}${File.separator}${file.nameWithoutExtension}$tag.${file.extension}"
    return File(taggedFilePath)
}
