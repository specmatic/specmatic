package io.specmatic.core

import io.ktor.util.reflect.*
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import java.net.URI

val OMIT = listOf("(OMIT)", "(omit)")

private sealed class PathSegmentPart {
    data class Literal(val value: String) : PathSegmentPart()
    data class Parameter(val pattern: URLPathSegmentPattern) : PathSegmentPart()
}

private data class ParsedPathSegment(
    val raw: String,
    val parts: List<PathSegmentPart>,
) {
    fun isSimple(): Boolean = parts.size == 1

    fun compatibilityPattern(): URLPathSegmentPattern =
        parts.singleOrNull()?.let { part ->
            when (part) {
                is PathSegmentPart.Literal -> URLPathSegmentPattern(ExactValuePattern(StringValue(part.value)))
                is PathSegmentPart.Parameter -> part.pattern
            }
        } ?: parts.filterIsInstance<PathSegmentPart.Parameter>().firstOrNull()?.pattern
        ?: URLPathSegmentPattern(ExactValuePattern(StringValue(raw)))

    fun pathParameterPatterns(): List<URLPathSegmentPattern> =
        parts.filterIsInstance<PathSegmentPart.Parameter>().map { it.pattern }

    fun parseTokenValues(segmentValue: String): Map<URLPathSegmentPattern, String>? {
        if (isSimple()) {
            val part = parts.single()
            return when (part) {
                is PathSegmentPart.Literal -> if (part.value == segmentValue) emptyMap() else null
                is PathSegmentPart.Parameter -> mapOf(part.pattern to segmentValue)
            }
        }

        var cursor = 0
        val values = mutableMapOf<URLPathSegmentPattern, String>()

        parts.forEachIndexed { index, part ->
            when (part) {
                is PathSegmentPart.Literal -> {
                    if (!segmentValue.startsWith(part.value, cursor)) {
                        return null
                    }
                    cursor += part.value.length
                }

                is PathSegmentPart.Parameter -> {
                    val nextLiteral = parts.drop(index + 1).filterIsInstance<PathSegmentPart.Literal>().firstOrNull { it.value.isNotEmpty() }
                    val endIndex = nextLiteral?.let { literal ->
                        segmentValue.indexOf(literal.value, cursor).takeIf { it >= 0 } ?: return null
                    } ?: segmentValue.length

                    val token = segmentValue.substring(cursor, endIndex)
                    values[part.pattern] = token
                    cursor = endIndex
                }
            }
        }

        return values.takeIf { cursor == segmentValue.length }
    }

    fun render(
        resolver: Resolver,
        paramTokenProvider: (URLPathSegmentPattern, Resolver) -> String,
    ): String {
        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)

        return parts.joinToString(separator = "") { part ->
            when (part) {
                is PathSegmentPart.Literal -> part.value
                is PathSegmentPart.Parameter -> paramTokenProvider(part.pattern, updatedResolver)
            }
        }
    }
}

data class HttpPathPattern(
    private val pathSegmentPatterns: List<URLPathSegmentPattern>,
    private val path: String,
    val otherPathPatterns: Collection<HttpPathPattern> = emptyList(),
) {
    private val parsedSegments: List<ParsedPathSegment> by lazy {
        parsePathSegments(path, pathSegmentPatterns)
    }
    private val usesLegacySegmentModel: Boolean by lazy {
        pathSegmentPatterns.size == parsedSegments.size && parsedSegments.all { it.isSimple() }
    }

    fun originalPath(): String = path

    fun segmentCount(): Int = parsedSegments.size

    fun getPathSegmentPatterns(): List<URLPathSegmentPattern> =
        if (usesLegacySegmentModel) pathSegmentPatterns else parsedSegments.map { it.compatibilityPattern() }

    fun allPathParameterPatterns(): List<URLPathSegmentPattern> =
        if (usesLegacySegmentModel) pathSegmentPatterns.filter { it.pattern !is ExactValuePattern } else parsedSegments.flatMap { it.pathParameterPatterns() }

    fun allPathParameterNames(): List<String> = allPathParameterPatterns().mapNotNull { it.key }

    fun hasPathParameter(name: String): Boolean = allPathParameterNames().contains(name)

    fun applyPathParamValue(requestPath: String, paramName: String, replacement: String): String? {
        val requestSegments = requestPath.split("/").filter { it.isNotEmpty() }
        if (requestSegments.size != parsedSegments.size) return null

        var replaced = false
        val updatedSegments = parsedSegments.zip(requestSegments).map { (segment, requestSegment) ->
            val tokenMap = segment.parseTokenValues(requestSegment) ?: return null

            if (segment.parts.size == 1 && segment.parts.single() is PathSegmentPart.Parameter) {
                val parameterPart = segment.parts.single() as PathSegmentPart.Parameter
                if (parameterPart.pattern.key == paramName) {
                    replaced = true
                    return@map replacement
                }

                return@map requestSegment
            }

            val rebuilt = StringBuilder()
            segment.parts.forEach { part ->
                when (part) {
                    is PathSegmentPart.Literal -> rebuilt.append(part.value)
                    is PathSegmentPart.Parameter -> {
                        val value = if (part.pattern.key == paramName) {
                            replaced = true
                            replacement
                        } else {
                            tokenMap[part.pattern] ?: return null
                        }
                        rebuilt.append(value)
                    }
                }
            }
            rebuilt.toString()
        }

        if (!replaced) return null

        val prefix = "/".takeIf { requestPath.startsWith("/") }.orEmpty()
        return updatedSegments.joinToString("/", prefix = prefix)
    }

    private fun calculateSpecificity(): Int =
        if (usesLegacySegmentModel) pathSegmentPatterns.count { it.pattern is ExactValuePattern }
        else parsedSegments.count { segment -> segment.parts.size == 1 && segment.parts.single() is PathSegmentPart.Literal }

    fun encompasses(otherHttpPathPattern: HttpPathPattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        if (this.matches(URI.create(otherHttpPathPattern.originalPath()), resolver = thisResolver) is Success)
            return Success()

        if (this.segmentCount() != otherHttpPathPattern.segmentCount()) {
            return Failure(
                "Path segment count mismatch: Expected ${this.originalPath()} (having ${this.segmentCount()} path segments) to have the same number of segments as ${otherHttpPathPattern.originalPath()} (which has ${otherHttpPathPattern.segmentCount()} path segments).",
                breadCrumb = BreadCrumb.PATH.value,
                failureReason = FailureReason.URLPathMisMatch,
            )
        }

        val thisPatterns = this.getPathSegmentPatterns()
        val otherPatterns = otherHttpPathPattern.getPathSegmentPatterns()

        val mismatchedPartResults =
            thisPatterns.zip(otherPatterns)
                .map { (thisPathItem, otherPathItem) ->
                    thisPathItem.encompasses(otherPathItem, thisResolver, otherResolver)
                }

        val failures = mismatchedPartResults.filterIsInstance<Failure>()

        if (failures.isEmpty())
            return Success()

        return Result.fromFailures(failures)
    }

    fun matches(uri: URI, resolver: Resolver = Resolver()): Result {
        return matches(uri.path, resolver)
    }

    fun matches(path: String, resolver: Resolver): Result {
        val httpRequest = HttpRequest(path = path)
        return matches(httpRequest, resolver)
    }

    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val requestPath = httpRequest.path!!
        val pathSegments = requestPath.split("/".toRegex()).filter { it.isNotEmpty() }

        val expectedSegmentCount = if (usesLegacySegmentModel) pathSegmentPatterns.size else parsedSegments.size
        if (expectedSegmentCount != pathSegments.size) {
            return Failure(
                "Expected $requestPath (having ${pathSegments.size} path segments) to match ${this.path} (which has ${expectedSegmentCount} path segments).",
                breadCrumb = BreadCrumb.PATH.value,
                failureReason = FailureReason.URLPathMisMatch,
            )
        }

        val results = checkIfPathSegmentsMatch(pathSegments, resolver, requestPath)

        val failures = results.filterIsInstance<Failure>()
        val segmentMatchResult = Result.fromResults(failures)

        if (!structureMatches(requestPath, resolver)) {
            return segmentMatchResult.withFailureReason(FailureReason.URLPathMisMatch)
        }

        val otherPathsInSpecThatMatch =
            otherPathPatterns.filter { otherPattern ->
                otherPattern.originalPath() != this.originalPath() && otherPattern.matches(requestPath, resolver).isSuccess()
            }

        if (otherPathsInSpecThatMatch.isNotEmpty()) {
            val specificityOfCurrentPattern = calculateSpecificity()

            val conflictingPatterns =
                otherPathsInSpecThatMatch.filter { otherPathPattern ->
                    otherPathPattern.calculateSpecificity() > specificityOfCurrentPattern
                }

            if (conflictingPatterns.isNotEmpty()) {
                val mostSpecificPathMatch = conflictingPatterns.maxBy { it.calculateSpecificity() }

                return Failure(
                    breadCrumb = BreadCrumb.PATH.value,
                    message = "URL $requestPath matches a more specific pattern: ${mostSpecificPathMatch.originalPath()}",
                    failureReason = FailureReason.URLPathParamMatchButConflict,
                )
            }
        }

        if (failures.isNotEmpty()) {
            return segmentMatchResult.withFailureReason(FailureReason.URLPathParamMismatchButSameStructure)
        }

        return Success()
    }

    private fun checkIfPathSegmentsMatch(
        pathSegments: List<String>,
        resolver: Resolver,
        path: String
    ): List<Result> = if (usesLegacySegmentModel) {
        pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
            try {
                val parsedValue = urlPathPattern.tryParse(token, resolver)
                val result = urlPathPattern.matches(parsedValue, resolver)
                if (result is Failure) {
                    when (urlPathPattern.key) {
                        null -> result.breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)")
                            .withFailureReason(FailureReason.URLPathMisMatch)

                        else -> result.breadCrumb(urlPathPattern.key).breadCrumb(BreadCrumb.PARAM_PATH.value)
                    }
                } else {
                    Success()
                }
            } catch (e: ContractException) {
                e.failure().breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)").let { failure ->
                    urlPathPattern.key?.let { failure.breadCrumb(urlPathPattern.key) } ?: failure
                }.withFailureReason(FailureReason.URLPathMisMatch)
            } catch (e: Throwable) {
                Failure(e.localizedMessage).breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)").let { failure ->
                    urlPathPattern.key?.let { failure.breadCrumb(urlPathPattern.key) } ?: failure
                }.withFailureReason(FailureReason.URLPathMisMatch)
            }
        }
    } else parsedSegments.zip(pathSegments).map { (parsedSegment, token) ->
        if (parsedSegment.isSimple()) {
            val pattern = parsedSegment.compatibilityPattern()
            try {
                val parsedValue = pattern.tryParse(token, resolver)
                val result = pattern.matches(parsedValue, resolver)
                if (result is Failure) {
                    when (pattern.key) {
                        null -> result.breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)")
                            .withFailureReason(FailureReason.URLPathMisMatch)

                        else -> result.breadCrumb(pattern.key).breadCrumb(BreadCrumb.PARAM_PATH.value)
                    }
                } else {
                    Success()
                }
            } catch (e: ContractException) {
                e.failure().breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)").let { failure ->
                    pattern.key?.let { failure.breadCrumb(pattern.key) } ?: failure
                }.withFailureReason(FailureReason.URLPathMisMatch)
            } catch (e: Throwable) {
                Failure(e.localizedMessage).breadCrumb("${BreadCrumb.PARAM_PATH.value} ($path)").let { failure ->
                    pattern.key?.let { failure.breadCrumb(pattern.key) } ?: failure
                }.withFailureReason(FailureReason.URLPathMisMatch)
            }
        } else {
            val values = parsedSegment.parseTokenValues(token)
                ?: return@map Failure(
                    "Expected \"${parsedSegment.raw}\", actual was \"$token\"",
                    breadCrumb = "${BreadCrumb.PARAM_PATH.value} ($path)",
                    failureReason = FailureReason.URLPathMisMatch,
                )

            val failures = values.mapNotNull { (pattern, valueToken) ->
                runCatching {
                    val parsedValue = pattern.tryParse(valueToken, resolver)
                    val result = pattern.matches(parsedValue, resolver)
                    if (result is Failure) {
                        (pattern.key?.let { result.breadCrumb(it) } ?: result)
                            .breadCrumb(BreadCrumb.PARAM_PATH.value) as Failure
                    } else {
                        null
                    }
                }.getOrElse { e ->
                    (if (e is ContractException) e.failure() else Failure(e.localizedMessage))
                        .let { failure -> pattern.key?.let { failure.breadCrumb(it) } ?: failure }
                        .breadCrumb(BreadCrumb.PARAM_PATH.value) as Failure
                }
            }

            Result.fromFailures(failures)
        }
    }

    fun generate(resolver: Resolver): String {
        if (usesLegacySegmentModel) {
            val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
            return attempt(breadCrumb = BreadCrumb.PARAM_PATH.value) {
                ("/" + pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
                    attempt(breadCrumb = "[$index]") {
                        val key = urlPathPattern.key
                        updatedResolver.withCyclePrevention(urlPathPattern.pattern) { cyclePreventedResolver ->
                            if (key != null) cyclePreventedResolver.generate(null, key, urlPathPattern.pattern)
                            else urlPathPattern.pattern.generate(cyclePreventedResolver)
                        }
                    }
                }.joinToString("/")).let {
                    if (path.endsWith("/") && !it.endsWith("/")) "$it/" else it
                }.let {
                    if (path.startsWith("/") && !it.startsWith("/")) "$it" else it
                }
            }
        }

        return attempt(breadCrumb = BreadCrumb.PARAM_PATH.value) {
            ("/" + parsedSegments.mapIndexed { index, segment ->
                attempt(breadCrumb = "[$index]") {
                    segment.render(resolver) { urlPathPattern, updatedResolver ->
                        val key = urlPathPattern.key
                        updatedResolver.withCyclePrevention(urlPathPattern.pattern) { cyclePreventedResolver ->
                            if (key != null) cyclePreventedResolver.generate(null, key, urlPathPattern.pattern)
                            else urlPathPattern.pattern.generate(cyclePreventedResolver)
                        }.toStringLiteral()
                    }
                }
            }.joinToString("/")).let {
                if (path.endsWith("/") && !it.endsWith("/")) "$it/" else it
            }.let {
                if (path.startsWith("/") && !it.startsWith("/")) "$it" else it
            }
        }
    }

    fun newBasedOn(
        row: Row,
        resolver: Resolver
    ): Sequence<List<URLPathSegmentPattern>> {
        val generatedPatterns = newListBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathParamPattern ->
            val key = urlPathParamPattern.key
            if (key === null || !row.containsField(key)) return@mapIndexed urlPathParamPattern
            attempt(breadCrumb = BreadCrumb.PARAM_PATH.with(withoutOptionality(key))) {
                val rowValue = row.getField(key)
                when {
                    isPatternToken(rowValue) -> attempt("Pattern mismatch in example of path param \"${urlPathParamPattern.key}\"") {
                        val rowValueWithoutWithoutIdentifier = withoutPatternDelimiters(rowValue).split(':').let {
                            it.getOrNull(1) ?: it.getOrNull(0)
                            ?: throw ContractException("Invalid pattern token $rowValue in example")
                        }.let {
                            withPatternDelimiters(it)
                        }
                        val rowPattern = resolvedHop(resolver.getPattern(rowValueWithoutWithoutIdentifier), resolver)
                        val pathSegmentPattern = resolvedHop(urlPathParamPattern.pattern, resolver)

                        if (pathSegmentPattern.javaClass == rowPattern.javaClass) {
                            urlPathParamPattern
                        } else {
                            when (val result = urlPathParamPattern.encompasses(rowPattern, resolver, resolver)) {
                                is Success -> urlPathParamPattern.copy(pattern = rowPattern)
                                is Failure -> throw ContractException(result.toFailureReport())
                            }
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
                            ),
                            urlPathParamPattern.key
                        )
                    }
                }
            }
        }, row, resolver).map { it.value }

        return generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }
    }

    fun readFrom(row: Row, resolver: Resolver): Sequence<List<URLPathSegmentPattern>> {
        val generatedPatterns = newListBasedOn(pathSegmentPatterns.map { urlPathParamPattern ->
            val key = urlPathParamPattern.key
            if (key === null || !row.containsField(key)) return@map urlPathParamPattern

            attempt(breadCrumb = BreadCrumb.PARAM_PATH.with(withoutOptionality(key))) {
                val rowValue = row.getField(key)
                when {
                    isPatternToken(rowValue) -> {
                        val parts = withoutPatternDelimiters(rowValue).split(':')
                        val tokenBody = parts.getOrNull(1) ?: parts.getOrNull(0)
                        ?: throw ContractException("Invalid pattern token $rowValue in example")
                        val pattern = resolver.getPattern(withPatternDelimiters(tokenBody))
                        resolvedHop(pattern, resolver)
                    }

                    else -> {
                        val exactValue = parsedScalarValue(rowValue)
                        URLPathSegmentPattern(ExactValuePattern(exactValue))
                    }
                }
            }
        }, row, resolver).map { it.value }

        return generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }
    }

    fun newBasedOn(resolver: Resolver): Sequence<List<URLPathSegmentPattern>> {
        val generatedPatterns = newBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
            attempt(breadCrumb = "[$index]") {
                urlPathPattern
            }
        }, resolver)

        return generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }
    }

    override fun toString(): String {
        return path
    }

    fun toOpenApiPath(): String {
        return convertPathParameterStyle(this.path)
    }

    fun pathParameters(): List<URLPathSegmentPattern> {
        return allPathParameterPatterns().filter { !it.pattern.instanceOf(ExactValuePattern::class) }
    }

    private fun negatively(
        patterns: List<URLPathSegmentPattern>,
        row: Row,
        resolver: Resolver
    ): Sequence<ReturnValue<List<URLPathSegmentPattern>>> {
        return Sequence {
            patterns.associateWith { it.negativeBasedOn(row, resolver) }
                .flatMap { (pathSegmentPattern, negativePatterns) ->
                    negativePatterns.map { negativePatternR ->
                        negativePatternR.ifValue { negativePattern ->
                            patterns.map {
                                if (it == pathSegmentPattern)
                                    negativePattern
                                else
                                    it
                            }.filterIsInstance<URLPathSegmentPattern>()
                        }
                    }
                }.iterator()
        }
    }

    fun negativeBasedOn(
        row: Row,
        resolver: Resolver
    ): Sequence<ReturnValue<List<URLPathSegmentPattern>>> {
        return negatively(pathSegmentPatterns, row, resolver)
    }

    fun extractPathParamValues(requestPath: String, resolver: Resolver): Map<String, String> {
        return extractPathParams(requestPath, resolver)
    }

    fun extractPathParams(requestPath: String, resolver: Resolver): Map<String, String> {
        if (usesLegacySegmentModel) {
            val pathSegments = requestPath.split("/").filter { it.isNotEmpty() }

            return pathSegmentPatterns.zip(pathSegments).mapNotNull { (pattern, value) ->
                when {
                    pattern.pattern is ExactValuePattern -> null
                    else -> pattern.key?.let { it to value }
                }
            }.toMap()
        }

        val pathSegments = requestPath.split("/").filter { it.isNotEmpty() }
        if (pathSegments.size != parsedSegments.size) return emptyMap()

        return parsedSegments.zip(pathSegments)
            .flatMap { (segment, segmentValue) ->
                val tokenMap = segment.parseTokenValues(segmentValue).orEmpty()
                tokenMap.mapNotNull { (pattern, value) ->
                    when {
                        pattern.pattern is ExactValuePattern -> null
                        else -> pattern.key?.let { it to value }
                    }
                }
            }.toMap()
    }

    fun fixValue(path: String?, resolver: Resolver): String {
        if (path == null) return this.generate(resolver)

        if (usesLegacySegmentModel) {
            val pathSegments = path.split("/".toRegex()).filter { it.isNotEmpty() }
            if (pathSegmentPatterns.size != pathSegments.size) return this.generate(resolver)

            val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
            val pathHadPrefix = path.startsWith("/")

            return pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
                val tokenWithoutParameter = removeKeyFromParameterToken(token)
                val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
                val result = urlPathPattern.fixValue(
                    value = urlPathPattern.tryParse(token, updatedResolver),
                    resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                )
                token.takeIf { isPatternToken(tokenWithoutParameter) && isPatternToken(result) } ?: result.toTokenString()
            }.joinToString("/", prefix = "/".takeIf { pathHadPrefix }.orEmpty())
        }

        val pathSegments = path.split("/".toRegex()).filter { it.isNotEmpty() }
        if (parsedSegments.size != pathSegments.size) return this.generate(resolver)

        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
        val pathHadPrefix = path.startsWith("/")

        return parsedSegments.zip(pathSegments).map { (segment, token) ->
            if (segment.isSimple()) {
                val urlPathPattern = segment.compatibilityPattern()
                val tokenWithoutParameter = removeKeyFromParameterToken(token)
                val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
                val result = urlPathPattern.fixValue(
                    value = urlPathPattern.tryParse(token, updatedResolver),
                    resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                )
                token.takeIf { isPatternToken(tokenWithoutParameter) && isPatternToken(result) } ?: result
            } else {
                val valuesByPattern = segment.parseTokenValues(token).orEmpty()
                segment.parts.joinToString(separator = "") { part ->
                    when (part) {
                        is PathSegmentPart.Literal -> part.value
                        is PathSegmentPart.Parameter -> {
                            val tokenValue = valuesByPattern[part.pattern] ?: ""
                            val normalizedToken = removeKeyFromParameterToken(tokenValue)
                            val (key, keyPattern) = part.pattern.let { it.key.orEmpty() to it.pattern }
                            val fixedValue = part.pattern.fixValue(
                                value = part.pattern.tryParse(normalizedToken, updatedResolver),
                                resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                            )
                            tokenValue.takeIf { isPatternToken(removeKeyFromParameterToken(tokenValue)) && isPatternToken(fixedValue) }
                                ?: fixedValue.toTokenString()
                        }
                    }
                }
            }
        }.joinToString("/", prefix = "/".takeIf { pathHadPrefix }.orEmpty())
    }

    fun fillInTheBlanks(path: String?, resolver: Resolver): ReturnValue<String> {
        if (path == null) return HasFailure("Path cannot be null")

        if (usesLegacySegmentModel) {
            val pathSegments = path.split("/").filter { it.isNotEmpty() }.map(::removeKeyFromParameterToken)
            if (pathSegmentPatterns.size != pathSegments.size) {
                return HasFailure("Expected ${pathSegmentPatterns.size} path segments but got ${pathSegments.size}")
            }

            val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
            val pathHadPrefix = path.startsWith("/")

            val generatedSegments = pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
                val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
                urlPathPattern.fillInTheBlanks(
                    value = urlPathPattern.tryParse(token, updatedResolver),
                    resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                ).breadCrumb(urlPathPattern.key)
            }.listFold()

            return generatedSegments.ifValue { value ->
                value.joinToString(separator = "/", prefix = "/".takeIf { pathHadPrefix }.orEmpty()) {
                    it.toStringLiteral()
                }
            }
        }

        val pathSegments = path.split("/").filter { it.isNotEmpty() }
        if (parsedSegments.size != pathSegments.size) {
            return HasFailure("Expected ${parsedSegments.size} path segments but got ${pathSegments.size}")
        }

        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
        val pathHadPrefix = path.startsWith("/")

        val generatedSegments = parsedSegments.zip(pathSegments).map { (segment, token) ->
            if (segment.isSimple()) {
                val urlPathPattern = segment.compatibilityPattern()
                val normalizedToken = removeKeyFromParameterToken(token)
                val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
                urlPathPattern.fillInTheBlanks(
                    value = urlPathPattern.tryParse(normalizedToken, updatedResolver),
                    resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                ).breadCrumb(urlPathPattern.key).ifValue { it.toStringLiteral() }
            } else {
                val tokenValues = segment.parseTokenValues(token)
                    ?: return@map HasFailure("Expected \"${segment.raw}\", actual was \"$token\"")

                segment.parts.map { part ->
                    when (part) {
                        is PathSegmentPart.Literal -> HasValue(part.value)
                        is PathSegmentPart.Parameter -> {
                            val tokenValue = removeKeyFromParameterToken(tokenValues[part.pattern].orEmpty())
                            val (key, keyPattern) = part.pattern.let { it.key.orEmpty() to it.pattern }
                            part.pattern.fillInTheBlanks(
                                value = part.pattern.tryParse(tokenValue, updatedResolver),
                                resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
                            ).breadCrumb(part.pattern.key).ifValue { it.toStringLiteral() }
                        }
                    }
                }.listFold().ifValue { it.joinToString(separator = "") }
            }
        }.listFold()

        return generatedSegments.ifValue { value ->
            value.joinToString(separator = "/", prefix = "/".takeIf { pathHadPrefix }.orEmpty())
        }
    }

    private fun removeKeyFromParameterToken(token: String): String {
        if (!isPatternToken(token) || !token.contains(":")) return token
        val patternType = withoutPatternDelimiters(token).split(":").last()
        return withPatternDelimiters(patternType)
    }

    private fun Any.toTokenString(): String =
        when (this) {
            is io.specmatic.core.value.Value -> this.toStringLiteral()
            else -> this.toString()
        }

    private fun structureMatches(path: String, resolver: Resolver): Boolean {
        val pathSegments = path.split("/").filter(String::isNotEmpty)
        if (usesLegacySegmentModel) {
            if (pathSegments.size != pathSegmentPatterns.size) return false

            pathSegmentPatterns.zip(pathSegments).forEach { (pattern, segment) ->
                if (pattern.pattern !is ExactValuePattern && pattern.key != null) return@forEach
                val parsedSegment = pattern.tryParse(segment, resolver)
                val result = pattern.matches(parsedSegment, resolver)
                if (result is Failure) return false
            }

            return true
        }
        if (pathSegments.size != parsedSegments.size) return false

        parsedSegments.zip(pathSegments).forEach { (segment, segmentValue) ->
            if (segment.isSimple()) {
                val pattern = segment.compatibilityPattern()
                if (pattern.pattern !is ExactValuePattern && pattern.key != null) return@forEach
                val parsedSegment = pattern.tryParse(segmentValue, resolver)
                val result = pattern.matches(parsedSegment, resolver)
                if (result is Failure) return false
            } else {
                if (segment.parseTokenValues(segmentValue) == null) return false
            }
        }

        return true
    }

    companion object {
        fun from(path: String): HttpPathPattern {
            return buildHttpPathPattern(path)
        }
    }
}

private val SPECMATIC_SEGMENT_PARAM_REGEX = Regex("""\(([^:()]+):([^()]+)\)""")

private fun parsePathSegments(path: String, seedPatterns: List<URLPathSegmentPattern>): List<ParsedPathSegment> {
    val rawSegments = path.trim('/').split("/").filter { it.isNotEmpty() }
    val patternByName = seedPatterns.filter { it.key != null }.associateBy { it.key!! }

    return rawSegments.mapIndexed { index, rawSegment ->
        val matches = SPECMATIC_SEGMENT_PARAM_REGEX.findAll(rawSegment).toList()

        val parts = when {
            matches.isEmpty() -> {
                val seeded = seedPatterns.getOrNull(index)
                when {
                    isMatcherToken(rawSegment) -> listOf(PathSegmentPart.Parameter(URLPathSegmentPattern(parsedPattern(rawSegment), seeded?.key)))
                    seeded != null && seeded.pattern !is ExactValuePattern -> listOf(PathSegmentPart.Parameter(seeded))
                    else -> listOf(PathSegmentPart.Literal(rawSegment))
                }
            }

            else -> {
                val partsBuilder = mutableListOf<PathSegmentPart>()
                var cursor = 0

                matches.forEach { match ->
                    val start = match.range.first
                    val endExclusive = match.range.last + 1

                    if (start > cursor) {
                        partsBuilder.add(PathSegmentPart.Literal(rawSegment.substring(cursor, start)))
                    }

                    val paramName = match.groupValues[1].trim()
                    val paramType = match.groupValues[2].trim()
                    val seededPattern = seedPatterns.getOrNull(index)
                    val pattern = patternByName[paramName]?.pattern
                        ?: seededPattern?.pattern?.takeIf { matches.size == 1 }
                        ?: builtInPatterns[withPatternDelimiters(paramType)]
                        ?: DeferredPattern(withPatternDelimiters(paramType))

                    partsBuilder.add(PathSegmentPart.Parameter(URLPathSegmentPattern(pattern, paramName)))
                    cursor = endExclusive
                }

                if (cursor < rawSegment.length) {
                    partsBuilder.add(PathSegmentPart.Literal(rawSegment.substring(cursor)))
                }

                partsBuilder
            }
        }

        ParsedPathSegment(rawSegment, parts)
    }
}

fun buildHttpPathPattern(
    url: String
): HttpPathPattern =
    buildHttpPathPattern(URI.create(url))

internal fun buildHttpPathPattern(
    urlPattern: URI
): HttpPathPattern {
    val path = urlPattern.path
    val pathPattern = pathToPattern(urlPattern.rawPath)
    return HttpPathPattern(path = path, pathSegmentPatterns = pathPattern)
}

private fun parseSpecmaticPathToken(part: String, rawPath: String): URLPathSegmentPattern {
    val pieces = withoutPatternDelimiters(part).split(":").map { it.trim() }
    if (pieces.size != 2) {
        throw ContractException("In path ${rawPath}, $part must be of the format (param_name:type), e.g. (id:number)")
    }

    val (name, type) = pieces
    val pattern = builtInPatterns[withPatternDelimiters(type)] ?: DeferredPattern(withPatternDelimiters(type))
    return URLPathSegmentPattern(pattern, name)
}

internal fun pathToPattern(rawPath: String): List<URLPathSegmentPattern> =
    rawPath.trim('/').split("/").filter { it.isNotEmpty() }.map { part ->
        when {
            SPECMATIC_SEGMENT_PARAM_REGEX.containsMatchIn(part) -> {
                val first = SPECMATIC_SEGMENT_PARAM_REGEX.find(part)
                    ?: return@map URLPathSegmentPattern(ExactValuePattern(StringValue(part)))
                val token = first.value
                parseSpecmaticPathToken(token, rawPath)
            }

            isPatternToken(part) -> parseSpecmaticPathToken(part, rawPath)

            isMatcherToken(part) -> URLPathSegmentPattern(parsedPattern(part))

            else -> URLPathSegmentPattern(ExactValuePattern(StringValue(part)))
        }
    }
