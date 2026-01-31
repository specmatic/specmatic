package io.specmatic.loader

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import java.io.File

class RecursiveSpecificationAndExampleLoader(private val specmaticConfig: SpecmaticConfig, private val strategy: LoaderStrategy) {
    fun loadAll(directory: File): List<SpecificationWithExamples> {
        logger.boundary()
        logger.log("Scanning for specification and examples in entry directory ${directory.path}")

        val specifications = findAllSpecifications(directory)
        logger.log("Found ${specifications.size} total specifications")
        logger.boundary()

        return specifications.map { specFile ->
            logger.boundary()
            logger.log("Scanning for examples related to ${specFile.path}")
            SpecificationWithExamples(
                specFile = specFile,
                examples = findExamplesForSpecification(specFile, directory)
            )
        }
    }

    fun load(specFile: File, entryDirectory: File = specFile.parentFile): SpecificationWithExamples? {
        if (!specFile.isFile || !strategy.isCompatibleSpecification(specFile)) return null
        logger.boundary()
        logger.log("Scanning for examples related to ${specFile.path}")
        return SpecificationWithExamples(
            specFile = specFile,
            examples = findExamplesForSpecification(specFile, entryDirectory)
        )
    }

    private fun findAllSpecifications(directory: File): List<File> {
        if (!directory.isDirectory) {
            logger.debug("Skipping non-directory entry ${directory.path}")
            return emptyList()
        }

        logger.debug("Scanning directory ${directory.path}")
        return directory.listFiles()?.flatMap { file ->
            when {
                file.isDirectory -> findAllSpecifications(file)
                file.isFile && strategy.isCompatibleSpecification(file) -> {
                    logger.log("Specification found at ${file.path}")
                    listOf(file)
                }
                else -> {
                    logger.debug("Skipping unsupported entry ${file.path}")
                    emptyList()
                }
            }
        }.orEmpty()
    }

    private fun findExamplesForSpecification(specFile: File, entryDirectory: File): ExampleDiscoveryResult {
        val specDirectory = specFile.parentFile ?: run {
            logger.debug("Skipping ${specFile.path}, no parent directory")
            return ExampleDiscoveryResult.empty()
        }

        val currentLevelExamples = findExamplesInCurrentLevel(specFile, specDirectory)
        val parentLevelExamples = findExamplesInParentLevels(specFile, specDirectory, entryDirectory)
        val combinedExamples = currentLevelExamples.plus(parentLevelExamples)

        logger.log("Found ${combinedExamples.specExamples.size} spec examples and ${combinedExamples.sharedExamples.size} shared example")
        return combinedExamples
    }

    private fun findExamplesInCurrentLevel(specFile: File, directory: File): ExampleDiscoveryResult {
        logger.debug("Checking sibling directories in ${directory.path}")
        return directory.listFiles()?.filter { it.isDirectory }?.map { sibling ->
            when (getDirectoryMatchType(sibling, specFile, directory)) {
                DirectoryMatchType.SPEC_EXAMPLE -> {
                    logger.debug("Spec example directory matched at ${sibling.path}")
                    ExampleDiscoveryResult(specExamples = loadExamplesFromDirectory(sibling))
                }
                DirectoryMatchType.SHARED_EXAMPLE -> {
                    logger.debug("Shared example directory matched at ${sibling.path}")
                    ExampleDiscoveryResult(sharedExamples = loadExamplesFromDirectory(sibling))
                }
                DirectoryMatchType.NONE -> ExampleDiscoveryResult.empty()
            }
        }?.fold(ExampleDiscoveryResult.empty()) { acc, result -> acc + result } ?: ExampleDiscoveryResult.empty()
    }

    private fun findExamplesInParentLevels(specFile: File, startDirectory: File, entryDirectory: File): ExampleDiscoveryResult {
        logger.debug("Checking parent directories for shared examples")
        return generateSequence(startDirectory.parentFile, nextFunction = { it.parentFile })
            .takeWhile { it.startsWith(entryDirectory) }
            .map { parent ->
                logger.debug("Inspecting parent directory ${parent.path}")
                findExamplesInParentLevel(specFile, parent)
            }
            .fold(ExampleDiscoveryResult.empty()) { acc, result -> acc + result }
    }

    private fun findExamplesInParentLevel(specFile: File, parentDirectory: File): ExampleDiscoveryResult {
        return parentDirectory.listFiles()?.filter { it.isDirectory }?.map { sibling ->
            when (getDirectoryMatchType(sibling, specFile, parentDirectory)) {
                DirectoryMatchType.SPEC_EXAMPLE -> findExamplesRecursively(sibling, specFile)
                DirectoryMatchType.SHARED_EXAMPLE -> ExampleDiscoveryResult(sharedExamples = loadExamplesFromDirectory(sibling))
                DirectoryMatchType.NONE -> ExampleDiscoveryResult.empty()
            }
        }?.fold(ExampleDiscoveryResult.empty()) { acc, result -> acc + result } ?: ExampleDiscoveryResult.empty()
    }

    private fun findExamplesRecursively(directory: File, specFile: File): ExampleDiscoveryResult {
        val childDirectories = directory.listFiles()?.filter { it.isDirectory } ?: return ExampleDiscoveryResult.empty()
        return childDirectories.map { child ->
            when (getDirectoryMatchType(child, specFile, directory)) {
                DirectoryMatchType.SPEC_EXAMPLE -> {
                    val recursiveFinds = findExamplesRecursively(child, specFile)
                    val levelFinds = ExampleDiscoveryResult(specExamples = loadExamplesFromDirectory(child))
                     levelFinds.plus(recursiveFinds)
                }
                DirectoryMatchType.SHARED_EXAMPLE -> ExampleDiscoveryResult(sharedExamples = loadExamplesFromDirectory(child))
                DirectoryMatchType.NONE -> findExamplesRecursively(child, specFile)
            }
        }.fold(ExampleDiscoveryResult.empty()) { acc, result -> acc + result }
    }

    private fun getDirectoryMatchType(directory: File, specFile: File, parentContext: File): DirectoryMatchType {
        val globalSettings = specmaticConfig.getGlobalSettingsOrDefault()
        val sharedTemplates: List<String> = globalSettings.getSharedExampleDirTemplates()
        val specTemplate: String = globalSettings.getSpecExampleDirTemplate()
        val dirName = directory.name

        return when {
            dirName == resolveTemplate(specTemplate, specFile, parentContext) -> DirectoryMatchType.SPEC_EXAMPLE
            sharedTemplates.any { dirName == resolveTemplate(it, specFile, parentContext) } -> DirectoryMatchType.SHARED_EXAMPLE
            else -> DirectoryMatchType.NONE
        }
    }

    private fun loadExamplesFromDirectory(directory: File): List<File> {
        val examples = directory.listFiles()?.filter { it.isFile && strategy.isCompatibleExample(it) }.orEmpty()
        logger.debug("Loaded ${examples.size} examples from ${directory.path}")
        return examples
    }

    private fun resolveTemplate(template: String, specFile: File, parentContext: File): String {
        return template
            .replace("<SPEC_FILE_NAME>", specFile.nameWithoutExtension)
            .replace("<SPEC_EACH_PARENT>", parentContext.name)
    }

    private enum class DirectoryMatchType { SPEC_EXAMPLE, SHARED_EXAMPLE, NONE }
}

data class SpecificationWithExamples(val specFile: File, val examples: ExampleDiscoveryResult)

data class ExampleDiscoveryResult(val specExamples: List<File> = emptyList(), val sharedExamples: List<File>  = emptyList()) {
    operator fun plus(other: ExampleDiscoveryResult) = ExampleDiscoveryResult(
        specExamples = this.specExamples + other.specExamples,
        sharedExamples = this.sharedExamples + other.sharedExamples
    )

    companion object {
        fun empty() = ExampleDiscoveryResult(emptyList(), emptyList())
    }
}
