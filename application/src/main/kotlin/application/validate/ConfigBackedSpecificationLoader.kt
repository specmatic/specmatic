package application.validate

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.ContractsSelectorPredicate
import io.specmatic.core.utilities.GitRepo
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import io.specmatic.loader.SpecificationWithExamples
import java.io.File

class ConfigBackedSpecificationLoader(
    private val specmaticConfig: SpecmaticConfig,
    private val classifier: RecursiveSpecificationAndExampleClassifier,
    private val loadSources: () -> List<ContractSource> = { specmaticConfig.loadSources() },
    private val configFileProvider: () -> File = { File(getConfigFilePath()).canonicalFile }
) {
    fun load(): List<SpecificationWithExamples> {
        val configFile = configFileProvider().canonicalFile
        if (!configFile.exists()) return emptyList()

        logger.boundary()
        logger.log("Loading specifications declared in ${configFile.path}")

        val classificationWorkingDirectory = configFile.parentFile?.canonicalFile ?: File(".").canonicalFile
        val contractLoadingBaseDir = classificationWorkingDirectory.resolve(".specmatic").canonicalFile
        val loadedSpecifications = loadSpecifications(configFile, classificationWorkingDirectory, contractLoadingBaseDir)
            .distinctBy { it.specFile.normalizedPath() }

        logger.log("Resolved ${loadedSpecifications.size} specifications from config")
        logger.boundary()

        return loadedSpecifications
    }

    private fun loadSpecifications(
        configFile: File,
        classificationWorkingDirectory: File,
        contractLoadingBaseDir: File
    ): List<SpecificationWithExamples> {
        return loadSources().flatMap { source ->
            val contractLoadingWorkingDirectory =
                if (source is GitRepo) contractLoadingBaseDir.canonicalPath else classificationWorkingDirectory.canonicalPath
            val contractPathDataList = source.loadContracts(
                { contractSource -> contractSource.testContracts + contractSource.stubContracts },
                contractLoadingWorkingDirectory,
                configFile.canonicalPath
            )

            contractPathDataList.mapNotNull { contractPathData ->
                val specificationFile = File(contractPathData.path).canonicalFile
                val configuredPath = contractPathData.specificationPath ?: contractPathData.path
                val descriptor = source.pathDescriptor(configuredPath).ifBlank { configuredPath }

                if (!specificationFile.exists()) {
                    logger.log("WARNING: Specification '$descriptor' from config '${configFile.canonicalPath}' could not be found at '${contractPathData.path}'. It will be ignored.")
                    return@mapNotNull null
                }

                logger.log("Using specification declared in config: $descriptor -> ${specificationFile.path}")
                classifier.load(specificationFile, classificationWorkingDirectory)
            }
        }
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}
