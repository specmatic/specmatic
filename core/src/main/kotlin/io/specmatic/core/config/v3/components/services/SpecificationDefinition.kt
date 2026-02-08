package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.config.v3.components.sources.SourceV3
import java.io.File

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = SpecificationDefinition.StringValue::class)
sealed interface SpecificationDefinition {
    data class Specification(val id: String? = null, val path: String, val urlPathPrefix: String? = null) {
        private val _config: MutableMap<String, Any?> = linkedMapOf()

        @get:JsonAnyGetter
        val config: Map<String, Any?> get() = _config

        @JsonAnySetter
        fun put(key: String, value: Any?) {
            _config[key] = value
        }
    }

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    data class ObjectValue(val spec: Specification): SpecificationDefinition {
        override fun toSpecificationSource(
            source: SourceV3,
            resiliencyTestSuite: ResiliencyTestSuite?,
            examples: List<String>,
            getBaseUrl: (File) -> String?
        ): SpecificationSourceEntry {
            val specFile = source.resolveSpecification(File(spec.path))
            return source.toSpecificationSource(specFile, spec.path, getBaseUrl(specFile), resiliencyTestSuite, examples)
        }

        companion object {
            fun from(specFile: File, id: String): ObjectValue {
                val spec = Specification(id, specFile.canonicalPath)
                return ObjectValue(spec)
            }
        }
    }

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    data class StringValue(val specification: String) : SpecificationDefinition {
        @Suppress("unused")
        @JsonValue
        fun asValue() = specification

        override fun toSpecificationSource(
            source: SourceV3,
            resiliencyTestSuite: ResiliencyTestSuite?,
            examples: List<String>,
            getBaseUrl: (File) -> String?
        ): SpecificationSourceEntry {
            val specFile = source.resolveSpecification(File(specification))
            val baseUrl = getBaseUrl(specFile)?.let(::getUrlPathPrefix)
            return source.toSpecificationSource(specFile, specification, baseUrl, resiliencyTestSuite, examples)
        }

        companion object {
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun from(value: String): SpecificationDefinition = StringValue(value)
        }
    }

    @JsonIgnore
    fun getSpecificationPath(): String = when(this) {
        is StringValue -> this.specification
        is ObjectValue -> this.spec.path
    }

    @JsonIgnore
    fun getUrlPathPrefix(baseUrl: String): String {
        val prefix = (this as? ObjectValue)?.spec?.urlPathPrefix?.trim('/')
        return if (prefix.isNullOrEmpty()) {
            baseUrl
        } else {
            "${baseUrl.removeSuffix("/")}/$prefix"
        }
    }

    @JsonIgnore
    fun getSpecificationId(): String? = (this as? ObjectValue)?.spec?.id

    fun matchesFile(source: SourceV3, file: File): Boolean {
        val resolvedSpecFile = source.resolveSpecification(File(getSpecificationPath()))
        val resolvedSpecPath = resolvedSpecFile.toPath().normalize()
        if (file.isAbsolute && resolvedSpecFile.isAbsolute) return resolvedSpecFile.sameAs(file)
        if (!file.isAbsolute) return file.toPath().normalize().endsWith(resolvedSpecPath)
        return false
    }

    private fun File.sameAs(other: File): Boolean {
        return try {
            this.toPath().toRealPath() == other.toPath().toRealPath()
        } catch (_: Exception) {
            this.canonicalFile == other.canonicalFile
        }
    }

    fun toSpecificationSource(
        source: SourceV3,
        resiliencyTestSuite: ResiliencyTestSuite?,
        examples: List<String>,
        getBaseUrl: (File) -> String?
    ): SpecificationSourceEntry
}
