package io.specmatic.core

import io.specmatic.conversions.NoSecurityScheme
import io.specmatic.conversions.OpenAPISecurityScheme
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.ktor.util.*
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorBasedValueGenerator
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_QUERY_PARAMS
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue

private const val MULTIPART_FORMDATA_BREADCRUMB = "MULTIPART-FORMDATA"
const val METHOD_BREAD_CRUMB = "METHOD"
private const val FORM_FIELDS_BREADCRUMB = "FORM-FIELDS"
const val CONTENT_TYPE = "Content-Type"

val invalidRequestStatuses = listOf(400, 422)

data class HeaderMatchParams(val request: HttpRequest, val headersResolver: Resolver?, val defaultResolver: Resolver, val failures: List<Failure>)

data class HttpRequestPattern(
    val headersPattern: HttpHeadersPattern = HttpHeadersPattern(),
    val httpPathPattern: HttpPathPattern? = null,
    val httpQueryParamPattern: HttpQueryParamPattern = HttpQueryParamPattern(emptyMap()),
    val method: String? = null,
    val body: Pattern = EmptyStringPattern,
    val formFieldsPattern: Map<String, Pattern> = emptyMap(),
    val multiPartFormDataPattern: List<MultiPartFormDataPattern> = emptyList(),
    val securitySchemes: List<OpenAPISecurityScheme> = listOf(NoSecurityScheme())
) {

    fun getPathSegmentPatterns() = httpPathPattern?.pathSegmentPatterns

    fun getHeaderKeys() = headersPattern.headerNames

    fun getQueryParamKeys() = httpQueryParamPattern.queryKeyNames

    fun matches(incomingHttpRequest: HttpRequest, resolver: Resolver, headersResolver: Resolver? = null, requestBodyReqex: Regex? = null): Result {
        val result = incomingHttpRequest to resolver to
                ::matchPath then
                ::matchMethod then
                ::matchSecurityScheme then
                ::matchQuery then
                { (request, defaultResolver, failures) ->
                    matchHeaders(HeaderMatchParams(request, headersResolver, defaultResolver, failures))
                } then
                ::matchFormFields then
                ::matchMultiPartFormData then
                ::matchBody then
                {
                    (request, resolver, failures) ->
                    if (requestBodyReqex?.matches(request.bodyString) == false)
                        MatchSuccess(Triple(request, resolver, failures.plus(Failure("Request did not match regex $requestBodyReqex"))))
                    else
                        MatchSuccess(Triple(request, resolver, failures))
                } then
                ::summarize otherwise
                ::handleError toResult
                ::returnResult

        return when (result) {
            is Failure -> result.breadCrumb("REQUEST")
            else -> result
        }
    }

    fun matchesPathAndMethod(
        incomingHttpRequest: HttpRequest,
        resolver: Resolver
    ): Result {
        val result = incomingHttpRequest to resolver to
                ::matchPath then
                ::matchMethod then
                ::summarize otherwise
                ::handleError toResult
                ::returnResult

        val matchFailureButSameStructure = (
            incomingHttpRequest.method == method &&
            (result as? Failure)?.hasReason(FailureReason.URLPathParamMismatchButSameStructure) == true
        )

        return when (result) {
            is Failure -> result.failureReason(
                failureReason = if (matchFailureButSameStructure) FailureReason.URLPathParamMismatchButSameStructure else result.failureReason
            ).breadCrumb("REQUEST")
            else -> result
        }
    }

    fun matchesPathStructureMethodAndContentType(incomingHttpRequest: HttpRequest, resolver: Resolver): Result {
        val contentTypeMatches = headersPattern.matchContentType(incomingHttpRequest.headers to resolver)
        if (contentTypeMatches is MatchFailure<*>) {
            return contentTypeMatches.error.breadCrumb(BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_HEADER).value)
        }

        val pathAndMethodMatch = matchesPathAndMethod(incomingHttpRequest, resolver)
        return pathAndMethodMatch.takeUnless {
            it is Failure && it.hasReason(FailureReason.URLPathParamMismatchButSameStructure)
        } ?: Success()
    }

    private fun matchSecurityScheme(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters
        val (modifiedHttpRequest, results) = securitySchemes.fold(
            initial = Pair(httpRequest, emptyList<SecurityMatch>())
        ) { (request, results), securityScheme ->
            securityScheme.removeParam(request) to results.plus(
                SecurityMatch(
                    presence = when {
                        securityScheme.isInRequest(httpRequest, complete = true) -> SchemePresence.FULL
                        securityScheme.isInRequest(httpRequest, complete = false) -> SchemePresence.PARTIAL
                        else -> SchemePresence.ABSENT
                    },
                    result = securityScheme.matches(httpRequest, resolver).breadCrumb(BreadCrumb.PARAMETERS.value)
                )
            )
        }

        SchemePresence.entries.forEach { presence ->
            val presenceResults = results.filter { it.presence == presence }.map { it.result }
            val presencesSuccess = presenceResults.filterIsInstance<Success>()
            val presenceFailures = presenceResults.filterIsInstance<Failure>()

            if (presencesSuccess.isNotEmpty()) {
                return MatchSuccess(Triple(modifiedHttpRequest, resolver, failures.plus(presenceFailures)))
            }

            if (presenceFailures.isNotEmpty()) {
                return MatchSuccess(Triple(modifiedHttpRequest, resolver, failures.plus(presenceFailures)))
            }
        }

        val newFailures = results.map { it.result }.filterIsInstance<Failure>()
        return MatchSuccess(Triple(modifiedHttpRequest, resolver, failures.plus(newFailures)))
    }

    fun matchesSignature(other: HttpRequestPattern): Boolean =
        httpPathPattern!!.path == other.httpPathPattern!!.path && method.equals(method)

    private fun matchMultiPartFormData(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        if (multiPartFormDataPattern.isEmpty() && httpRequest.multiPartFormData.isEmpty()) {
            return MatchSuccess(parameters)
        }

        val results: List<Result?> = multiPartFormDataPattern.map { type ->
            val results = httpRequest.multiPartFormData.map { value ->
                type.matches(value, resolver)
            }

            val result = results.find { it is Success } ?: results.find { it is Failure && it.failureReason != FailureReason.PartNameMisMatch }?.breadCrumb(type.name)?.breadCrumb(MULTIPART_FORMDATA_BREADCRUMB)
            result ?: when {
                isOptional(type.name) -> Success()
                else -> null
            }
        }

        val payloadFailures: List<Failure> = results.filterIsInstance<Failure>()

        val typeKeys = multiPartFormDataPattern.map { withoutOptionality(it.name) }.sorted()
        val valueKeys = httpRequest.multiPartFormData.map { it.name }.sorted()

        val missingInType: List<Failure> = valueKeys.filter { it !in typeKeys }.map {
            Failure(resolver.mismatchMessages.unexpectedKey("part", it)).breadCrumb(it).breadCrumb(MULTIPART_FORMDATA_BREADCRUMB)
        }

        val originalTypeKeys = multiPartFormDataPattern.map { it.name }.sorted()
        val missingInValue = originalTypeKeys.filter { !isOptional(it) }.filter { withoutOptionality(it) !in valueKeys }.map { partName ->
            Failure(resolver.mismatchMessages.expectedKeyWasMissing("part", withoutOptionality(partName))).breadCrumb(withoutOptionality(partName)).breadCrumb(MULTIPART_FORMDATA_BREADCRUMB)
        }

        val allFailures: List<Failure> = missingInValue.plus(missingInType).plus(payloadFailures)

        return if(allFailures.isEmpty())
            MatchSuccess(parameters)
        else
            MatchSuccess(Triple(httpRequest, resolver, failures.plus(allFailures)))
    }

    fun matchFormFields(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, _: List<Failure>) = parameters

        val keyErrorResults: List<Failure> = resolver.findKeyErrorList(formFieldsPattern, httpRequest.formFields).map {
            it.missingKeyToResult("form field", resolver.mismatchMessages).breadCrumb(it.name).breadCrumb(FORM_FIELDS_BREADCRUMB)
        }

        val payloadResults: List<Result> = formFieldsPattern
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

        val allFailures = keyErrorResults.plus(payloadResults.filterIsInstance<Failure>())

        return if(allFailures.isEmpty())
            MatchSuccess(parameters)
        else
            MatchSuccess(Triple(httpRequest, resolver, allFailures))
    }

    private fun matchHeaders(parameters: HeaderMatchParams): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, headersResolver, defaultResolver, failures) = parameters
        return when (val result = this.headersPattern.matches(httpRequest.headers, headersResolver ?: defaultResolver)) {
            is Failure -> {
                val failureReason = result.traverseFailureReason()
                val breadCrumbResult = result.breadCrumb(BreadCrumb.PARAMETERS.value)
                if (failureReason == FailureReason.ContentTypeMismatch)
                    MatchFailure(breadCrumbResult)
                else
                    MatchSuccess(Triple(httpRequest, defaultResolver, failures.plus(breadCrumbResult)))
            }
            else -> MatchSuccess(Triple(httpRequest, defaultResolver, failures))
        }
    }

    private fun matchBody(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        val result = try {
            // TODO: Review Body String Logic
            val bodyValue =
                if (isPatternToken(httpRequest.bodyString))
                    StringValue(httpRequest.bodyString)
                else if (httpRequest.body is JSONObjectValue || httpRequest.body is JSONArrayValue) {
                    httpRequest.body
                } else body.parse(httpRequest.bodyString, resolver)

            resolver.matchesPattern(null, body, bodyValue).breadCrumb("BODY")
        } catch (e: ContractException) {
            e.failure().breadCrumb("BODY")
        }

        return when (result) {
            is Failure -> MatchSuccess(Triple(httpRequest, resolver, failures.plus(result)))
            else -> MatchSuccess(parameters)
        }
    }

    private fun matchMethod(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        method.let {
            return if (it != httpRequest.method)
                MatchFailure(
                    mismatchResult(
                        method ?: "",
                        httpRequest.method ?: ""
                    ).copy(failureReason = FailureReason.MethodMismatch).breadCrumb(METHOD_BREAD_CRUMB)
                )
            else
                MatchSuccess(Triple(httpRequest, resolver, failures))
        }
    }

    private fun matchPath(parameters: Pair<HttpRequest, Resolver>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver) = parameters

        val result = matchesPath(httpRequest.path!!, resolver)

        return if (result is Failure) {
            if(result.failureReason == FailureReason.URLPathMisMatch)
                MatchFailure(result)
            else
                MatchSuccess(Triple(parameters.first, parameters.second, listOf(result)))
        }
        else
            MatchSuccess(Triple(parameters.first, parameters.second, emptyList()))
    }

    fun matchesPath(
        path: String,
        resolver: Resolver
    ) = httpPathPattern!!.matches(path, resolver)

    private fun matchQuery(parameters: Triple<HttpRequest, Resolver, List<Failure>>): MatchingResult<Triple<HttpRequest, Resolver, List<Failure>>> {
        val (httpRequest, resolver, failures) = parameters

        val updatedResolver =
            if(Flags.getBooleanValue(EXTENSIBLE_QUERY_PARAMS))
                resolver.copy(
                    findKeyErrorCheck = resolver.findKeyErrorCheck.copy(
                        unexpectedKeyCheck = IgnoreUnexpectedKeys
                    )
                )
            else
                resolver

        val result = httpQueryParamPattern.matches(httpRequest, updatedResolver)

        return if (result is Failure)
            MatchSuccess(Triple(httpRequest, resolver, failures.plus(result)))
        else
            MatchSuccess(parameters)
    }

    fun generate(request: HttpRequest, resolver: Resolver): HttpRequestPattern {
        var requestPattern = HttpRequestPattern()

        return attempt(breadCrumb = "REQUEST") {
            if (method == null) {
                throw missingParam("HTTP method")
            }
            if (httpPathPattern == null) {
                throw missingParam("URL path")
            }

            requestPattern = requestPattern.copy(method = request.method)

            requestPattern = attempt(breadCrumb = "URL") {
                val path = request.path ?: ""
                val pathTypes = pathToPattern(path)
                val queryParamTypes = toTypeMapForQueryParameters(request.queryParams, httpQueryParamPattern, resolver)
                requestPattern.copy(httpPathPattern = HttpPathPattern(pathTypes, path), httpQueryParamPattern = HttpQueryParamPattern(queryParamTypes))
            }

            requestPattern = attempt(breadCrumb = BreadCrumb.PARAM_HEADER.value) {
                val headersFromRequest = toTypeMap(
                    toLowerCaseKeys(request.headers),
                    toLowerCaseKeys(headersPattern.pattern),
                    resolver
                )

                requestPattern.copy(
                    headersPattern = headersPattern.copy(
                        pattern = headersFromRequest, ancestorHeaders = this.headersPattern.pattern
                    )
                )
            }

            requestPattern = attempt(breadCrumb = "BODY") {
                requestPattern.copy(
                    body = when (request.body) {
                        EmptyString -> EmptyStringPattern
                        NoBodyValue -> NoBodyPattern
                        is StringValue -> encompassedType(request.bodyString, null, body, resolver)
                        else -> request.body.exactMatchElseType()
                    }
                )
            }

            requestPattern = attempt(breadCrumb = "FORM FIELDS") {
                requestPattern.copy(formFieldsPattern = toTypeMap(request.formFields, formFieldsPattern, resolver))
            }

            val multiPartFormDataRequestMap =
                request.multiPartFormData.fold(emptyMap<String, MultiPartFormDataValue>()) { acc, part ->
                    acc.plus(part.name to part)
                }

            attempt(breadCrumb = "MULTIPART DATA") {
                requestPattern.copy(multiPartFormDataPattern = multiPartFormDataPattern.filter {
                    withoutOptionality(it.name) in multiPartFormDataRequestMap
                }.map {
                    val key = withoutOptionality(it.name)
                    multiPartFormDataRequestMap.getValue(key).inferType()
                })
            }
        }
    }

    private fun <ValueType> toLowerCaseKeys(map: Map<String, ValueType>) =
        map.map { (key, value) -> key.toLowerCasePreservingASCIIRules() to value }.toMap()

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

    private fun toTypeMapForQueryParameters(
        queryParams: QueryParameters,
        httpQueryParamPattern: HttpQueryParamPattern,
        resolver: Resolver
    ): Map<String, Pattern> {
        val patterns: Map<String, Pattern> = httpQueryParamPattern.queryPatterns

        val paramsWithinPattern = patterns.filterKeys { withoutOptionality(it) in queryParams.paramPairs.map { it.first } }.map {
            val key = withoutOptionality(it.key)
            val pattern = it.value

            attempt(breadCrumb = key) {
                val values: List<String> = queryParams.getValues(key)
                when (pattern) {
                    is QueryParameterArrayPattern -> {
                        val queryParameterValuePatterns = values.map { value ->
                            encompassedType(value, key, pattern.pattern.first(), resolver)
                        }
                        key to QueryParameterArrayPattern(queryParameterValuePatterns, key)
                    }

                    is QueryParameterScalarPattern -> {
                        key to QueryParameterScalarPattern(
                            encompassedType(
                                values.single(),
                                key,
                                pattern.pattern,
                                resolver
                            )
                        )
                    }

                    else -> {
                        throw ContractException("Non query type: $pattern found")
                    }
                }
            }
        }.toMap()

        val paramsUnaccountedFor = queryParams.paramPairs.filter { (name, _) ->
            name !in paramsWithinPattern
        }.groupBy { (name, _) ->
            name
        }

        val paramsOutsidePattern = if(httpQueryParamPattern.additionalProperties != null) {
            val results = paramsUnaccountedFor.map { (name, values) ->
                values.map { (_, rawValue) ->
                    val value = httpQueryParamPattern.additionalProperties.parse(rawValue, resolver)
                    httpQueryParamPattern.additionalProperties.matches(value, resolver)
                }
            }.flatten()

            val matchResult = Result.fromResults(results)

            if (matchResult is Failure)
                throw ContractException(matchResult.toFailureReport())

            unaccountedQueryParamsToMap(paramsUnaccountedFor)
        } else if(Flags.getBooleanValue(EXTENSIBLE_QUERY_PARAMS)) {
            unaccountedQueryParamsToMap(paramsUnaccountedFor)
        } else {
            emptyMap()
        }

        return paramsWithinPattern + paramsOutsidePattern
    }

    private fun unaccountedQueryParamsToMap(paramsUnaccountedFor: Map<String, List<Pair<String, String>>>) =
        paramsUnaccountedFor.map { (name, values) ->
            val pattern = if (values.size > 1) {
                QueryParameterArrayPattern(values.map { ExactValuePattern(StringValue(it.second)) }, name)
            } else {
                QueryParameterScalarPattern(ExactValuePattern(StringValue(values.single().second)))
            }

            name to pattern
        }.toMap()

    private fun encompassedType(valueString: String, key: String?, type: Pattern, resolver: Resolver): Pattern {
        return when {
            isPatternToken(valueString) -> resolvedHop(parsedPattern(valueString, key), resolver)
            else -> runCatching { type.parseToType(valueString, resolver) }.getOrElse { StringValue(valueString).exactMatchElseType() }
        }
    }

    fun generate(resolver: Resolver): HttpRequest {
        return attempt(breadCrumb = "REQUEST") {
            if (method == null) {
                throw missingParam("HTTP method")
            }
            HttpRequest()
                .updateMethod(method)
                .generateAndUpdateURL(resolver)
                .generateAndUpdateBody(resolver, body)
                .generateAndUpdateHeaders(resolver)
                .generateAndUpdateFormFieldsValues(resolver)
                .generateAndUpdateSecuritySchemes(resolver)
                .generateAndUpdateMultiPartData(resolver)
        }
    }

    fun generateV2(resolver: Resolver): List<DiscriminatorBasedItem<HttpRequest>> {
        return attempt(breadCrumb = "REQUEST") {
            val baseRequest = generate(resolver)

            DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(
                resolver,
                body
            ).map {
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata(
                        discriminatorProperty = it.discriminatorProperty,
                        discriminatorValue = it.discriminatorValue,
                    ),
                    value = baseRequest.updateBody(it.value)
                )
            }
        }
    }

    private fun HttpRequest.generateAndUpdatePath(resolver: Resolver): HttpRequest {
        if (httpPathPattern == null) {
            throw missingParam("URL path")
        }
        return this.updatePath(httpPathPattern.generate(resolver))
    }

    private fun HttpRequest.generateAndUpdateQueryParam(resolver: Resolver): HttpRequest {
        val queryParams = httpQueryParamPattern.generate(resolver)
        return queryParams.fold(this) { request, queryParam ->
            request.updateQueryParam(queryParam.first, queryParam.second)
        }
    }

    private fun HttpRequest.generateAndUpdateURL(resolver: Resolver): HttpRequest {
        return attempt(breadCrumb = "URL") {
            this.generateAndUpdatePath(resolver)
                .generateAndUpdateQueryParam(resolver)
        }
    }

    private fun HttpRequest.generateAndUpdateBody(resolver: Resolver, body: Pattern): HttpRequest {
        return attempt(breadCrumb = "BODY") {
            resolver.withCyclePrevention(body) {cyclePreventedResolver ->
                val generatedValue = body.generate(cyclePreventedResolver)
                this.updateBody(generatedValue)
            }
        }
    }

    private fun HttpRequest.generateAndUpdateHeaders(resolver: Resolver): HttpRequest {
        return attempt(breadCrumb = BreadCrumb.PARAMETERS.value) {
            this.copy(
                headers = headersPattern.generate(
                    resolver.updateLookupPath(BreadCrumb.PARAMETERS.value)
                )
            )
        }
    }

    private fun HttpRequest.generateAndUpdateFormFieldsValues(resolver: Resolver): HttpRequest {
        val formFieldsValue = attempt(breadCrumb = "FORM FIELDS") {
            formFieldsPattern.mapValues { (key, pattern) ->
                attempt(breadCrumb = key) {
                    resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                        cyclePreventedResolver.generate(key, pattern)
                    }.toString()
                }
            }
        }
        if(formFieldsValue.isEmpty()) return this
        return this.copy(
            formFields = formFieldsValue,
            headers = this.headers.plus(CONTENT_TYPE to "application/x-www-form-urlencoded")
        )
    }

    private fun HttpRequest.generateAndUpdateSecuritySchemes(resolver: Resolver): HttpRequest {
        return securitySchemes.fold(this) { request, securityScheme ->
            securityScheme.addTo(request, resolver.updateLookupPath(BreadCrumb.PARAMETERS.value))
        }
    }

    private fun HttpRequest.generateAndUpdateMultiPartData(resolver: Resolver): HttpRequest {
        val multipartData = attempt(breadCrumb = "MULTIPART DATA") {
            multiPartFormDataPattern.mapIndexed { index, multiPartFormDataPattern ->
                attempt(breadCrumb = "[$index]") { multiPartFormDataPattern.generate(resolver) }
            }
        }
        if(multipartData.isEmpty()) return this
        return this.copy(
            multiPartFormData = multipartData,
            headers = this.headers.plus(CONTENT_TYPE to "multipart/form-data")
        )
    }

    fun newBasedOn(row: Row, initialResolver: Resolver, status: Int = 0): Sequence<ReturnValue<HttpRequestPattern>> {
        val resolver = when (status) {
            in invalidRequestStatuses -> initialResolver.invalidRequestResolver()
            else -> initialResolver
        }

        return returnValue(breadCrumb = "REQUEST") {
            val newHttpPathPatterns: Sequence<ReturnValue<HttpPathPattern?>> = httpPathPattern?.let { httpPathPattern ->
                val newURLPathSegmentPatternsList = if (status.toString().startsWith("2")) {
                    httpPathPattern.newBasedOn(row, resolver)
                } else httpPathPattern.readFrom(row, resolver)
                newURLPathSegmentPatternsList.map { HttpPathPattern(it, httpPathPattern.path) }.map { HasValue(it) }
            } ?: sequenceOf(HasValue(null))

            val newQueryParamsPatterns: Sequence<ReturnValue<HttpQueryParamPattern>> = returnValue(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
                if (status.toString().startsWith("2")) {
                    httpQueryParamPattern.addComplimentaryPatterns(
                        httpQueryParamPattern.newBasedOn(row, resolver),
                        row,
                        resolver
                    )
                } else {
                    httpQueryParamPattern.readFrom(
                        row,
                        resolver,
                        shouldGenerateMandatoryEntryIfMissing(resolver, status)
                    ).map { pattern ->
                        pattern.ifValue { HttpQueryParamPattern(pattern.value) }
                    }
                }
            }

            val newHeadersPattern: Sequence<ReturnValue<HttpHeadersPattern>> = returnValue(breadCrumb = BreadCrumb.PARAM_HEADER.value) {
                if (status.toString().startsWith("2")) {
                    headersPattern.addComplimentaryPatterns(
                        headersPattern.newBasedOn(row, resolver),
                        row,
                        resolver,
                        BreadCrumb.PARAM_HEADER.value
                    )
                } else {
                    headersPattern.readFrom(
                        row,
                        resolver,
                        shouldGenerateMandatoryEntryIfMissing(resolver, status),
                        BreadCrumb.PARAM_HEADER.value
                    )
                }
            }

            val newBodies: Sequence<ReturnValue<Pattern>> = attempt(breadCrumb = "BODY") {
                val rawRequestBody = row.getFieldOrNull(REQUEST_BODY_FIELD) ?: return@attempt resolver.generateHttpRequestBodies(this.body, row)
                val parsedValue = runCatching { body.parse(rawRequestBody, resolver) }.getOrElse { e ->
                    if (isInvalidRequestResponse(status)) StringValue(rawRequestBody)
                    else throw e
                }
                val requestBodyAsIs = ExactValuePattern(parsedValue)

                if (!isInvalidRequestResponse(status)) {
                    resolver.matchesPattern(null, body, parsedValue).throwOnFailure()
                }

                if (status in 200..299)
                    resolver.generateHttpRequestBodies(this.body, row, requestBodyAsIs)
                else
                    sequenceOf(HasValue(requestBodyAsIs))
            }

            val newFormFieldsPatterns: Sequence<ReturnValue<Map<String, Pattern>>> =
                newMapBasedOn(formFieldsPattern, row, resolver).map { it.value }.map { HasValue(it) }
            val newFormDataPartLists: Sequence<ReturnValue<List<MultiPartFormDataPattern>>> =
                newMultiPartBasedOn(multiPartFormDataPattern, row, resolver).map { HasValue(it) }

            newHttpPathPatterns.flatMap(BreadCrumb.PARAM_PATH.value) { newPathParamPattern ->
                newQueryParamsPatterns.flatMap(BreadCrumb.PARAM_QUERY.value) { newQueryParamPattern ->
                    newBodies.flatMap("BODY") { newBody ->
                        newHeadersPattern.flatMap(BreadCrumb.PARAM_HEADER.value) { newHeadersPattern ->
                            newFormFieldsPatterns.flatMap("FORM-FIELDS") { newFormFieldsPattern ->
                                newFormDataPartLists.flatMap("FORM-DATA") { newFormDataPartList ->
                                    val newRequestPattern = HttpRequestPattern(
                                        headersPattern = newHeadersPattern.value,
                                        httpPathPattern = newPathParamPattern.value,
                                        httpQueryParamPattern = newQueryParamPattern.value,
                                        method = method,
                                        body = newBody.value,
                                        formFieldsPattern = newFormFieldsPattern.value,
                                        multiPartFormDataPattern = newFormDataPartList.value
                                    )

                                    val schemeInRow = securitySchemes.find { it.isInRow(row) }

                                    if (schemeInRow != null) {
                                        listOf(schemeInRow.addTo(newRequestPattern, row)).asSequence()
                                    } else {
                                        securitySchemes.asSequence().map {
                                            newRequestPattern.copy(securitySchemes = listOf(it))
                                        }
                                    }.map { requestPattern ->
                                        val requestValueDetails = listOf(newHeadersPattern)
                                            .filterIsInstance<HasValue<*>>().flatMap {
                                                it.valueDetails
                                            }
                                        HasValue(requestPattern, requestValueDetails)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun <T, U> Sequence<T>.flatMap(breadCrumb: String = "", fn: (T) -> Sequence<ReturnValue<U>>): Sequence<ReturnValue<U>> {
        val iterator = this.iterator()

        return sequence {
            try {
                while(iterator.hasNext()) {
                    val next = iterator.next()

                    val results: Sequence<ReturnValue<U>> = fn(next)

                    yieldAll(results)
                }
            } catch(t: Throwable) {
                yield(HasException(t, breadCrumb = breadCrumb))
            }
        }
    }

    private fun isInvalidRequestResponse(status: Int): Boolean {
        return status in invalidRequestStatuses
    }

    private fun shouldGenerateMandatoryEntryIfMissing(resolver: Resolver, status: Int): Boolean {
        if(resolver.isNegative) return true
        val isNon4xxResponseStatus = status.toString().startsWith("4").not()
        return isNon4xxResponseStatus
    }

    fun newBasedOn(resolver: Resolver): Sequence<HttpRequestPattern> {
        return attempt(breadCrumb = "REQUEST") {
            val newHttpPathPatterns = httpPathPattern?.let { httpPathPattern ->
                val newURLPathSegmentPatternsList = httpPathPattern.newBasedOn(resolver)
                newURLPathSegmentPatternsList.map { HttpPathPattern(it, httpPathPattern.path) }
            } ?: sequenceOf<HttpPathPattern?>(null)

            val newQueryParamsPatterns = httpQueryParamPattern.newBasedOn(resolver)
            val newBodies = attempt(breadCrumb = "BODY") {
                resolver.withCyclePrevention(body) { cyclePreventedResolver ->
                    body.newBasedOn(cyclePreventedResolver)
                }
            }
            val newHeadersPattern = headersPattern.newBasedOn(resolver)
            val newFormFieldsPatterns = newBasedOn(formFieldsPattern, resolver)
            //TODO: Backward Compatibility
            val newFormDataPartLists = newMultiPartBasedOn(multiPartFormDataPattern, Row(), resolver)

            newHttpPathPatterns.flatMap { newPathParamPattern ->
                newQueryParamsPatterns.flatMap { newQueryParamPattern ->
                    newBodies.flatMap { newBody ->
                        newHeadersPattern.flatMap { newHeadersPattern ->
                            newFormFieldsPatterns.flatMap { newFormFieldsPattern ->
                                newFormDataPartLists.flatMap { newFormDataPartList ->
                                    val newRequestPattern = HttpRequestPattern(
                                        headersPattern = newHeadersPattern,
                                        httpPathPattern = newPathParamPattern,
                                        httpQueryParamPattern = newQueryParamPattern,
                                        method = method,
                                        body = newBody,
                                        formFieldsPattern = newFormFieldsPattern,
                                        multiPartFormDataPattern = newFormDataPartList
                                    )

                                    securitySchemes.map {
                                        newRequestPattern.copy(securitySchemes = listOf(it))
                                    }.asSequence().map { HasValue(it) }
                                }
                            }
                        }
                    }
                }
            }.map { it.value }
        }
    }

    fun testDescription(): String {
        return "$method ${httpPathPattern.toString()}"
    }

    fun negativeBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<HttpRequestPattern>> {
        return returnValue(breadCrumb = "REQUEST") {
            val newHttpPathPatterns: Sequence<ReturnValue<HttpPathPattern>?> =
                httpPathPattern?.let { httpPathPattern ->
                    httpPathPattern.negativeBasedOn(row, resolver)
                        .map { it.ifValue { HttpPathPattern(it, httpPathPattern.path) } }
                } ?: sequenceOf(null)

            val newQueryParamsPatterns = httpQueryParamPattern.negativeBasedOn(row, resolver)

            val newBodies: Sequence<ReturnValue<out Pattern>> = returnValue(breadCrumb = "BODY") returnNewBodies@ {
                val rawRequestBody = row.getFieldOrNull(REQUEST_BODY_FIELD) ?: return@returnNewBodies body.negativeBasedOn(row, resolver)
                val parsedValue = body.parse(rawRequestBody, resolver)
                body.matches(parsedValue, resolver).throwOnFailure()
                this.body.negativeBasedOn(row.noteRequestBody(), resolver)
            }

            val newHeadersPattern = headersPattern.negativeBasedOn(row, resolver, BreadCrumb.PARAM_HEADER.value)
            val newFormFieldsPatterns = newMapBasedOn(formFieldsPattern, row, resolver).map { it.value }
            val newFormDataPartLists = newMultiPartBasedOn(multiPartFormDataPattern, row, resolver)

            sequence {
                try {
                    // If security schemes are present, for now we'll just take the first scheme and assign it to each negative request pattern.
                    // Ideally we should generate negative patterns from the security schemes and use them.
                    val positivePattern: HttpRequestPattern =
                        newBasedOn(
                            row,
                            resolver,
                            400
                        ).first().value.copy(securitySchemes = listOf(securitySchemes.first()))

                    newHttpPathPatterns.forEach { pathParamPatternR ->
                        if (pathParamPatternR != null) {
                            yield(pathParamPatternR.ifValue { pathParamPattern ->
                                positivePattern.copy(httpPathPattern = pathParamPattern)
                            })
                        }
                    }
                    newQueryParamsPatterns.forEach { queryParamPatternR ->
                        yield(
                            queryParamPatternR.ifValue { queryParamPattern ->
                                positivePattern.copy(httpQueryParamPattern = queryParamPattern)
                            }
                        )
                    }
                    newBodies.forEach { newBodyPatternR ->
                        yield(
                            newBodyPatternR.ifValue { newBodyPattern -> positivePattern.copy(body = newBodyPattern) }
                        )
                    }
                    newHeadersPattern.forEach { newHeaderPatternR ->
                        yield(
                            newHeaderPatternR.ifValue { newHeaderPattern ->
                                positivePattern.copy(headersPattern = newHeaderPattern)
                            }
                        )
                    }
                    newFormFieldsPatterns.forEach { newFormFieldPattern ->
                        yield(
                            HasValue(positivePattern.copy(formFieldsPattern = newFormFieldPattern))
                        )
                    }
                    newFormDataPartLists.forEach { newFormDataPartListPattern ->
                        yield(
                            HasValue(positivePattern.copy(multiPartFormDataPattern = newFormDataPartListPattern))
                        )
                    }
                } catch (t: Throwable) {
                    yield(HasException(t))
                }
            }
        }
    }

    fun addPathParamsToRows(requestPath: String, row: Row, resolver: Resolver): Row {
        return httpPathPattern?.let { httpPathPattern ->
            val pathParams = httpPathPattern.extractPathParams(requestPath, resolver)
            return row.addFields(pathParams)
        } ?: row
    }

    fun getSubstitution(
        runningRequest: HttpRequest,
        originalRequest: HttpRequest,
        resolver: Resolver,
        data: JSONObjectValue,
    ): Substitution {
        return Substitution(
            runningRequest,
            originalRequest,
            httpPathPattern ?: HttpPathPattern(emptyList(), ""),
            headersPattern,
            body,
            resolver,
            data
        )
    }

    fun withoutOptionality(request: HttpRequest, resolver: Resolver): HttpRequest {
        return request.copy(
            body = body.eliminateOptionalKey(request.body, resolver)
        )
    }

    fun fixRequest(request: HttpRequest, resolver: Resolver): HttpRequest {
        return request.copy(
            method = method,
            path = httpPathPattern?.fixValue(request.path, resolver),
            queryParams = httpQueryParamPattern.fixValue(request.queryParams, resolver),
            headers = headersPattern.fixValue(request.headers, resolver.updateLookupPath(BreadCrumb.PARAMETERS.value)),
            body = body.fixValue(request.body, resolver)
        )
    }

    fun fillInTheBlanks(request: HttpRequest, resolver: Resolver): HttpRequest {
        val sanitizedRequest = withoutSecuritySchemes(request)
        val path = httpPathPattern?.fillInTheBlanks(
            path = sanitizedRequest.path, resolver = resolver
        )?.breadCrumb(BreadCrumb.PARAM_PATH.value) ?: HasValue(null)

        val queryParams = httpQueryParamPattern.fillInTheBlanks(
            queryParams = sanitizedRequest.queryParams, resolver = resolver
        ).breadCrumb(BreadCrumb.PARAM_QUERY.value)

        val headers = headersPattern.fillInTheBlanks(
            headers = sanitizedRequest.headers, resolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value)
        ).breadCrumb(BreadCrumb.PARAM_HEADER.value)

        val body = body.fillInTheBlanks(sanitizedRequest.body, resolver).breadCrumb("BODY")

        return HasValue(request)
            .combine(path) { req, it -> req.copy(path = it) }
            .combine(queryParams) { req, it -> req.copy(queryParams = it) }
            .combine(headers) { req, it -> req.copy(headers = it) }
            .combine(body) { req, it -> req.copy(body = it) }
            .ifValue { copySecuritySchemes(request, it) }
            .breadCrumb("REQUEST")
            .value
    }

    private fun copySecuritySchemes(originalRequest: HttpRequest, request: HttpRequest): HttpRequest {
        return securitySchemes.fold(request) { req, securityScheme ->
            securityScheme.copyFromTo(originalRequest, req)
        }
    }

    private fun withoutSecuritySchemes(request: HttpRequest): HttpRequest {
        return securitySchemes.fold(request) { req, securityScheme ->
            securityScheme.removeParam(req)
        }
    }

    fun getSOAPAction(resolver: Resolver): String? {
        return when(val soapActionPattern = headersPattern.getSOAPActionPattern(resolver, onlyUnescaped = true)) {
            is ExactValuePattern -> soapActionPattern.pattern.toStringLiteral()
            else -> null
        }
    }
}

private enum class SchemePresence { FULL, PARTIAL, ABSENT }
private data class SecurityMatch(val presence: SchemePresence, val result: Result)

fun missingParam(missingValue: String): ContractException {
    return ContractException("$missingValue is missing. Can't generate the contract test.")
}

fun newMultiPartBasedOn(
    partList: List<MultiPartFormDataPattern>,
    row: Row,
    resolver: Resolver
): Sequence<List<MultiPartFormDataPattern>> {
    val values = partList.map { part ->
        attempt(breadCrumb = part.name) {
            part.newBasedOn(row, resolver)
        }
    }

    return multiPartListCombinations(values)
}

fun multiPartListCombinations(values: List<Sequence<MultiPartFormDataPattern?>>): Sequence<List<MultiPartFormDataPattern>> {
    if (values.isEmpty())
        return sequenceOf(emptyList())

    val value: Sequence<MultiPartFormDataPattern?> = values.last()
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
