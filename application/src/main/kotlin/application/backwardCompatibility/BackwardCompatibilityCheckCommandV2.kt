package application.backwardCompatibility

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.jsonObjectMapper
import io.specmatic.core.utilities.yamlObjectMapper
import io.specmatic.stub.isOpenAPI
import picocli.CommandLine.Command
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.pathString

@Command(
    name = "backward-compatibility-check",
    mixinStandardHelpOptions = true,
    description = ["Checks backward compatibility of OpenAPI specifications"]
)
class BackwardCompatibilityCheckCommandV2: BackwardCompatibilityCheckBaseCommand() {

    override fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): Results {
        return testBackwardCompatibility(oldFeature as Feature, newFeature as Feature)
    }

    private fun isYAML(string: String): Boolean {
        return try {
            yamlObjectMapper.readValue(string, Map::class.java)
            true
        } catch(e: Throwable) {
            false
        }
    }

    private fun isJSON(string: String): Boolean {
        return try {
            jsonObjectMapper.readValue(string, Map::class.java)
            true
        } catch(e: Throwable) {
            false
        }
    }

    override fun File.isValidFileFormat(): Boolean {
        if (this.extension !in CONTRACT_EXTENSIONS) return false

        val content = this.readText()

        return when(this.extension.lowercase()) {
            "yaml", "yml" -> isYAML(content)
            "json" -> isJSON(content)
            "spec" -> isGherkin(content)
            else -> true
        }
    }

    override fun File.isValidSpec(): Boolean {
        return isValidSpec(this)
    }

    private fun isGherkin(content: String): Boolean {
        return try {
            parseGherkinStringToFeature(content)
            true
        } catch(e: Throwable) {
            false
        }
    }

    override fun getFeatureFromSpecPath(path: String): Feature {
        logger.disableInfoLogging()
        return OpenApiSpecification.fromFile(path).toFeature().also {
            logger.enableInfoLogging()
        }
    }

    override fun regexForMatchingReferred(schemaFileName: String) = schemaFileName

    override fun getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch: Set<String>): Set<String> {
        data class CollectedFiles(
            val specifications: MutableSet<String> = mutableSetOf(),
            val examplesMissingSpecifications: MutableList<String> = mutableListOf(),
            val ignoredFiles: MutableList<String> = mutableListOf()
        )

        val collectedFiles = filesChangedInCurrentBranch.fold(CollectedFiles()) { acc, filePath ->
            val path = Paths.get(filePath)
            val examplesDir = getParentExamplesDirectory(path)

            if (examplesDir == null) {
                acc.ignoredFiles.add(filePath)
            } else {
                val parentPath = examplesDir.parent
                val strippedPath = parentPath.resolve(examplesDir.fileName.toString().removeSuffix("_examples"))
                val specFiles = findSpecFiles(strippedPath)

                if (specFiles.isNotEmpty()) {
                    acc.specifications.addAll(specFiles.map { it.toString() })
                } else {
                    acc.examplesMissingSpecifications.add(filePath)
                }
            }
            acc
        }

        val result = collectedFiles.specifications.toMutableSet()

        collectedFiles.examplesMissingSpecifications.forEach { filePath ->
            val path = Paths.get(filePath)
            val examplesDir = getParentExamplesDirectory(path)
            if (examplesDir != null) {
                val parentPath = examplesDir.parent
                val strippedPath = parentPath.resolve(examplesDir.fileName.toString().removeSuffix("_examples"))
                val specFiles = findSpecFiles(strippedPath)
                if (specFiles.isNotEmpty()) {
                    result.addAll(specFiles.map { it.toString() })
                } else {
                    result.add("${strippedPath}.yaml")
                }
            }
        }

        return result
    }

    fun getParentExamplesDirectory(path: Path): Path? {
        return generateSequence(path, Path::getParent).find {
            it.pathString.endsWith("_examples") || it.pathString.endsWith("_tests")
        }
    }

    override fun areExamplesValid(feature: IFeature, which: String): Boolean {
        feature as Feature
        return try {
            feature.validateExamplesOrException()
            true
        } catch (t: Throwable) {
            println()
            false
        }
    }

    override fun getUnusedExamples(feature: IFeature): Set<String> {
        feature as Feature
        return feature.loadExternalisedExamplesAndListUnloadableExamples().second
    }

    private fun findSpecFiles(path: Path): List<Path> {
        val extensions = CONTRACT_EXTENSIONS
        return extensions.map { path.resolveSibling(path.fileName.toString() + it) }
            .filter { Files.exists(it) && (isOpenAPI(it.pathString) || it.extension in listOf(WSDL, CONTRACT_EXTENSION)) }
    }
}

internal fun isValidSpec(file: File): Boolean {
    if (file.extension !in CONTRACT_EXTENSIONS) return false
    return OpenApiSpecification.isParsable(file.path)
}
