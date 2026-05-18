package io.specmatic.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.JsonPatch
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScenarioFingerprintTest {

    private val baseSpec = """
        openapi: 3.0.0
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
                      type: object
                      required: [id]
                      properties:
                        id:
                          type: string
              responses:
                '201':
                  description: created
    """.trimIndent()

    private val componentRefSpec = """
        openapi: 3.0.0
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
                      ${'$'}ref: '#/components/schemas/Order'
              responses:
                '201':
                  description: created
        components:
          schemas:
            Order:
              type: object
              required: [id]
              properties:
                id:
                  type: string
    """.trimIndent()

    @Test
    fun `from copies identifying fields and patterns off the scenario`() {
        val scenario = singleScenarioFrom(baseSpec)

        val fingerprint = ScenarioFingerprint.from(scenario)

        assertThat(fingerprint.status).isEqualTo(scenario.status)
        assertThat(fingerprint.requestContentType).isEqualTo(scenario.requestContentType)
        assertThat(fingerprint.responseContentType).isEqualTo(scenario.responseContentType)
        assertThat(fingerprint.httpRequestPattern).isEqualTo(scenario.httpRequestPattern)
        assertThat(fingerprint.httpResponsePattern).isEqualTo(scenario.httpResponsePattern)
    }

    @Test
    fun `fingerprints of structurally identical scenarios are equal`() {
        val first = singleScenarioFrom(baseSpec)
        val second = singleScenarioFrom(baseSpec)

        assertThat(ScenarioFingerprint.from(first)).isEqualTo(ScenarioFingerprint.from(second))
    }

    @Test
    fun `fingerprints differ when request body shape differs`() {
        val original = singleScenarioFrom(baseSpec)
        val withExtraField = singleScenarioFrom(baseSpec.applyJsonPatch("""
            - op: add
              path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
              value:
                type: string
        """.trimIndent()))

        assertThat(ScenarioFingerprint.from(original))
            .isNotEqualTo(ScenarioFingerprint.from(withExtraField))
    }

    @Test
    fun `fingerprints differ when response status differs`() {
        val original = singleScenarioFrom(baseSpec)
        val differentStatus = singleScenarioFrom(baseSpec.applyJsonPatch("""
            - op: move
              from: /paths/~1orders/post/responses/201
              path: /paths/~1orders/post/responses/202
        """.trimIndent()))

        assertThat(ScenarioFingerprint.from(original))
            .isNotEqualTo(ScenarioFingerprint.from(differentStatus))
    }

    @Test
    fun `fingerprints differ when request content type differs`() {
        val original = singleScenarioFrom(baseSpec)
        val differentContentType = singleScenarioFrom(baseSpec.applyJsonPatch("""
            - op: move
              from: /paths/~1orders/post/requestBody/content/application~1json
              path: /paths/~1orders/post/requestBody/content/application~1vnd.orders+json
        """.trimIndent()))

        assertThat(ScenarioFingerprint.from(original))
            .isNotEqualTo(ScenarioFingerprint.from(differentContentType))
    }

    @Test
    fun `changeStatusBetween returns UNCHANGED for identical scenario sets`() {
        val old = scenariosFrom(baseSpec)
        val new = scenariosFrom(baseSpec)

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.UNCHANGED)
    }

    @Test
    fun `changeStatusBetween returns CHANGED when a structural diff exists`() {
        val old = scenariosFrom(baseSpec)
        val new = scenariosFrom(baseSpec.applyJsonPatch("""
            - op: add
              path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
              value:
                type: string
        """.trimIndent()))

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.CHANGED)
    }

    @Test
    fun `changeStatusBetween returns CHANGED when a referenced component schema changes`() {
        val old = scenariosFrom(componentRefSpec)
        val new = scenariosFrom(componentRefSpec.applyJsonPatch("""
            - op: replace
              path: /components/schemas/Order/properties/id/type
              value: integer
        """.trimIndent()))

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.CHANGED)
    }

    @Test
    fun `changeStatusBetween returns UNCHANGED when an unused component schema changes`() {
        val specWithUnusedComponent = componentRefSpec.applyJsonPatch("""
            - op: add
              path: /components/schemas/Unused
              value:
                type: object
                properties:
                  name:
                    type: string
        """.trimIndent())
        val old = scenariosFrom(specWithUnusedComponent)
        val new = scenariosFrom(specWithUnusedComponent.applyJsonPatch("""
            - op: replace
              path: /components/schemas/Unused/properties/name/type
              value: integer
        """.trimIndent()))

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.UNCHANGED)
    }

    @Test
    fun `changeStatusBetween returns CHANGED when a scenario is added`() {
        val old = scenariosFrom(baseSpec)
        val new = scenariosFrom(baseSpec.applyJsonPatch("""
            - op: add
              path: /paths/~1orders/post/responses/400
              value:
                description: bad request
        """.trimIndent()))

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.CHANGED)
    }

    @Test
    fun `changeStatusBetween returns CHANGED when a scenario is removed`() {
        val old = scenariosFrom(baseSpec.applyJsonPatch("""
            - op: add
              path: /paths/~1orders/post/responses/400
              value:
                description: bad request
        """.trimIndent()))
        val new = scenariosFrom(baseSpec)

        assertThat(ScenarioFingerprint.changeStatusBetween(old, new)).isEqualTo(ChangeStatus.CHANGED)
    }

    @Test
    fun `changeStatusBetween returns UNCHANGED when both sides are empty`() {
        assertThat(ScenarioFingerprint.changeStatusBetween(emptyList(), emptyList()))
            .isEqualTo(ChangeStatus.UNCHANGED)
    }

    @Test
    fun `changeStatusBetween returns CHANGED when an operation appears only on one side`() {
        val onlyOnNew = scenariosFrom(baseSpec)

        assertThat(ScenarioFingerprint.changeStatusBetween(emptyList(), onlyOnNew))
            .isEqualTo(ChangeStatus.CHANGED)
        assertThat(ScenarioFingerprint.changeStatusBetween(onlyOnNew, emptyList()))
            .isEqualTo(ChangeStatus.CHANGED)
    }

    private fun scenariosFrom(yaml: String): List<Scenario> =
        OpenApiSpecification.fromYAML(yaml, "test.yaml").toFeature().scenariosForChangeTracking()

    private fun singleScenarioFrom(yaml: String): Scenario = scenariosFrom(yaml).single()

    private fun String.applyJsonPatch(patch: String): String {
        val specNode = yamlMapper.readTree(this)
        val patchNode = yamlMapper.readTree(patch.trimIndent())
        return yamlMapper.writeValueAsString(JsonPatch.apply(patchNode, specNode)).trim()
    }

    private val yamlMapper = ObjectMapper(YAMLFactory())
}
