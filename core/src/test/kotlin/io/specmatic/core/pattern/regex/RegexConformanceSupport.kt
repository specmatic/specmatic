package io.specmatic.core.pattern.regex

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

internal data class RegexConformanceCandidate(val value: String, val matches: Boolean)
internal data class RegexConformanceFixtureSet(val schemaVersion: Int, val cases: List<RegexConformanceFixture>)
internal data class RegexConformanceFixture(
    val id: String,
    val source: String,
    val dialect: String,
    val pattern: String,
    val category: String,
    val generation: String,
    val syntaxValid: Boolean,
    val reason: String? = null,
    val deferredReason: String? = null,
    val candidates: List<RegexConformanceCandidate>,
) {
    override fun toString(): String = id
}

internal object RegexConformanceFixtures {
    private val mapper = ObjectMapper().registerKotlinModule()
    fun read(): RegexConformanceFixtureSet {
        val resource = requireNotNull(javaClass.getResource("/regex-conformance/cases.json"))
        return mapper.readValue(resource)
    }
}
