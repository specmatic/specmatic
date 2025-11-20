package io.specmatic.conversions

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.StringValue
import io.swagger.v3.oas.models.media.Schema

private typealias PropertyName = String
private typealias SchemaName = String
private typealias DiscriminatorData = Map<PropertyName, Map<SchemaName, Pair<SchemaName, List<Schema<*>>>>>

data class DiscriminatorDetails(private val discriminatorData: DiscriminatorData = emptyMap()) {
    fun isNotEmpty(): Boolean {
        return discriminatorData.isNotEmpty()
    }

    val schemaNames: List<String>
        get() {
            return discriminatorData.entries.firstOrNull()?.value?.values?.map { it.first }.orEmpty()
        }

    val values: List<String>
        get() {
            return discriminatorData.entries.firstOrNull()?.value?.keys?.toList() ?: emptyList()
        }

    val key: String?
        get() {
            return discriminatorData.entries.firstOrNull()?.key
        }

    val schemas: List<Schema<*>>
        get() {
            return discriminatorData.entries.flatMap {
                it.value.values.flatMap {
                    it.second
                }
            }
        }

    fun plus(newDiscriminatorDetailsDetails: Triple<PropertyName, Map<SchemaName, Pair<SchemaName, List<Schema<*>>>>, DiscriminatorDetails>?): DiscriminatorDetails {
        if(newDiscriminatorDetailsDetails == null)
            return this

        val (propertyName, valuesAndSchemas: Map<SchemaName, Pair<SchemaName, List<Schema<*>>>>, newDiscriminator) = newDiscriminatorDetailsDetails

        val updatedDiscriminatorData: DiscriminatorData =
            discriminatorData.plus(propertyName to valuesAndSchemas)

        return this.copy(discriminatorData = updatedDiscriminatorData).plus(newDiscriminator)
    }

    fun plus(newDiscriminatorDetails: DiscriminatorDetails): DiscriminatorDetails {
        return this.copy(discriminatorData = mergeMapOfMaps(discriminatorData, newDiscriminatorDetails.discriminatorData))
    }

    private fun <T> mergeMapOfMaps(
        discriminatorDetails1: Map<String, Map<String, T>>,
        discriminatorDetails2: Map<String, Map<String, T>>
    ): Map<String, Map<String, T>> {
        val keys = discriminatorDetails1.keys + discriminatorDetails2.keys

        return keys.associateWith { key ->
            val detail1 = discriminatorDetails1[key] ?: emptyMap()
            val detail2 = discriminatorDetails2[key] ?: emptyMap()

            (detail1 + detail2)
        }
    }

    fun hasValueForKey(propertyName: String?): Boolean {
        if(propertyName == null)
            return false

        return propertyName in discriminatorData
    }

    fun valueFor(propertyName: String): Pattern {
        if(propertyName !in discriminatorData)
            throw ContractException("$propertyName not found in discriminator details")

        return discriminatorData.getValue(propertyName).firstNotNullOf { ExactValuePattern(StringValue(it.key), discriminator = true) }
    }

    fun explode(): List<DiscriminatorDetails> {
        return explode(discriminatorData)
    }

    private fun explode(discriminatorData: DiscriminatorData): List<DiscriminatorDetails> {
        val propertyName = discriminatorData.keys.firstOrNull() ?: return listOf(DiscriminatorDetails())

        val discriminatorDetailsWithOneKeyLess = discriminatorData - propertyName

        val valueOptionsWithSchemasForProperty = discriminatorData.getValue(propertyName)

        return valueOptionsWithSchemasForProperty.flatMap { valueOption: Map.Entry<String, Pair<String, List<Schema<*>>>> ->
            explode(discriminatorDetailsWithOneKeyLess).map { discriminator ->
                discriminator.plus(Triple(propertyName, mapOf(valueOption.toPair()), DiscriminatorDetails()))
            }
        }
    }
}
