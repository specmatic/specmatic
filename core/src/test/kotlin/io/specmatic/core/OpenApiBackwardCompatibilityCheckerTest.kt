package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class OpenApiBackwardCompatibilityCheckerTest {
    @Test
    fun `run should remove failure reasons from generated records without hiding mismatch`() {
        val oldSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "old.yaml").toFeature()

        val newSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.1
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  text/plain:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "new.yaml").toFeature()

        val records = OpenApiBackwardCompatibilityChecker(oldSpec, newSpec).run()
        val failure = records.map { it.compatResult }.filterIsInstance<Result.Failure>().single()

        assertThat(failure.isFluffy()).isFalse()
        assertThat(failure.traverseFailureReason()).isNull()
        assertThat(Results(listOf(failure)).report()).isNotEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        assertThat(failure.reportString()).isEqualToIgnoringWhitespace("""
        In scenario "POST /orders. Response: ok"
        API: POST /orders -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.Content-Type",
                details = "This is text/plain in the new specification, but application/json in the old specification",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Nested
    inner class RequestContentTypeFallback {
        @Test
        fun `keeps every scenario in the only matching request content type group`() {
            val oldSpec = spec(
                responseContentType = "application/json",
                requestContentTypes = listOf("application/vnd.example+json"),
            )

            val newSpec = spec(
                responseContentType = "application/json",
                requestContentTypes = listOf("application/*+json"),
            )

            val records = check(oldSpec, newSpec)
            val responseStatuses = listOf(202, 204, 503)
            assertThat(records).hasSize(responseStatuses.size * 3)
            assertThat(records.map { it.scenario.status }.sorted())
                .isEqualTo(responseStatuses.flatMap { status -> List(3) { status } })

            assertThat(records.filter { it.compatResult is Result.Failure }.map { it.scenario.status }.sorted())
                .isEqualTo(responseStatuses.flatMap { listOf(it, it) })

            assertThat(records.filter { it.compatResult.isSuccess() }.map { it.scenario.status }.sorted())
                .isEqualTo(responseStatuses)

            assertThat(records.map { it.scenario.requestContentType }.toSet())
                .isEqualTo(setOf("application/*+json"))
        }
    }

    @Nested
    inner class ResponseContentTypeFallback {
        @ParameterizedTest
        @ValueSource(ints = [1, 2, 3])
        fun `response media type change is independent of request content type count`(requestContentTypeCount: Int) {
            val requestContentTypes = listOf(
                "application/json",
                "application/*+json",
                "application/vnd.example+json",
            ).take(requestContentTypeCount)

            val oldSpec = spec(
                requestContentTypes = requestContentTypes,
                responseContentType = "application/json",
            )

            val newSpec = spec(
                requestContentTypes = requestContentTypes,
                responseContentType = "application/problem+json",
            )

            val records = check(oldSpec, newSpec)
            assertThat(records).hasSize(requestContentTypeCount * 9)
            assertThat(records.map { it.compatResult.isSuccess() })
                .isEqualTo(List(requestContentTypeCount * 9) { true })

            assertThat(records.map { it.scenario.status }.sorted())
                .isEqualTo(listOf(202, 204, 503).flatMap { status -> List(requestContentTypeCount * 3) { status } })

            assertThat(records.map { requireNotNull(it.scenario.requestContentType) }.sorted())
                .isEqualTo(requestContentTypes.flatMap { contentType -> List(9) { contentType } }.sorted())
        }
    }

    @Nested
    inner class CompatibleResponseCandidates {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/problem+json",
            "application/problem+json, application/json",
            "application/json, application/vnd.api+json",
            "application/vnd.api+json, application/json",
            "application/xml, application/soap+xml",
            "application/soap+xml, application/xml",
            "application/yaml, application/vnd.example+yaml",
            "application/vnd.example+yaml, application/yaml",
        )
        fun `selects compatible syntax families alongside an incompatible alternative`(oldResponseContentType: String, newResponseContentType: String) {
            val oldSpec = spec(
                requestContentTypes = listOf("application/json"),
                responseContentType = oldResponseContentType,
                responseSchema = "ResponseWithStringId",
            )

            val newSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = newResponseContentType,
                requestContentTypes = listOf("application/json"),
                additionalResponseContentTypes = listOf(
                    element = ResponseContentType(
                        mediaType = "application/octet-stream",
                        schema = "ResponseWithIntegerId"
                    )
                ),
            )

            val oldFeature = feature(oldSpec, "old.yaml")
                .withResponseBody(503, oldResponseContentType, StringPattern())

            val newFeature = feature(newSpec, "new.yaml")
                .withResponseBody(503, newResponseContentType, StringPattern())

            val records = check(oldFeature, newFeature)
            assertAllRecordsSuccessful(records)
        }

        @Test
        fun `exact content type match takes precedence over compatible alternatives`() {
            val oldSpec = spec(
                requestContentTypes = listOf("application/json"),
                responseContentType = "application/json",
                responseSchema = "ResponseWithStringId",
            )

            val newSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
                additionalResponseContentTypes = listOf(
                    element = ResponseContentType(
                        mediaType = "application/problem+json",
                        schema = "ResponseWithIntegerId",
                    ),
                ),
            )

            assertAllRecordsSuccessful(check(oldSpec, newSpec))
        }

        @Test
        fun `compares every compatible response candidate regardless of declaration order`() {
            val alternatives = listOf(
                listOf(
                    ResponseContentType("application/problem+json", "ResponseWithIntegerId"),
                    ResponseContentType("application/vnd.api+json", "ResponseWithBooleanId"),
                ),
                listOf(
                    ResponseContentType("application/vnd.api+json", "ResponseWithBooleanId"),
                    ResponseContentType("application/problem+json", "ResponseWithIntegerId"),
                ),
            )

            alternatives.forEach { (first, second) ->
                val oldSpec = spec(
                    requestContentTypes = listOf("application/json"),
                    responseContentType = "application/json",
                    responseSchema = "ResponseWithStringId",
                )

                val newSpec = spec(
                    responseSchema = first.schema,
                    responseContentType = first.mediaType,
                    additionalResponseContentTypes = listOf(second),
                    requestContentTypes = listOf("application/json"),
                )

                val records = check(oldSpec, newSpec)
                val failures = records.filter { it.compatResult is Result.Failure }
                assertThat(failures.map { requireNotNull(it.scenario.responseContentType) }.distinct().sorted())
                    .isEqualTo(listOf("application/problem+json", "application/vnd.api+json"))

                assertThat(
                    failures.flatMap { (it.compatResult as Result.Failure).toMatchFailureDetailList() }
                        .distinct()
                        .sortedBy { it.errorMessages.single() },
                ).isEqualTo(
                    listOf(
                        MatchFailureDetails(
                            breadCrumbs = listOf("RESPONSE", "BODY", "response", "id"),
                            errorMessages = listOf("This is boolean in the new specification response but string in the old specification"),
                            ruleViolationReport = RuleViolationReport(setOf(StandardRuleViolation.TYPE_MISMATCH)),
                        ),
                        MatchFailureDetails(
                            breadCrumbs = listOf("RESPONSE", "BODY", "response", "id"),
                            errorMessages = listOf("This is number in the new specification response but string in the old specification"),
                            ruleViolationReport = RuleViolationReport(setOf(StandardRuleViolation.TYPE_MISMATCH)),
                        ),
                    ),
                )
            }
        }

        @Test
        fun `keeps response schema encompassing directional when the media type changes compatibly`() {
            val oldSpec = spec(
                requestContentTypes = listOf("application/json"),
                responseContentType = "application/json",
                responseSchema = "UnconstrainedString",
            )

            val newSpec = spec(
                requestContentTypes = listOf("application/json"),
                responseContentType = "application/problem+json",
                responseSchema = "ConstrainedString",
            )

            assertAllRecordsSuccessful(check(oldSpec, newSpec))
            val reverseFailures = check(newSpec, oldSpec).filter { it.compatResult is Result.Failure }

            assertThat(reverseFailures).isNotEmpty
            assertThat(reverseFailures.map { it.scenario.responseContentType }.distinct())
                .isEqualTo(listOf("application/json"))
        }
    }

    @Nested
    inner class IncompatibleResponseCandidates {
        @ParameterizedTest
        @CsvSource(
            "application/json, application/xml",
            "application/json, text/foo+json",
            "application/foo+custom, application/custom",
            "application/foo+custom, application/foo+other",
        )
        fun `uses the single same status fallback for incompatible content types`(oldResponseContentType: String, newResponseContentType: String) {
            val oldSpec = spec(listOf("application/json"), oldResponseContentType)
            val newSpec = spec(listOf("application/json"), newResponseContentType)

            val oldFeature = feature(oldSpec, "old.yaml")
                .withResponseBody(503, oldResponseContentType, StringPattern())

            val newFeature = feature(newSpec, "new.yaml")
                .withResponseBody(503, newResponseContentType, StringPattern())

            val failures = check(oldFeature, newFeature).filter { it.compatResult is Result.Failure }
            assertContentTypeMismatch(failures, oldResponseContentType, newResponseContentType)
        }

        @Test
        fun `does not match responses across different statuses`() {
            val oldSpec = spec(listOf("application/json"), "application/json", responseStatus = 503)
            val newSpec = spec(listOf("application/json"), "application/json", responseStatus = 400)

            val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
            val removedResponseDetails = failures
                .filter { it.scenario.status == 503 }
                .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                .distinct()

            assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
        }

        @Test
        fun `does not choose among multiple unrelated content types`() {
            val oldSpec = spec(listOf("application/json"), "application/json")
            val newSpec = spec(
                requestContentTypes = listOf("application/json"),
                responseContentType = "application/xml",
                additionalResponseContentTypes = listOf(ResponseContentType("text/plain")),
            )

            val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
            val removedResponseDetails = failures
                .filter { it.scenario.status == 503 }
                .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                .distinct()

            assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
        }
    }

    @Nested
    inner class MissingAndWildcardResponseContentTypes {
        @Test
        fun `matches identical wildcard ranges`() {
            val oldSpec = spec(listOf("application/json"), "application/*")
            val newSpec = spec(listOf("application/json"), "application/*")
            assertAllRecordsSuccessful(check(oldSpec, newSpec))
        }

        @Test
        fun `single wildcard to concrete change reaches content type comparison`() {
            val oldSpec = spec(listOf("application/json"), "application/*")
            val newSpec = spec(listOf("application/json"), "application/json")
            val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
            assertContentTypeMismatch(failures, "application/*", "application/json")
        }

        @ParameterizedTest
        @ValueSource(strings = ["application/*+json", "a*/json", "*/json", "application/j*son"])
        fun `rejects invalid wildcard syntax during exact response comparison`(invalidContentType: String) {
            val oldSpec = spec(listOf("application/json"), invalidContentType)
            val newSpec = spec(listOf("application/json"), invalidContentType)
            val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
            assertContentTypeMismatch(failures, invalidContentType, invalidContentType)
        }

        @Test
        fun `keeps the no content type match when both responses omit it`() {
            val responseSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),            )

            val responseFeature = feature(responseSpec, "response.yaml")
            val noContentTypeFeature = responseFeature.withResponseContentType(status = 503, value = null)
            assertAllRecordsSuccessful(check(noContentTypeFeature, noContentTypeFeature))
        }

        @Test
        fun `keeps the single candidate fallback when either response omits content type`() {
            val responseSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
            )

            val responseFeature = feature(responseSpec, "response.yaml")
            val noContentTypeFeature = responseFeature.withResponseContentType(status = 503, value = null)
            assertAllRecordsSuccessful(check(noContentTypeFeature, responseFeature))
            assertAllRecordsSuccessful(check(responseFeature, noContentTypeFeature))
        }

        @Test
        fun `does not infer compatibility from multiple content types when the old content type is missing`() {
            val oldSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
            )

            val oldFeature = feature(oldSpec, "old.yaml").withResponseContentType(status = 503, value = null)
            val newSpec = spec(
                responseSchema = "ResponseWithStringId",
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
                additionalResponseContentTypes = listOf(ResponseContentType("text/plain")),
            )

            val failures = check(oldFeature, feature(newSpec, "new.yaml")).filter { it.compatResult is Result.Failure }
            val removedResponseDetails = failures
                .filter { it.scenario.status == 503 }
                .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                .distinct()

            assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
        }

        @Test
        fun `does not select a concrete response for a wildcard when multiple alternatives exist`() {
            val oldSpec = spec(listOf("application/json"), "application/*")
            val newSpec = spec(
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
                additionalResponseContentTypes = listOf(ResponseContentType("image/*")),
            )

            val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
            val removedResponseDetails = failures
                .filter { it.scenario.status == 503 }
                .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                .distinct()

            assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
        }

        @Test
        fun `does not choose a missing content type among multiple new alternatives`() {
            val oldSpec = spec(listOf("application/json"), "application/json")
            val newSpec = spec(
                responseContentType = "application/json",
                requestContentTypes = listOf("application/json"),
                additionalResponseContentTypes = listOf(ResponseContentType("text/plain")),
            )

            val newFeature = feature(newSpec, "new.yaml")
                .withResponseContentType(status = 503, previousValue = "application/json", value = null)

            val failures = check(feature(oldSpec, "old.yaml"), newFeature).filter { it.compatResult is Result.Failure }
            val removedResponseDetails = failures
                .filter { it.scenario.status == 503 }
                .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                .distinct()

            assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
        }

        @Test
        fun `rejects an invalid media type even when its identifier is exact`() {
            val validSpec = spec(listOf("application/json"), "application/json")
            val oldFeature = feature(validSpec, "old.yaml")
                .withResponseContentType(503, "not-a-media-type")
                .withResponseBody(503, "not-a-media-type", StringPattern())

            val newFeature = feature(validSpec, "new.yaml")
                .withResponseContentType(503, "not-a-media-type")
                .withResponseBody(503, "not-a-media-type", StringPattern())

            val failures = check(oldFeature, newFeature).filter { it.compatResult is Result.Failure }
            assertContentTypeMismatch(failures, "not-a-media-type", "not-a-media-type")
        }
    }

    @Nested
    @Execution(ExecutionMode.SAME_THREAD)
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    inner class ConfiguredSyntaxFormats {
        @Test
        fun `uses configured syntax formats through the response comparison path`() {
            withSyntaxFormatsProperty("custom") {
                val oldSpec = spec(
                    responseSchema = "ResponseWithStringId",
                    responseContentType = "application/custom",
                    requestContentTypes = listOf("application/json"),
                )
                val newSpec = spec(
                    responseSchema = "ResponseWithStringId",
                    requestContentTypes = listOf("application/json"),
                    responseContentType = "application/vnd.example+custom",
                    additionalResponseContentTypes = listOf(
                        ResponseContentType(
                            mediaType = "application/xml",
                            schema = "ResponseWithIntegerId",
                        ),
                    ),
                )

                assertAllRecordsSuccessful(check(oldSpec, newSpec))
            }
        }

        @Test
        fun `uses the default syntax formats when the property is absent`() {
            withSyntaxFormatsProperty(null) {
                val oldSpec = spec(listOf("application/json"), "application/custom")
                val newSpec = spec(
                    requestContentTypes = listOf("application/json"),
                    responseContentType = "application/vnd.example+custom",
                    additionalResponseContentTypes = listOf(ResponseContentType("text/plain")),
                )

                val failures = check(oldSpec, newSpec).filter { it.compatResult is Result.Failure }
                val removedResponseDetails = failures
                    .filter { it.scenario.status == 503 }
                    .map { (it.compatResult as Result.Failure).toMatchFailureDetailList().single() }
                    .distinct()

                assertOldResponseMissing(removedResponseDetails, "/paths/~1items~1{id}/put/responses/503")
            }
        }

        @Test
        fun `restores the previous syntax format property after each check`() {
            val propertyName = io.specmatic.core.utilities.ResponseMediaTypeMatcher.FORMATS_PROPERTY
            val originalValue = System.getProperty(propertyName)
            withSyntaxFormatsProperty("custom") {}
            assertThat(System.getProperty(propertyName)).isEqualTo(originalValue)
        }

        private fun withSyntaxFormatsProperty(value: String?, action: () -> Unit) {
            val propertyName = io.specmatic.core.utilities.ResponseMediaTypeMatcher.FORMATS_PROPERTY
            val originalValue = System.getProperty(propertyName)

            try {
                if (value == null) System.clearProperty(propertyName) else System.setProperty(propertyName, value)
                action()
            } finally {
                if (originalValue == null) System.clearProperty(propertyName) else System.setProperty(propertyName, originalValue)
            }
        }
    }

    private fun assertAllRecordsSuccessful(records: List<OpenApiBackwardCompatibilityCheckRecord>) {
        assertThat(records.filter { it.compatResult is Result.Failure })
            .isEqualTo(emptyList<OpenApiBackwardCompatibilityCheckRecord>())
    }

    private fun assertContentTypeMismatch(
        failures: List<OpenApiBackwardCompatibilityCheckRecord>,
        oldResponseContentType: String,
        newResponseContentType: String,
    ) {
        val details = failures
            .flatMap { (it.compatResult as Result.Failure).toMatchFailureDetailList() }
            .distinct()

        assertThat(details).isEqualTo(
            listOf(
                MatchFailureDetails(
                    breadCrumbs = listOf("RESPONSE", "HEADER", "Content-Type"),
                    ruleViolationReport = RuleViolationReport(setOf(StandardRuleViolation.VALUE_MISMATCH)),
                    errorMessages = listOf("This is $newResponseContentType in the new specification response but $oldResponseContentType in the old specification"),
                ),
            ),
        )
    }

    private fun assertOldResponseMissing(details: List<MatchFailureDetails>, pointer: String) {
        assertThat(details.map { it.copy(sourceLocation = null) }.distinct()).isEqualTo(
            listOf(MatchFailureDetails(errorMessages = listOf("This API exists in the old contract but not in the new contract"))),
        )
        assertThat(details.map { it.sourceLocation?.filePath }.distinct()).isEqualTo(listOf("old.yaml"))
        assertThat(details.map { it.sourceLocation?.pointer }.distinct()).isEqualTo(listOf(pointer))
    }

    private fun check(oldSpec: String, newSpec: String): List<OpenApiBackwardCompatibilityCheckRecord> {
        val oldFeature = feature(oldSpec, "old.yaml")
        val newFeature = feature(newSpec, "new.yaml")
        return check(oldFeature, newFeature)
    }

    private fun check(oldFeature: Feature, newFeature: Feature): List<OpenApiBackwardCompatibilityCheckRecord> {
        return OpenApiBackwardCompatibilityChecker(oldFeature, newFeature).run()
    }

    private fun feature(spec: String, fileName: String): Feature {
        return OpenApiSpecification.fromYAML(spec, fileName).toFeature()
    }

    private fun Feature.withResponseContentType(status: Int, value: String?): Feature {
        return withResponseContentType(status, previousValue = null, value = value)
    }

    private fun Feature.withResponseContentType(status: Int, previousValue: String?, value: String?): Feature = copy(
        scenarios = scenarios.map { scenario ->
            if (scenario.status != status || (previousValue != null && scenario.responseContentType != previousValue)) return@map scenario
            scenario.copy(
                httpResponsePattern = scenario.httpResponsePattern.copy(
                    headersPattern = scenario.httpResponsePattern.headersPattern.copy(contentType = value),
                ),
            )
        },
    )

    private fun Feature.withResponseBody(status: Int, contentType: String?, body: Pattern): Feature = copy(
        scenarios = scenarios.map { scenario ->
            if (scenario.status != status || scenario.responseContentType != contentType) return@map scenario
            scenario.copy(
                httpResponsePattern = scenario.httpResponsePattern.copy(body = body),
            )
        },
    )

    private fun spec(
        requestContentTypes: List<String>,
        responseContentType: String?,
        additionalResponseContentTypes: List<ResponseContentType> = emptyList(),
        responseSchema: String = "Problem",
        responseStatus: Int = 503,
    ): String {
        val template = checkNotNull(javaClass.getResource("/openapi/bcc_content_type_fallback.yaml")).readText()
        val requestContent = requestContentTypes.joinToString("\n") { contentType ->
            $$"""
            "$$contentType":
              schema:
                $ref: '#/components/schemas/Item'
            """.trimIndent().prependIndent("          ")
        }

        val responseContentTypes = buildList {
            responseContentType?.let { add(ResponseContentType(it, responseSchema)) }
            addAll(additionalResponseContentTypes)
        }

        val responseContent = responseContentTypes.takeIf { it.isNotEmpty() }?.let { contentTypes ->
            "          content:\n" + contentTypes.joinToString("\n") { contentType ->
                $$"""
                "$${contentType.mediaType}":
                  schema:
                    $ref: '#/components/schemas/$${contentType.schema}'
                """.trimIndent().prependIndent("            ")
            }
        }.orEmpty()

        return template
            .replace("          __REQUEST_CONTENT_TYPES__", requestContent)
            .replace("          __RESPONSE_CONTENT__", responseContent)
            .replace("__RESPONSE_STATUS__", responseStatus.toString())
    }

    private data class ResponseContentType(val mediaType: String, val schema: String = "Problem")
}
