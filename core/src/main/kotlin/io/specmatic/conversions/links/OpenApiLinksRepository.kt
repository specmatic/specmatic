package io.specmatic.conversions.links

import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.Scenario
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.Examples
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.listFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.utilities.Flags
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.links.Link
import io.swagger.v3.oas.models.responses.ApiResponse

data class OpenApiLinksRepository(
    private val openApiFilePath: String? = null,
    private val operationToLinks: Map<OpenApiOperationReference, List<OpenApiLink>> = emptyMap(),
    private val openApiLinksGraph: OpenApiLinksGraph = OpenApiLinksGraph()
) {
    private val links: List<OpenApiLink> = operationToLinks.flatMap { it.value }
    val size: Int = links.size

    fun getDefinedFor(scenario: Scenario): List<OpenApiLink> {
        return links.filter {
            it.matchesFor(scenario.operationMetadata?.operationId, scenario.status, scenario.requestContentType) ||
                it.matchesFor(scenario.path, scenario.method, scenario.status, scenario.requestContentType)
        }
    }

    // Use for testing, prefer scenario
    fun getDefinedFor(path: String, method: String, status: Int, operationId: String?, contentType: String?): List<OpenApiLink> {
        return links.filter {
            it.matchesFor(operationId, status, contentType) || it.matchesFor(path, method, status, contentType)
        }
    }

    fun getDefinedBy(path: String, method: String, status: Int, contentType: String?): List<OpenApiLink> {
        return links.filter { it.matchesBy(path, method, status, contentType) }
    }

    fun openApiLinksToExamples(links: List<OpenApiLink>, scenario: Scenario, lenient: Boolean = true): ReturnValue<List<Examples>> {
        if (links.isEmpty()) return HasValue(emptyList())

        val linkToRequests = links.mapNotNull { link ->
            link.toHttpRequest(scenario).realise(
                hasValue = { it, _ -> HasValue(Pair(link, it)) },
                orException = { e ->
                    val errorMessage = "Unexpected Exception while converting openApiLink ${link.name} to request"
                    if (!lenient) return@realise e.addDetails(errorMessage, "").cast()
                    logger.boundary()
                    logger.ofTheException(e.t, errorMessage)
                    logger.boundary()
                    null
                },
                orFailure = { f ->
                    val errorMessage = "Invalid Request in OpenApi Link ${link.name}"
                    if (!lenient) return@realise f.addDetails(errorMessage, "").cast()
                    logger.boundary()
                    logger.log(f.addDetails(errorMessage, "").failure.reportString())
                    logger.boundary()
                    null
                },
            )
        }.listFold().unwrapOrReturn {
            return it.cast()
        }

        val rows = linkToRequests.map { (link, request) ->
            // TODO: Handle parameter isolation, PARAMETERS.QUERY.<NAME> instead of <NAME>
            Row(
                name = link.name,
                isFromOpenApiLink = true,
                isPartial = link.isPartial,
                fileSource = openApiFilePath,
            ).updateRequest(request, scenario.httpRequestPattern, scenario.resolver)
        }

        return HasValue(
            listOf(
                Examples(
                    rows = rows,
                    columnNames = rows.flatMap(Row::columnNames),
                ),
            ),
        )
    }

    fun sortOpenApiScenariosBasedOnLinks(scenarios: List<Scenario>, lenient: Boolean): ReturnValue<List<Scenario>> {
        return openApiLinksGraph.sortScenariosBasedOnDependency(scenarios).realise(
            hasValue = { it, _ -> HasValue(it) },
            orException = { e ->
                if (!lenient) return@realise e.cast()
                logger.boundary()
                logger.ofTheException(e.t, "Unexpected Exception while generating execution order based on OpenApi links")
                logger.boundary()
                HasValue(scenarios)
            },
            orFailure = { f ->
                if (!lenient) return@realise f.cast()
                logger.boundary()
                logger.log(f.addDetails("Failed to generate execution order based on OpenApi Links", "").failure.reportString())
                logger.boundary()
                HasValue(scenarios)
            },
        )
    }

    companion object {
        private const val ENABLE_OPENAPI_LINKS = "SPECMATIC_ENABLE_LINKS"
        private val enableLinks: Boolean = Flags.getBooleanValue(ENABLE_OPENAPI_LINKS, true)

        fun from(openApi: OpenAPI, openApiFilePath: String? = null, lenient: Boolean = true): ReturnValue<OpenApiLinksRepository> {
            if (!enableLinks) return HasValue(OpenApiLinksRepository())

            val parser = if (lenient) {
                ::parseOpenApiLinkLenient
            } else {
                ::parseOpenApiLink
            }

            val parsedLinks = openApi.paths.orEmpty().flatMap { (path, pathItem) ->
                pathItem.readOperationsMap().flatMap { (method, operation) ->
                    operation.responses.orEmpty().flatMap { (response, apiResponse) ->
                        apiResponse.links.orEmpty().mapNotNull { (name, link) ->
                            val operationId = operation.operationId
                            val statusCode = response.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
                            val opReference = OpenApiOperationReference(path, method.name, statusCode, operationId)
                            parser(openApi, apiResponse, opReference, name, link)?.ifValue { it.byOperation to it }
                        }
                    }
                }
            }.listFold().unwrapOrReturn { return it.cast() }

            val links = parsedLinks.groupBy({ it.first }, { it.second })
            val openApiLinksGraph = parseOpenApiLinkGraph(links, lenient = lenient).unwrapOrReturn {
                return it.cast()
            }
            return HasValue(OpenApiLinksRepository(openApiFilePath, links, openApiLinksGraph))
        }

        private fun parseOpenApiLinkLenient(openAPI: OpenAPI, response: ApiResponse, byOperation: OpenApiOperationReference, linkName: String, openApiLink: Link): HasValue<OpenApiLink>? {
            val resolvedLink = if (openApiLink.`$ref` != null) {
                unReferenceLink(openAPI, openApiLink).realise(
                    hasValue = { it, _ -> it },
                    orException = { e ->
                        logger.boundary()
                        logger.ofTheException(e.t, "Failed to de-reference OpenApi Link $linkName")
                        logger.boundary()
                        null
                    },
                    orFailure = { f ->
                        logger.boundary()
                        logger.log(f.addDetails("Failed to de-reference OpenApi Link $linkName", linkName).failure.reportString())
                        logger.boundary()
                        null
                    },
                )
            } else { openApiLink } ?: return null

            val updatedRef = OpenApiOperationReference.updateContentType(byOperation, response, openApiLink).unwrapOrReturn {
                logger.boundary()
                logger.log(it.toFailure().reportString())
                logger.boundary()
                return null
            }

            val parseResult = OpenApiLink.from(openAPI, updatedRef, linkName, resolvedLink)
            return parseResult.realise(
                hasValue = { it, _ -> HasValue(it) },
                orException = { e ->
                    logger.boundary()
                    logger.ofTheException(e.t, "Unexpected Exception while parsing openApiLink $linkName")
                    logger.boundary()
                    null
                },
                orFailure = { f ->
                    logger.boundary()
                    logger.log(f.addDetails("Invalid OpenApi Link $linkName", "").failure.reportString())
                    logger.boundary()
                    null
                },
            )
        }

        private fun parseOpenApiLink(openAPI: OpenAPI, response: ApiResponse, byOperation: OpenApiOperationReference, linkName: String, openApiLink: Link): ReturnValue<OpenApiLink> {
            val resolvedLink = if (openApiLink.`$ref` != null) {
                unReferenceLink(openAPI, openApiLink).unwrapOrReturn { return it.cast() }
            } else {
                openApiLink
            }

            val updatedRef = OpenApiOperationReference.updateContentType(byOperation, response, openApiLink).unwrapOrReturn {
                return it.cast()
            }

            val parseResult = OpenApiLink.from(openAPI, updatedRef, linkName, resolvedLink)
            return parseResult.realise(
                hasValue = { it, _ -> HasValue(it) },
                orException = { e ->
                    e.addDetails("Unexpected Exception while parsing openApiLink $linkName", "")
                },
                orFailure = { f ->
                    f.addDetails("Invalid OpenApi Link $linkName", "")
                },
            )
        }

        private fun unReferenceLink(openAPI: OpenAPI, openApiLink: Link): ReturnValue<Link> {
            val linkName = openApiLink.`$ref`?.substringAfterLast("/")?.takeIf(String::isNotBlank)
                ?: return HasFailure("Invalid reference to OpenApi Link", openApiLink.`$ref`)

            return openAPI.components?.links?.get(linkName)?.let(::HasValue) ?: HasFailure(
                "Failed to resolve reference to OpenApi Link", openApiLink.`$ref`,
            )
        }

        private fun parseOpenApiLinkGraph(links: Map<OpenApiOperationReference, List<OpenApiLink>>, lenient: Boolean): ReturnValue<OpenApiLinksGraph> {
            return OpenApiLinksGraph.from(links.flatMap { it.value }).realise(
                hasValue = { it, _ -> HasValue(it) },
                orException = { e ->
                    if (!lenient) return@realise e.cast()
                    logger.boundary()
                    logger.ofTheException(e.t, "Unexpected Exception while parsing OpenApi Links Graph")
                    logger.boundary()
                    HasValue(OpenApiLinksGraph())
                },
                orFailure = { f ->
                    if (!lenient) return@realise f.cast()
                    logger.boundary()
                    logger.log(f.addDetails("Failed to parse OpenApi Links Graph", "").failure.reportString())
                    logger.boundary()
                    HasValue(OpenApiLinksGraph())
                },
            )
        }
    }
}
