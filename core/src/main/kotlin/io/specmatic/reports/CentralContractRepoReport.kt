package io.specmatic.reports

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.stub.hasOpenApiFileExtension
import io.specmatic.stub.isOpenAPI
import java.io.File

class CentralContractRepoReport {
    fun generate(currentWorkingDir: String = ""): CentralContractRepoReportJson {
        val searchPath = File(currentWorkingDir).canonicalPath
        logger.log("Searching for specification files at: $searchPath")
        val specifications = findSpecifications(searchPath)

        val openAPISpecificationRows = getOpenAPISpecificationRows(
            specifications[SpecType.OPENAPI].orEmpty(),
            searchPath
        )
        val asyncAPISpecificationRows = getAsyncAPISpecificationRows(
            specifications[SpecType.ASYNCAPI].orEmpty(),
            searchPath
        )
        return CentralContractRepoReportJson(
            openAPISpecificationRows + asyncAPISpecificationRows
        )
    }

    private fun getOpenAPISpecificationRows(
        specifications: List<File>,
        currentWorkingDir: String
    ): List<SpecificationRow> {
        val currentWorkingDirPath = File(currentWorkingDir).absoluteFile

        return specifications.mapNotNull {
            try {
                val feature = OpenApiSpecification.fromYAML(it.readText(), it.path).toFeature()
                Pair(it, feature)
            }
            catch (e:Throwable){
                logger.log("Could not parse ${it.path} due to the following error:")
                logger.log(exceptionCauseMessage(e))
                null
            }
        }
       .filter { (spec, feature) ->
            if (feature.scenarios.isEmpty()) {
                logger.log("Excluding specification: ${spec.path} as it does not have any paths ")
            }
            feature.scenarios.isNotEmpty()
        }.map { (spec, feature) ->
            SpecificationRow(
                spec.relativeTo(File("").canonicalFile).path,
                feature.serviceType,
                feature.scenarios.map {
                    OpenAPISpecificationOperation(
                        convertPathParameterStyle(it.path),
                        it.method,
                        it.httpResponsePattern.status
                    )
                }
            )
        }
    }

    private fun findSpecifications(currentDirectoryPath: String): Map<SpecType, List<File>> {
        val currentDirectory = File(currentDirectoryPath)
        val openApiSpecifications = mutableListOf<File>()
        val asyncApiSpecifications = mutableListOf<File>()
        val allFiles = currentDirectory.listFiles() ?: emptyArray()
        for (file in allFiles) {
            if (file.isDirectory) {
                val specMap = findSpecifications(file.canonicalPath)
                openApiSpecifications.addAll(specMap[SpecType.OPENAPI].orEmpty())
                asyncApiSpecifications.addAll(specMap[SpecType.ASYNCAPI].orEmpty())
            } else {
                if (hasOpenApiFileExtension(file.canonicalPath) && isOpenAPI(file.canonicalPath)) {
                    openApiSpecifications.add(file)
                }
                 if (hasAsyncApiFileExtension(file.canonicalPath) && isAsyncAPI(file.canonicalPath)) {
                     asyncApiSpecifications.add(file)
                 }
            }
        }
        return mapOf(
            SpecType.OPENAPI to openApiSpecifications,
            SpecType.ASYNCAPI to asyncApiSpecifications
        )
    }

    private enum class SpecType {
        OPENAPI,
        ASYNCAPI
    }
}