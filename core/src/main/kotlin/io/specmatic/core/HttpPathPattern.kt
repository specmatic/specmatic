package io.specmatic.core

import io.ktor.util.reflect.*
import io.specmatic.conversions.TemplateTokenizer
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.net.URI

val OMIT = listOf("(OMIT)", "(omit)")

data class HttpPathPattern(
    private val pathSegmentPatterns: List<URLPathSegmentPattern>,
    private val path: String,
    private val otherPathPatterns: Collection<HttpPathPattern> = emptyList(),
) {
    private val pathSegmentExtractor: PathSegmentExtractor = PathSegmentExtractor(path, pathSegmentPatterns)
    private fun calculateSpecificity(): Int {
        val segments = this.path.removePrefix("/").removeSuffix("/").split("/")
        return segments.sumOf(::segmentSpecificity)
    }

    private fun segmentSpecificity(segment: String): Int {
        return when {
            internalPathRegex.matchEntire(segment) != null -> 0
            internalPathRegex.containsMatchIn(segment) -> 1
            else -> 2
        }
    }

    fun encompasses(otherHttpPathPattern: HttpPathPattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        if (this.matches(URI.create(otherHttpPathPattern.path), resolver = thisResolver) is Success)
            return Success()

        if (this.pathSegmentPatterns.size != otherHttpPathPattern.pathSegmentPatterns.size) {
            return Failure(
                "Path segment count mismatch: Expected ${this.path} (having ${this.pathSegmentPatterns.size} path segments) to have the same number of segments as ${otherHttpPathPattern.path} (which has ${otherHttpPathPattern.pathSegmentPatterns.size} path segments).",
                breadCrumb = BreadCrumb.PATH.value,
                failureReason = FailureReason.URLPathMisMatch,
            )
        }

        val mismatchedPartResults =
            this.pathSegmentPatterns.zip(otherHttpPathPattern.pathSegmentPatterns)
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
        val path = httpRequest.path!!

        val slashBasedRawPathSegments = splitPathBySlash(path)
        val slashBasedPathSegments = splitPathBySlash(this.path)
        if (slashBasedPathSegments.size != slashBasedRawPathSegments.size) {
            return Failure(
                "Expected $path (having ${slashBasedRawPathSegments.size} path segments) to match ${this.path} (which has ${slashBasedPathSegments.size} path segments).",
                breadCrumb = BreadCrumb.PATH.value,
                failureReason = FailureReason.URLPathMisMatch
            )
        }

        val pathSegments = extractPathSegments(path)
        if (pathSegmentPatterns.size != pathSegments.size) {
            return Failure("Expected $path to match ${this.path}.", breadCrumb = BreadCrumb.PATH.value, failureReason = FailureReason.URLPathMisMatch)
        }

        val results = checkIfPathSegmentsMatch(pathSegments, resolver, path)

        val failures = results.filterIsInstance<Failure>()
        val segmentMatchResult = Result.fromResults(failures)

        if (!structureMatches(path, resolver)) {
            return segmentMatchResult.withFailureReason(FailureReason.URLPathMisMatch)
        }

        val otherPathsInSpecThatMatch =
            otherPathPatterns.filter { otherPattern ->
                otherPattern.path != this.path && otherPattern.matches(path, resolver).isSuccess()
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
                    message = "URL $path matches a more specific pattern: ${mostSpecificPathMatch.path}",
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
    ) = pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
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

    fun generate(resolver: Resolver): String {
        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
        return attempt(breadCrumb = BreadCrumb.PARAM_PATH.value) {
            pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
                attempt(breadCrumb = "[$index]") {
                    val key = urlPathPattern.key
                    updatedResolver.withCyclePrevention(urlPathPattern.pattern) { cyclePreventedResolver ->
                        if (key != null) cyclePreventedResolver.generate(null, key, urlPathPattern.pattern)
                        else urlPathPattern.pattern.generate(cyclePreventedResolver)
                    }
                }
            }.map(Value::toStringLiteral).joinToPath()
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

        //TODO: replace this with Generics
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

        //TODO: replace this with Generics
        return generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }
    }

    fun newBasedOn(resolver: Resolver): Sequence<List<URLPathSegmentPattern>> {
        val generatedPatterns = newBasedOn(pathSegmentPatterns.mapIndexed { index, urlPathPattern ->
            attempt(breadCrumb = "[$index]") {
                urlPathPattern
            }
        }, resolver)

        //TODO: replace this with Generics
        return generatedPatterns.map { list -> list.map { it as URLPathSegmentPattern } }
    }

    override fun toString(): String {
        return path
    }

    fun toOpenApiPath(): String {
        return convertPathParameterStyle(this.path)
    }

    fun toInternalPath(): String {
        return path
    }

    fun pathParameters(): List<URLPathSegmentPattern> {
        return pathSegmentPatterns.filter { !it.pattern.instanceOf(ExactValuePattern::class) && !it.key.isNullOrBlank() }
    }

    fun containsParameter(name: String): Boolean {
        return this.pathSegmentPatterns.any { it.key == name }
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

    private fun patternFromExample(
        key: String?,
        row: Row,
        urlPathPattern: URLPathSegmentPattern,
        resolver: Resolver
    ): Sequence<ReturnValue<Pattern>> = when {
        key !== null && row.containsField(key) -> {
            val rowValue = row.getField(key)
            when {
                isPatternToken(rowValue) -> attempt("Pattern mismatch in example of path param \"${urlPathPattern.key}\"") {
                    val rowPattern = resolver.getPattern(rowValue)
                    when (val result = urlPathPattern.encompasses(rowPattern, resolver, resolver)) {
                        is Success -> sequenceOf(urlPathPattern.copy(pattern = rowPattern))
                        is Failure -> throw ContractException(result.toFailureReport())
                    }
                }

                else -> attempt("Format error in example of path parameter \"$key\"") {
                    val value = urlPathPattern.parse(rowValue, resolver)

                    val matchResult = urlPathPattern.matches(value, resolver)
                    if (matchResult is Failure)
                        throw ContractException("""Could not run contract test, the example value ${value.toStringLiteral()} provided "id" does not match the contract.""")

                    sequenceOf(URLPathSegmentPattern(ExactValuePattern(value)))
                }
            }.map { HasValue(it) }
        }

        else -> returnValueSequence {
            val positives: Sequence<Pattern> = urlPathPattern.newBasedOnWrapper(row, resolver)
            val negatives: Sequence<ReturnValue<Pattern>> = urlPathPattern.negativeBasedOn(row, resolver)

            positives.map { HasValue(it) } + negatives
        }
    }

    fun extractPathParams(requestPath: String, resolver: Resolver): Map<String, Value> {
        val pathSegments = extractPathSegments(requestPath)
        return pathSegmentPatterns.zip(pathSegments).mapNotNull { (pattern, value) ->
            when {
                pattern.pattern is ExactValuePattern -> null
                else -> pattern.key!! to pattern.tryParse(value, resolver)
            }
        }.toMap()
    }

    fun toMapIndexed(path: String, resolver: Resolver): Map<String, Value> {
        val pathSegments = extractPathSegments(path)
        return pathSegmentPatterns.zip(pathSegments).mapIndexed { index, (pattern, value) ->
            when {
                pattern.pattern is ExactValuePattern -> index.toString() to pattern.pattern.pattern
                else -> pattern.key!! to pattern.tryParse(value, resolver)
            }
        }.toMap()
    }

    fun extractPatternToMap(path: String, resolver: Resolver): Map<URLPathSegmentPattern, Value> {
        val pathSegments = extractPathSegments(path)
        return pathSegmentPatterns.zip(pathSegments).mapNotNull { (pattern, value) ->
            when {
                pattern.pattern is ExactValuePattern -> null
                else -> Pair(pattern, pattern.tryParse(value, resolver))
            }
        }.toMap()
    }

    fun <T> onPatterns(block: (List<URLPathSegmentPattern>) -> T): T {
        return block(pathSegmentPatterns)
    }

    fun updatePathParameter(path: String, parameterName: String, newValue: Value): String? {
        val pathSegments = extractPathSegments(path)
        if (pathSegments.size != pathSegmentPatterns.size) return null
        val updatedSegments = pathSegmentPatterns.zip(pathSegments).map { (pattern, segmentValue)  ->
            when {
                pattern.pattern is ExactValuePattern -> segmentValue
                pattern.key == parameterName -> newValue.toStringLiteral()
                else -> segmentValue
            }
        }

        if (pathSegmentPatterns.none { it.key == parameterName }) return null
        return updatedSegments.joinToString("")
    }

    fun zipWithExpandedPathSegments(other: HttpPathPattern, resolver: Resolver): List<Pair<URLPathSegmentPattern, URLPathSegmentPattern>>? {
        val expanded = expandPath(other, resolver)
        if (pathSegmentPatterns.size != expanded.pathSegmentPatterns.size) return null
        return pathSegmentPatterns.zip(expanded.pathSegmentPatterns)
    }

    fun fixValue(path: String?, resolver: Resolver): String {
        if (path == null) return this.generate(resolver)
        val pathSegments = extractPathSegments(path)
        if (pathSegmentPatterns.size != pathSegments.size) return this.generate(resolver)

        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
        return pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
            val tokenWithoutParameter = removeKeyFromParameterToken(token)
            val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
            val result = urlPathPattern.fixValue(
                value = urlPathPattern.tryParse(token, updatedResolver),
                resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
            )
            if (isPatternToken(tokenWithoutParameter) && isPatternToken(result)) StringValue(token) else result
        }.map(Value::toStringLiteral).joinToPath()
    }

    fun fillInTheBlanks(path: String?, resolver: Resolver): ReturnValue<String> {
        if (path == null) return HasFailure("Path cannot be null")
        val pathSegments = extractPathSegments(path)
        if (pathSegmentPatterns.size != pathSegments.size) {
            return HasFailure("Expected ${pathSegmentPatterns.size} path segments but got ${pathSegments.size}")
        }

        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.PATH.value)
        val generatedSegments = pathSegmentPatterns.zip(pathSegments).map { (urlPathPattern, token) ->
            val (key, keyPattern) = urlPathPattern.let { it.key.orEmpty() to it.pattern }
            urlPathPattern.fillInTheBlanks(
                value = urlPathPattern.tryParse(removeKeyFromParameterToken(token), updatedResolver),
                resolver = updatedResolver.updateLookupPath(null, KeyWithPattern(key, keyPattern))
            ).breadCrumb(urlPathPattern.key)
        }.listFold()

        return generatedSegments.ifValue { value ->
            value.map { it.toStringLiteral() }.joinToPath()
        }
    }

    private fun expandPath(other: HttpPathPattern, resolver: Resolver): HttpPathPattern {
        if (this.pathSegmentPatterns.size == other.pathSegmentPatterns.size) return other
        val segments = pathSegmentExtractor.extract(other.path)
        if (segments.size != this.pathSegmentPatterns.size) return other
        return other.copy(pathSegmentPatterns = pathSegmentPatterns.zip(segments).map { (pattern, segment) ->
            if (pattern.pattern is ExactValuePattern) return@map URLPathSegmentPattern(ExactValuePattern(StringValue(segment)))
            URLPathSegmentPattern(ExactValuePattern(pattern.tryParse(segment, resolver)))
        })
    }

    private fun removeKeyFromParameterToken(token: String): String {
        if (!isPatternToken(token) || !token.contains(":")) return token
        val patternType = withoutPatternDelimiters(token).split(":").last()
        return withPatternDelimiters(patternType)
    }

    private fun structureMatches(path: String, resolver: Resolver): Boolean {
        val pathSegments = extractPathSegments(path)
        if (pathSegments.size != pathSegmentPatterns.size) return false

        pathSegmentPatterns.zip(pathSegments).forEach { (pattern, segment) ->
            if (pattern.pattern !is ExactValuePattern && pattern.key != null) return@forEach
            val parsedSegment = pattern.tryParse(segment, resolver)
            val result = pattern.matches(parsedSegment, resolver)
            if (result is Failure) return false
        }

        return true
    }

    private fun extractPathSegments(rawPath: String): List<String> {
        return pathSegmentExtractor.extract(rawPath)
    }

    private fun splitPathBySlash(path: String): List<String> {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split("/").filter(String::isNotBlank)
    }

    private fun List<String>.joinToPath(): String {
        val combined = joinToString(separator = "")
        return pathSegmentExtractor.ensurePrefixAndSuffix(combined)
    }

    companion object {
        internal val internalPathRegex: Regex = Regex("\\([^():]+:[^()]+\\)")

        internal fun extractUsingInternalPathRegex(path: String): TemplateTokenizer {
            val parts = internalPathRegex.split(path)
            val pattern = parts.joinToString(separator = "([^/]+)") { Regex.escape(it) }
            return TemplateTokenizer(Regex(pattern))
        }

        fun from(path: String): HttpPathPattern {
            return buildHttpPathPattern(path)
        }
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

internal fun pathToPattern(rawPath: String): List<URLPathSegmentPattern> {
    val segments = HttpPathPattern.extractUsingInternalPathRegex(rawPath).extract(rawPath)
    return segments.map { part ->
        when {
            isPatternToken(part) -> {
                val pieces = withoutPatternDelimiters(part).split(":").map { it.trim() }
                if (pieces.size != 2) {
                    throw ContractException("In path ${rawPath}, $part must be of the format (param_name:type), e.g. (id:number)")
                }

                val (name, type) = pieces
                val pattern = builtInPatterns[withPatternDelimiters(type)] ?: DeferredPattern(withPatternDelimiters(type))
                URLPathSegmentPattern(pattern, name)
            }

            isMatcherToken(part) -> URLPathSegmentPattern(parsedPattern(part))

            else -> URLPathSegmentPattern(ExactValuePattern(StringValue(part)))
        }
    }
}
