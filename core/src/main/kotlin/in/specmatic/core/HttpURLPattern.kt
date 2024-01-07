package `in`.specmatic.core

import `in`.specmatic.core.Result.Failure
import `in`.specmatic.core.Result.Success
import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.utilities.URIUtils
import `in`.specmatic.core.value.StringValue
import io.ktor.util.reflect.*
import java.net.URI

const val QUERY_PARAMS_BREADCRUMB = "QUERY-PARAMS"
val OMIT = listOf("(OMIT)", "(omit)")

data class HttpURLPattern(
    val queryPatterns: Map<String, Pattern>,
    val pathSegmentPatterns: List<URLPathSegmentPattern>,
    val path: String
) {
    fun encompasses(otherHttpURLPattern: HttpURLPattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        if (this.matches(HttpRequest("GET", URI.create(otherHttpURLPattern.path)), thisResolver) is Success)
            return Success()

        val mismatchedPartResults =
            this.pathSegmentPatterns.zip(otherHttpURLPattern.pathSegmentPatterns).map { (thisPathItem, otherPathItem) ->
                thisPathItem.pattern.encompasses(otherPathItem, thisResolver, otherResolver)
            }

        val failures = mismatchedPartResults.filterIsInstance<Failure>()

        if (failures.isEmpty())
            return Success()

        return Result.fromFailures(failures)
    }

    fun matches(uri: URI, sampleQuery: Map<String, String> = emptyMap(), resolver: Resolver = Resolver()): Result {
        val httpRequest = HttpRequest(path = uri.path, queryParams = sampleQuery)
        return matches(httpRequest, resolver)
    }

    fun matchesPath(path: String, resolver: Resolver): Result {
        return HttpRequest(path = path) to resolver to
                ::matchesPath otherwise
                ::handleError toResult
                ::returnResult
    }

    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        return httpRequest to resolver to
                ::matchesPath then
                ::matchesQuery otherwise
                ::handleError toResult
                ::returnResult
    }

    fun matchesPath(parameters: Pair<HttpRequest, Resolver>): MatchingResult<Pair<HttpRequest, Resolver>> {
        val (httpRequest, resolver) = parameters
        return when (val pathResult = matchesPath(URI(httpRequest.path!!), resolver)) {
            is Failure -> MatchFailure(pathResult.copy(failureReason = FailureReason.URLPathMisMatch))
            else -> MatchSuccess(parameters)
        }
    }

    fun matchesQuery(parameters: Pair<HttpRequest, Resolver>): MatchingResult<Pair<HttpRequest, Resolver>> {
        val (httpRequest, resolver) = parameters
        return when (val queryResult = matchesQuery(httpRequest, resolver)) {
            is Failure -> MatchFailure(queryResult.breadCrumb(QUERY_PARAMS_BREADCRUMB))
            else -> MatchSuccess(parameters)
        }
    }

    fun matchesQuery(httpRequest: HttpRequest, resolver: Resolver): Result {
        return matchesQuery(queryPatterns, httpRequest.queryParams, resolver)
    }

    private fun matchesPath(uri: URI, resolver: Resolver): Result {
        val pathParts = uri.path.split("/".toRegex()).filter { it.isNotEmpty() }.toTypedArray()

        if (pathSegmentPatterns.size != pathParts.size)
            return Failure(
                "Expected $uri (having ${pathParts.size} path segments) to match $path (which has ${pathSegmentPatterns.size} path segments).",
                breadCrumb = "PATH"
            )

        pathSegmentPatterns.zip(pathParts).forEach { (urlPathPattern, token) ->
            try {
                val parsedValue = urlPathPattern.tryParse(token, resolver)
                val result = resolver.matchesPattern(urlPathPattern.key, urlPathPattern.pattern, parsedValue)
                if (result is Failure) {
                    return when (urlPathPattern.key) {
                        null -> result.breadCrumb("PATH ($uri)")
                        else -> result.breadCrumb("PATH ($uri)").breadCrumb(urlPathPattern.key)
                    }
                }
            } catch (e: ContractException) {
                e.failure().breadCrumb("PATH ($uri)").let { failure ->
                    urlPathPattern.key?.let { failure.breadCrumb(urlPathPattern.key) } ?: failure
                }
            } catch (e: Throwable) {
                Failure(e.localizedMessage).breadCrumb("PATH ($uri)").let { failure ->
                    urlPathPattern.key?.let { failure.breadCrumb(urlPathPattern.key) } ?: failure
                }
            }
        }

        return Success()
    }

    fun generatePath(resolver: Resolver): String {
        return attempt(breadCrumb = "PATH") {
            ("/" + pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
                attempt(breadCrumb = "[$index]") {
                    val key = urlPathPattern.key
                    resolver.withCyclePrevention(urlPathPattern.pattern) { cyclePreventedResolver ->
                        if (key != null)
                            cyclePreventedResolver.generate(key, urlPathPattern.pattern)
                        else urlPathPattern.pattern.generate(cyclePreventedResolver)
                    }
                }
            }.joinToString("/")).let {
                if (path.endsWith("/") && !it.endsWith("/")) "$it/" else it
            }.let {
                if (path.startsWith("/") && !it.startsWith("/")) "$/it" else it
            }
        }
    }

    fun generateQuery(resolver: Resolver): Map<String, String> {
        return attempt(breadCrumb = "QUERY-PARAMS") {
            queryPatterns.mapKeys { it.key.removeSuffix("?") }.map { (name, pattern) ->
                attempt(breadCrumb = name) {
                    name to resolver.withCyclePrevention(pattern) { it.generate(name, pattern) }.toString()
                }
            }.toMap()
        }
    }

    fun newBasedOn(row: Row, resolver: Resolver): List<HttpURLPattern> {
        val generatedPatterns = newBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathParamPattern ->
            val key = urlPathParamPattern.key
            if (key === null || !row.containsField(key)) return@mapIndexed urlPathParamPattern
            attempt(breadCrumb = "[$index]") {
                val rowValue = row.getField(key)
                when {
                    isPatternToken(rowValue) -> attempt("Pattern mismatch in example of path param \"${urlPathParamPattern.key}\"") {
                        val rowPattern = resolver.getPattern(rowValue)
                        when (val result = urlPathParamPattern.encompasses(rowPattern, resolver, resolver)) {
                            is Success -> urlPathParamPattern.copy(pattern = rowPattern)
                            is Failure -> throw ContractException(result.toFailureReport())
                        }
                    }

                    else -> attempt("Format error in example of path parameter \"$key\"") {
                        val value = urlPathParamPattern.parse(rowValue, resolver)

                        val matchResult = urlPathParamPattern.matches(value, resolver)
                        if (matchResult is Failure)
                            throw ContractException("""Could not run contract test, the example value ${value.toStringLiteral()} provided "id" does not match the contract.""")

                        URLPathSegmentPattern(
                            ExactValuePattern(
                                value
                            )
                        )
                    }
                }
            }
        }, row, resolver)

        //TODO: replace this with Generics
        val generatedURLPathSegmentPatterns = generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }

        val newQueryParamsList = attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val optionalQueryParams = queryPatterns

            forEachKeyCombinationIn(row.withoutOmittedKeys(optionalQueryParams), row) { entry ->
                newBasedOn(entry.mapKeys { withoutOptionality(it.key) }, row, resolver)
            }
        }

        return generatedURLPathSegmentPatterns.flatMap { urlPathSegmentPatterns ->
            newQueryParamsList.map { newQueryParams ->
                HttpURLPattern(newQueryParams, urlPathSegmentPatterns, path)
            }
        }
    }

    fun newBasedOn(resolver: Resolver): List<HttpURLPattern> {
        val generatedPatterns = newBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
            attempt(breadCrumb = "[$index]") {
                urlPathPattern
            }
        }, resolver)

        //TODO: replace this with Generics
        val generatedURLPathSegmentPatterns = generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }

        val newQueryParamsList = attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val optionalQueryParams = queryPatterns

            allOrNothingCombinationIn(optionalQueryParams) { entry ->
                newBasedOn(entry.mapKeys { withoutOptionality(it.key) }, resolver)
            }
        }

        return generatedURLPathSegmentPatterns.flatMap { newURLPathPatterns ->
            newQueryParamsList.map { newQueryParams ->
                HttpURLPattern(newQueryParams, newURLPathPatterns, path)
            }
        }
    }

    override fun toString(): String {
        val stringizedQuery = if (queryPatterns.isNotEmpty()) {
            "?" + queryPatterns.mapKeys { it.key.removeSuffix("?") }.map { (key, value) ->
                "$key=$value"
            }.toList().joinToString(separator = "&")
        } else ""

        return path + stringizedQuery
    }

    fun toOpenApiPath(): String {
        val pathParamsWithPattern =
            this.path.split("/").filter { it.startsWith("(") }.map { it.replace("(", "").replace(")", "").split(":") }
        return this.path.replace("(", "{").replace(""":[a-z,A-Z]*?\)""".toRegex(), "}")
    }

    fun pathParameters(): List<URLPathSegmentPattern> {
        return pathSegmentPatterns.filter { !it.pattern.instanceOf(ExactValuePattern::class) }
    }

    fun negativeBasedOn(row: Row, resolver: Resolver): List<HttpURLPattern?> {
        val newPathPartsList: List<List<Pattern>> = newBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
            val key = urlPathPattern.key

            attempt(breadCrumb = "[$index]") {
                when {
                    key !== null && row.containsField(key) -> {
                        val rowValue = row.getField(key)
                        when {
                            isPatternToken(rowValue) -> attempt("Pattern mismatch in example of path param \"${urlPathPattern.key}\"") {
                                val rowPattern = resolver.getPattern(rowValue)
                                when (val result = urlPathPattern.encompasses(rowPattern, resolver, resolver)) {
                                    is Success -> urlPathPattern.copy(pattern = rowPattern)
                                    is Failure -> throw ContractException(result.toFailureReport())
                                }
                            }

                            else -> attempt("Format error in example of path parameter \"$key\"") {
                                val value = urlPathPattern.parse(rowValue, resolver)

                                val matchResult = urlPathPattern.matches(value, resolver)
                                if (matchResult is Failure)
                                    throw ContractException("""Could not run contract test, the example value ${value.toStringLiteral()} provided "id" does not match the contract.""")

                                URLPathSegmentPattern(
                                    ExactValuePattern(
                                        value
                                    )
                                )
                            }
                        }
                    }

                    else -> urlPathPattern
                }
            }
        }, row, resolver)

        val newURLPathSegmentPatternsList = newPathPartsList.map { list -> list.map { it as URLPathSegmentPattern } }

        val newQueryParamsList = attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val optionalQueryParams = queryPatterns

            forEachKeyCombinationIn(row.withoutOmittedKeys(optionalQueryParams), row) { entry ->
                negativeBasedOn(entry.mapKeys { withoutOptionality(it.key) }, row, resolver, true)
            }
        }

        return newURLPathSegmentPatternsList.flatMap { newURLPathPatterns ->
            newQueryParamsList.map { newQueryParams ->
                HttpURLPattern(newQueryParams, newURLPathPatterns, path)
            }
        }
    }
}

internal fun toURLMatcherWithOptionalQueryParams(
    url: String,
    apiKeyQueryParams: Set<String> = emptySet()
): HttpURLPattern =
    toURLMatcherWithOptionalQueryParams(URI.create(url), apiKeyQueryParams)

internal fun toURLMatcherWithOptionalQueryParams(
    urlPattern: URI,
    apiKeyQueryParams: Set<String> = emptySet()
): HttpURLPattern {
    val path = urlPattern.path

    val pathPattern = pathToPattern(urlPattern.rawPath)

    val queryPattern = URIUtils.parseQuery(urlPattern.query).mapKeys {
        "${it.key}?"
    }.mapValues {
        if (isPatternToken(it.value))
            DeferredPattern(it.value, it.key)
        else
            ExactValuePattern(StringValue(it.value))
    }.let { queryParams ->
        apiKeyQueryParams.map { apiKeyQueryParam ->
            Pair("${apiKeyQueryParam}?", StringPattern())
        }.toMap().plus(queryParams)
    }

    return HttpURLPattern(queryPatterns = queryPattern, path = path, pathSegmentPatterns = pathPattern)
}

internal fun pathToPattern(rawPath: String): List<URLPathSegmentPattern> =
    rawPath.trim('/').split("/").filter { it.isNotEmpty() }.map { part ->
        when {
            isPatternToken(part) -> {
                val pieces = withoutPatternDelimiters(part).split(":").map { it.trim() }
                if (pieces.size != 2) {
                    throw ContractException("In path ${rawPath}, $part must be of the format (param_name:type), e.g. (id:number)")
                }

                val (name, type) = pieces

                URLPathSegmentPattern(DeferredPattern(withPatternDelimiters(type)), name)
            }

            else -> URLPathSegmentPattern(ExactValuePattern(StringValue(part)))
        }
    }

internal fun matchesQuery(
    queryPattern: Map<String, Pattern>,
    sampleQuery: Map<String, String>,
    resolver: Resolver
): Result {
    val keyErrors = resolver.findKeyErrorList(queryPattern, sampleQuery.mapValues { StringValue(it.value) })
    val keyErrorList: List<Failure> = keyErrors.map {
        it.missingKeyToResult("query param", resolver.mismatchMessages).breadCrumb(it.name)
    }

    val results: List<Result?> = queryPattern.keys.map { key ->
        val keyName = key.removeSuffix("?")

        if (!sampleQuery.containsKey(keyName))
            null
        else {
            try {
                val patternValue = queryPattern.getValue(key)
                val sampleValue = sampleQuery.getValue(keyName)

                val parsedValue = try {
                    patternValue.parse(sampleValue, resolver)
                } catch (e: Exception) {
                    StringValue(sampleValue)
                }
                resolver.matchesPattern(keyName, patternValue, parsedValue).breadCrumb(keyName)
            } catch (e: ContractException) {
                e.failure().breadCrumb(keyName)
            } catch (e: Throwable) {
                Failure(e.localizedMessage).breadCrumb(keyName)
            }
        }
    }

    val failures = keyErrorList.plus(results).filterIsInstance<Failure>()

    return if (failures.isNotEmpty())
        Failure.fromFailures(failures)
    else
        Success()
}
