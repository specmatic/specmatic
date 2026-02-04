package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.v3.components.sources.SourceV3
import java.io.File

@JsonIgnoreProperties("specificationPath", "urlPathPrefix", "specificationId")
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = SpecificationDefinition.StringValue::class)
sealed interface SpecificationDefinition {
    data class Specification(val id: String? = null, val path: String, val urlPathPrefix: String? = null)

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    data class ObjectValue(val spec: Specification): SpecificationDefinition

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    data class StringValue(val specification: String) : SpecificationDefinition {
        @Suppress("unused")
        @JsonValue
        fun asValue() = specification

        companion object {
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun from(value: String): SpecificationDefinition = StringValue(value)
        }
    }

    fun getSpecificationPath(): String = when(this) {
        is StringValue -> this.specification
        is ObjectValue -> this.spec.path
    }

    fun getUrlPathPrefix(): String? = (this as? ObjectValue)?.spec?.urlPathPrefix

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
}
