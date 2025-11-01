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
import io.specmatic.core.pattern.mapFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.links.Link

data class OpenApiLinksRepository(
    private val openApiFilePath: String? = null,
    private val operationToLinks: Map<OpenApiOperationReference, List<OpenApiLink>> = emptyMap(),
) {
    private val links: List<OpenApiLink> = operationToLinks.flatMap { it.value }
    val size: Int = links.size

    fun getDefinedFor(scenario: Scenario): List<OpenApiLink> {
        return links.filter {
            it.matchesFor(scenario.operationMetadata?.operationId, scenario.status) || it.matchesFor(scenario.path, scenario.method, scenario.status)
        }
    }

    fun getDefinedBy(path: String, method: String, status: Int): List<OpenApiLink> {
        return links.filter { it.matchesBy(path, method, status) }
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

    companion object {
        fun from(openApi: OpenAPI, openApiFilePath: String? = null, lenient: Boolean = true): ReturnValue<OpenApiLinksRepository> {
            val parser = if (lenient) {
                ::parseOpenApiLinkLenient
            } else {
                ::parseOpenApiLink
            }

            val linksMap = openApi.paths.orEmpty().mapValues { (path, pathItem) ->
                pathItem.readOperationsMap().mapValues { (method, operation) ->
                    operation.responses.orEmpty().entries.associate { (response, apiResponse) ->
                        Pair(operation, response) to apiResponse.links.orEmpty().mapNotNull { (name, link) ->
                            val operationId = operation.operationId
                            val statusCode = response.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
                            val opReference = OpenApiOperationReference(path, method.name, statusCode, operationId)
                            parser(openApi, opReference, name, link)
                        }.listFold()
                    }.mapFold()
                }.mapFold()
            }.mapFold().unwrapOrReturn { return it.cast() }

            val flatLinks = linksMap.flatMap { (path, methodMap) ->
                methodMap.flatMap { (method, operationMap) ->
                    operationMap.map { (operationToResponse, links) ->
                        val operationId = operationToResponse.first.operationId
                        val statusCode = operationToResponse.second.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
                        OpenApiOperationReference(path, method.name, statusCode, operationId) to links
                    }
                }
            }.toMap()

            return HasValue(OpenApiLinksRepository(openApiFilePath, flatLinks))
        }

        private fun parseOpenApiLinkLenient(openAPI: OpenAPI, byOperation: OpenApiOperationReference, linkName: String, openApiLink: Link): HasValue<OpenApiLink>? {
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

            val parseResult = OpenApiLink.from(openAPI, byOperation, linkName, resolvedLink)
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

        private fun parseOpenApiLink(openAPI: OpenAPI, byOperation: OpenApiOperationReference, linkName: String, openApiLink: Link): ReturnValue<OpenApiLink> {
            val resolvedLink = if (openApiLink.`$ref` != null) {
                unReferenceLink(openAPI, openApiLink).unwrapOrReturn { return it.cast() }
            } else {
                openApiLink
            }

            val parseResult = OpenApiLink.from(openAPI, byOperation, linkName, resolvedLink)
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
    }
}
