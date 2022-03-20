package `in`.specmatic.core

import `in`.specmatic.core.Result.Failure
import `in`.specmatic.core.Result.Success
import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.value.StringValue
import java.net.URI

private const val MULTIPART_FORMDATA_BREADCRUMB = "MULTIPART-FORMDATA"
private const val FORM_FIELDS_BREADCRUMB = "FORM-FIELDS"
const val CONTENT_TYPE = "Content-Type"

data class HttpRequestPattern(
    val headersPattern: HttpHeadersPattern = HttpHeadersPattern(),
    val urlMatcher: URLMatcher? = null,
    val method: String? = null,
    val body: Pattern = EmptyStringPattern,
    val formFieldsPattern: Map<String, Pattern> = emptyMap(),
    val multiPartFormDataPattern: List<MultiPartFormDataPattern> = emptyList()
) {
    fun matches(incomingHttpRequest: HttpRequest, resolver: Resolver, headersResolver: Resolver? = null): Result {
        val result = incomingHttpRequest to resolver to
                ::matchUrl then
                ::matchMethod then
                { (request, defaultResolver) ->
                    matchHeaders(Triple(request, headersResolver, defaultResolver))
                } then
                ::matchFormFields then
                ::matchMultiPartFormData then
                ::matchBody then
                ::summarize otherwise
                ::handleError toResult
                ::returnResult

        return when (result) {
            is Failure -> result.breadCrumb("REQUEST")
            else -> result
        }
    }

    fun matchesSignature(other: HttpRequestPattern): Boolean =
        urlMatcher!!.path == other.urlMatcher!!.path && method.equals(method)

    private fun summarize(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (_, _, failures) = parameters

        return if(failures.isNotEmpty())
            MatchFailure(Failure.fromFailures(failures))
        else
            MatchSuccess(parameters)
    }

    private fun matchMultiPartFormData(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        if (multiPartFormDataPattern.isEmpty() && httpRequest.multiPartFormData.isEmpty())
            return MatchSuccess(parameters)

        if (multiPartFormDataPattern.isEmpty() && httpRequest.multiPartFormData.isNotEmpty()) {
            return MatchFailure(
                Failure(
                    "The contract expected no multipart data, but the request contained ${httpRequest.multiPartFormData.size} parts.",
                    breadCrumb = MULTIPART_FORMDATA_BREADCRUMB
                )
            )
        }

        val results = multiPartFormDataPattern.map { type ->
            val results = httpRequest.multiPartFormData.map { value ->
                type.matches(value, resolver)
            }

            val result = results.find { it is Success }
                ?: results.find { it is Failure && it.failureReason != FailureReason.PartNameMisMatch }
                    ?.breadCrumb(type.name)
            result ?: when {
                isOptional(type.name) -> Success()
                else -> Failure("The part named ${type.name} was not found.").breadCrumb(type.name)
            }
        }

        if (results.any { it !is Success }) {
            val reason = results.filter { it !is Success }.joinToString("\n\n") {
                it.toReport().toText().prependIndent("  ")
            }

            return MatchFailure(
                Failure(
                    "The multipart data in the request did not match the contract:\n$reason",
                    breadCrumb = MULTIPART_FORMDATA_BREADCRUMB
                )
            )
        }

        val typeKeys = multiPartFormDataPattern.map { withoutOptionality(it.name) }.sorted()
        val valueKeys = httpRequest.multiPartFormData.map { it.name }.sorted()

        val missingInType = valueKeys.filter { it !in typeKeys }
        if (missingInType.isNotEmpty())
            return MatchFailure(
                Failure(
                    "Some parts in the request were missing from the contract, and their names are $missingInType.",
                    breadCrumb = MULTIPART_FORMDATA_BREADCRUMB
                )
            )

        val originalTypeKeys = multiPartFormDataPattern.map { it.name }.sorted()
        val missingInValue = originalTypeKeys.filter { !isOptional(it) }.filter { withoutOptionality(it) !in valueKeys }
            .joinToString(", ")
        if (missingInValue.isNotEmpty())
            return MatchFailure(
                Failure(
                    "Some parts in the contract were missing from the request, and their names are $missingInValue",
                    breadCrumb = MULTIPART_FORMDATA_BREADCRUMB
                )
            )

        return MatchSuccess(parameters)
    }

    fun matchFormFields(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver) = parameters

        val keyError = resolver.findKeyError(formFieldsPattern, httpRequest.formFields)
        if (keyError != null)
            return MatchFailure(keyError.missingKeyToResult("form field", resolver.mismatchMessages))

        val result: Result? = formFieldsPattern
            .filterKeys { key -> withoutOptionality(key) in httpRequest.formFields }
            .map { (key, pattern) -> Triple(withoutOptionality(key), pattern, httpRequest.formFields.getValue(key)) }
            .map { (key, pattern, value) ->
                try {
                    when (val result = resolver.matchesPattern(
                        key, pattern, try {
                            pattern.parse(value, resolver)
                        } catch (e: Throwable) {
                            StringValue(value)
                        }
                    )) {
                        is Failure -> result.breadCrumb(key).breadCrumb(FORM_FIELDS_BREADCRUMB)
                        else -> result
                    }
                } catch (e: ContractException) {
                    e.failure().breadCrumb(key).breadCrumb(FORM_FIELDS_BREADCRUMB)
                } catch (e: Throwable) {
                    mismatchResult(pattern, value).breadCrumb(key).breadCrumb(FORM_FIELDS_BREADCRUMB)
                }
            }
            .firstOrNull { it is Failure }

        return when (result) {
            is Failure -> MatchFailure(result)
            else -> MatchSuccess(parameters)
        }
    }

    private fun matchHeaders(parameters: Triple<HttpRequest, Resolver?, Resolver>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, headersResolver, defaultResolver) = parameters
        val headers = httpRequest.headers
        return when (val result = this.headersPattern.matches(headers, headersResolver ?: defaultResolver)) {
            is Failure -> MatchSuccess(Triple(httpRequest, defaultResolver, listOf(result)))
            else -> MatchSuccess(Triple(httpRequest, defaultResolver, emptyList()))
        }
    }

    private fun matchBody(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        val result = try {
            val bodyValue =
                if (isPatternToken(httpRequest.bodyString))
                    StringValue(httpRequest.bodyString)
                else
                    body.parse(httpRequest.bodyString, resolver)

            resolver.matchesPattern(null, body, bodyValue).breadCrumb("BODY")
        } catch (e: ContractException) {
            e.failure().breadCrumb("BODY")
        }

        return when (result) {
            is Failure -> MatchSuccess(Triple(httpRequest, resolver, failures.plus(result)))
            else -> MatchSuccess(parameters)
        }
    }

    private fun matchMethod(parameters: Pair<HttpRequest, Resolver>): MatchingResult<Pair<HttpRequest, Resolver>> {
        val (httpRequest, _) = parameters
        method.let {
            return if (it != httpRequest.method)
                MatchFailure(mismatchResult(method ?: "", httpRequest.method ?: "").breadCrumb("METHOD"))
            else
                MatchSuccess(parameters)
        }
    }

    private fun matchUrl(parameters: Pair<HttpRequest, Resolver>): MatchingResult<Pair<HttpRequest, Resolver>> {
        val (httpRequest, resolver) = parameters
        urlMatcher.let {
            val result = urlMatcher!!.matches(
                URI(httpRequest.path!!),
                httpRequest.queryParams,
                resolver
            )
            return if (result is Failure)
                MatchFailure(result.breadCrumb("URL"))
            else
                MatchSuccess(parameters)
        }
    }

    fun generate(request: HttpRequest, resolver: Resolver): HttpRequestPattern {
        var requestType = HttpRequestPattern()

        return attempt(breadCrumb = "REQUEST") {
            if (method == null) {
                throw missingParam("HTTP method")
            }
            if (urlMatcher == null) {
                throw missingParam("URL path")
            }

            requestType = requestType.copy(method = request.method)

            requestType = attempt(breadCrumb = "URL") {
                val path = request.path ?: ""
                val pathTypes = pathToPattern(path)
                val queryParamTypes = toTypeMap(
                    request.queryParams,
                    urlMatcher.queryPattern,
                    resolver
                ).mapKeys { it.key.removeSuffix("?") }

                requestType.copy(urlMatcher = URLMatcher(queryParamTypes, pathTypes, path))
            }

            requestType = attempt(breadCrumb = "HEADERS") {
                requestType.copy(
                    headersPattern = HttpHeadersPattern(
                        toTypeMap(
                            request.headers,
                            headersPattern.pattern,
                            resolver
                        )
                    )
                )
            }

            requestType = attempt(breadCrumb = "BODY") {
                requestType.copy(
                    body = when (request.body) {
                        is StringValue -> encompassedType(request.bodyString, null, body, resolver)
                        else -> request.body.exactMatchElseType()
                    }
                )
            }

            requestType = attempt(breadCrumb = "FORM FIELDS") {
                requestType.copy(formFieldsPattern = toTypeMap(request.formFields, formFieldsPattern, resolver))
            }

            val multiPartFormDataRequestMap =
                request.multiPartFormData.fold(emptyMap<String, MultiPartFormDataValue>()) { acc, part ->
                    acc.plus(part.name to part)
                }

            attempt(breadCrumb = "MULTIPART DATA") {
                requestType.copy(multiPartFormDataPattern = multiPartFormDataPattern.filter {
                    withoutOptionality(it.name) in multiPartFormDataRequestMap
                }.map {
                    val key = withoutOptionality(it.name)
                    multiPartFormDataRequestMap.getValue(key).inferType()
                })
            }
        }
    }

    private fun toTypeMap(
        values: Map<String, String>,
        types: Map<String, Pattern>,
        resolver: Resolver
    ): Map<String, Pattern> {
        return types.filterKeys { withoutOptionality(it) in values }.mapValues {
            val key = withoutOptionality(it.key)
            val type = it.value

            attempt(breadCrumb = key) {
                val valueString = values.getValue(key)
                encompassedType(valueString, key, type, resolver)
            }
        }
    }

    private fun encompassedType(valueString: String, key: String?, type: Pattern, resolver: Resolver): Pattern {
        return when {
            isPatternToken(valueString) -> resolvedHop(parsedPattern(valueString, key), resolver).let { parsedType ->
                when (val result = type.encompasses(parsedType, resolver, resolver)) {
                    is Success -> parsedType
                    is Failure -> throw ContractException(result.toFailureReport())
                }
            }
            else -> type.parseToType(valueString, resolver)
        }
    }

    fun generate(resolver: Resolver): HttpRequest {
        var newRequest = HttpRequest()

        return attempt(breadCrumb = "REQUEST") {
            if (method == null) {
                throw missingParam("HTTP method")
            }
            if (urlMatcher == null) {
                throw missingParam("URL path")
            }
            newRequest = newRequest.updateMethod(method)
            attempt(breadCrumb = "URL") {
                newRequest = newRequest.updatePath(urlMatcher.generatePath(resolver))
                val queryParams = urlMatcher.generateQuery(resolver)
                for (key in queryParams.keys) {
                    newRequest = newRequest.updateQueryParam(key, queryParams[key] ?: "")
                }
            }
            val headers = headersPattern.generate(resolver)

            val body = body
            attempt(breadCrumb = "BODY") {
                body.generate(resolver).let { value ->
                    newRequest = newRequest.updateBody(value)
                    newRequest = newRequest.updateHeader(CONTENT_TYPE, value.httpContentType)
                }
            }

            newRequest = newRequest.copy(headers = headers)

            val formFieldsValue = attempt(breadCrumb = "FORM FIELDS") {
                formFieldsPattern.mapValues { (key, pattern) ->
                    attempt(breadCrumb = key) {
                        resolver.generate(
                            key,
                            pattern
                        ).toString()
                    }
                }
            }
            newRequest = when (formFieldsValue.size) {
                0 -> newRequest
                else -> newRequest.copy(
                    formFields = formFieldsValue,
                    headers = newRequest.headers.plus(CONTENT_TYPE to "application/x-www-form-urlencoded")
                )
            }

            val multipartData = attempt(breadCrumb = "MULTIPART DATA") {
                multiPartFormDataPattern.mapIndexed { index, multiPartFormDataPattern ->
                    attempt(breadCrumb = "[$index]") { multiPartFormDataPattern.generate(resolver) }
                }
            }
            when (multipartData.size) {
                0 -> newRequest
                else -> newRequest.copy(
                    multiPartFormData = multipartData,
                    headers = newRequest.headers.plus(CONTENT_TYPE to "multipart/form-data")
                )
            }
        }
    }

    fun newBasedOn(row: Row, resolver: Resolver): List<HttpRequestPattern> {
        return attempt(breadCrumb = "REQUEST") {
            val newURLMatchers = urlMatcher?.newBasedOn(row, resolver) ?: listOf<URLMatcher?>(null)
            val newBodies: List<Pattern> = attempt(breadCrumb = "BODY") {
                body.let {
                    if(it is DeferredPattern && row.containsField(it.pattern)) {
                        val example = row.getField(it.pattern)
                        listOf(ExactValuePattern(it.parse(example, resolver)))
                    }
                    else if(it.typeAlias?.let { isPatternToken(it) } == true && row.containsField(it.typeAlias!!)) {
                        val example = row.getField(it.typeAlias!!)
                        listOf(ExactValuePattern(it.parse(example, resolver)))
                    }
                    else if(it is XMLPattern && it.referredType?.let { referredType -> row.containsField("($referredType)") } == true) {
                        val referredType = "(${it.referredType})"
                        val example = row.getField(referredType)
                        listOf(ExactValuePattern(it.parse(example, resolver)))
                    } else {
                        body.newBasedOn(row, resolver)
                    }
                }
            }
            val newHeadersPattern = headersPattern.newBasedOn(row, resolver)
            val newFormFieldsPatterns = newBasedOn(formFieldsPattern, row, resolver)
            val newFormDataPartLists = newMultiPartBasedOn(multiPartFormDataPattern, row, resolver)

            newURLMatchers.flatMap { newURLMatcher ->
                newBodies.flatMap { newBody ->
                    newHeadersPattern.flatMap { newHeadersPattern ->
                        newFormFieldsPatterns.flatMap { newFormFieldsPattern ->
                            newFormDataPartLists.map { newFormDataPartList ->
                                HttpRequestPattern(
                                    headersPattern = newHeadersPattern,
                                    urlMatcher = newURLMatcher,
                                    method = method,
                                    body = newBody,
                                    formFieldsPattern = newFormFieldsPattern,
                                    multiPartFormDataPattern = newFormDataPartList
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun newBasedOn(resolver: Resolver): List<HttpRequestPattern> {
        return attempt(breadCrumb = "REQUEST") {
            val newURLMatchers = urlMatcher?.newBasedOn(resolver) ?: listOf<URLMatcher?>(null)
            val newBodies = attempt(breadCrumb = "BODY") { body.newBasedOn(resolver) }
            val newHeadersPattern = headersPattern.newBasedOn(resolver)
            val newFormFieldsPatterns = newBasedOn(formFieldsPattern, resolver)
            //TODO: Backward Compatibility
            val newFormDataPartLists = newMultiPartBasedOn(multiPartFormDataPattern, Row(), resolver)

            newURLMatchers.flatMap { newURLMatcher ->
                newBodies.flatMap { newBody ->
                    newHeadersPattern.flatMap { newHeadersPattern ->
                        newFormFieldsPatterns.flatMap { newFormFieldsPattern ->
                            newFormDataPartLists.map { newFormDataPartList ->
                                HttpRequestPattern(
                                    headersPattern = newHeadersPattern,
                                    urlMatcher = newURLMatcher,
                                    method = method,
                                    body = newBody,
                                    formFieldsPattern = newFormFieldsPattern,
                                    multiPartFormDataPattern = newFormDataPartList
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun testDescription(): String {
        return "$method ${urlMatcher.toString()}"
    }
}

fun missingParam(missingValue: String): ContractException {
    return ContractException("$missingValue is missing. Can't generate the contract test.")
}

fun newMultiPartBasedOn(
    partList: List<MultiPartFormDataPattern>,
    row: Row,
    resolver: Resolver
): List<List<MultiPartFormDataPattern>> {
    val values = partList.map { part ->
        attempt(breadCrumb = part.name) {
            part.newBasedOn(row, resolver)
        }
    }

    return multiPartListCombinations(values)
}

fun multiPartListCombinations(values: List<List<MultiPartFormDataPattern?>>): List<List<MultiPartFormDataPattern>> {
    if (values.isEmpty())
        return listOf(emptyList())

    val value: List<MultiPartFormDataPattern?> = values.last()
    val subLists = multiPartListCombinations(values.dropLast(1))

    return subLists.flatMap { list ->
        value.map { type ->
            when (type) {
                null -> list
                else -> list.plus(type)
            }
        }
    }
}
