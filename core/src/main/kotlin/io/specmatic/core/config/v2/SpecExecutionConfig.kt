package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import io.specmatic.core.Configuration.Companion.DEFAULT_BASE_URL
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.Source
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.SourceProvider
import io.specmatic.core.utilities.ResolvedWebSource
import io.specmatic.core.utilities.Flags
import java.io.File
import java.net.URI

sealed class SpecExecutionConfig {
    data class StringValue(@get:JsonValue val value: String) : SpecExecutionConfig() {
        override fun resolveAgainst(baseDirectory: File): SpecExecutionConfig {
            return StringValue(baseDirectory.resolve(value).canonicalPath)
        }

        override fun createSpecificationEntriesFrom(source: Source, baseDir: File, resiliencyTestSuite: ResiliencyTestSuite?): List<SpecificationSourceEntry> {
            val specFile = resolveSpecFile(source, baseDir, value)
            return listOf(SpecificationSourceEntry(source, specFile, value, null, null, resiliencyTestSuite))
        }

        override fun use(baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): SpecExecutionConfig {
            return ObjectValue.FullUrl(baseUrl = baseUrl, specs = specs(), resiliencyTests = resiliencyTestsConfig)
        }

        override fun use(port: Int): SpecExecutionConfig {
            return ObjectValue.PartialUrl(port = port, specs = specs())
        }
    }

    sealed class ObjectValue : SpecExecutionConfig() {
        abstract val specs: List<String>
        abstract val resiliencyTests: ResiliencyTestsConfig?

        fun toBaseUrl(defaultBaseUrl: String? = null): String {
            val resolvedBaseUrl = defaultBaseUrl
                ?: Flags.getStringValue(Flags.SPECMATIC_BASE_URL)
                ?: DEFAULT_BASE_URL
            val baseUrl = URI(resolvedBaseUrl)
            return toUrl(baseUrl).toString()
        }

        abstract fun toUrl(default: URI): URI

        data class FullUrl(
            val baseUrl: String,
            override val specs: List<String>,
            override val resiliencyTests: ResiliencyTestsConfig? = null,
        ) : ObjectValue() {
            override fun toUrl(default: URI) = URI(baseUrl)

            override fun resolveAgainst(baseDirectory: File): SpecExecutionConfig {
                return this.copy(specs = this.specs.map { baseDirectory.resolve(it).canonicalPath })
            }

            override fun createSpecificationEntriesFrom(source: Source, baseDir: File, resiliencyTestSuite: ResiliencyTestSuite?): List<SpecificationSourceEntry> {
                val baseUrl = toBaseUrl(null)
                val resiliency = resiliencyTests?.resolvedEnable ?: resiliencyTestSuite
                return specs().map { spec ->
                    val specFile = resolveSpecFile(source, baseDir, spec)
                    SpecificationSourceEntry(source, specFile, spec, baseUrl, null, resiliency)
                }
            }

            override fun use(baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): SpecExecutionConfig {
                return this.copy(baseUrl = baseUrl, resiliencyTests = resiliencyTestsConfig)
            }

            override fun use(port: Int): SpecExecutionConfig {
                return PartialUrl(port = port, specs = specs(), resiliencyTests = resiliencyTests)
            }
        }

        data class PartialUrl(
            val host: String? = null,
            val port: Int? = null,
            val basePath: String? = null,
            override val specs: List<String>,
            override val resiliencyTests: ResiliencyTestsConfig? = null,
        ) : ObjectValue() {
            override fun toUrl(default: URI): URI {
                return URI(
                    default.scheme,
                    default.userInfo,
                    host ?: default.host,
                    port ?: default.port,
                    basePath ?: default.path,
                    default.query,
                    default.fragment
                )
            }

            override fun resolveAgainst(baseDirectory: File): SpecExecutionConfig {
                return this.copy(specs = this.specs.map { baseDirectory.resolve(it).canonicalPath })
            }

            override fun createSpecificationEntriesFrom(source: Source, baseDir: File, resiliencyTestSuite: ResiliencyTestSuite?): List<SpecificationSourceEntry> {
                val baseUrl = toBaseUrl(null)
                val resiliency = resiliencyTests?.resolvedEnable ?: resiliencyTestSuite
                return specs().map { spec ->
                    val specFile = resolveSpecFile(source, baseDir, spec)
                    SpecificationSourceEntry(source, specFile, spec, baseUrl, port, resiliency)
                }
            }

            override fun use(baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): SpecExecutionConfig {
                return FullUrl(baseUrl = baseUrl, specs = specs(), resiliencyTests = resiliencyTestsConfig)
            }

            override fun use(port: Int): SpecExecutionConfig {
                return this.copy(port = port)
            }
        }
    }

    data class ConfigValue(
        val specs: List<String>,
        val specType: String,
        val config: Map<String, Any>
    ) : SpecExecutionConfig() {
        fun contains(specPath: String, specType: String): Boolean {
            return specPath in this.specs.toSet() && specType == this.specType
        }

        override fun resolveAgainst(baseDirectory: File): SpecExecutionConfig {
            return this.copy(specs = this.specs.map { baseDirectory.resolve(it).canonicalPath })
        }

        override fun createSpecificationEntriesFrom(source: Source, baseDir: File, resiliencyTestSuite: ResiliencyTestSuite?): List<SpecificationSourceEntry> {
            return specs().map { spec ->
                val specFile = resolveSpecFile(source, baseDir, spec)
                SpecificationSourceEntry(source, specFile, spec, null, null, resiliencyTestSuite)
            }
        }

        override fun use(baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): SpecExecutionConfig {
            // Can't convert to either of the types and the config is genric map
            return this
        }

        override fun use(port: Int): SpecExecutionConfig {
            // Can't convert to either of the types and the config is genric map
            return this
        }
    }

    @JsonIgnore
    fun contains(absoluteSpecPath: String): Boolean = when (this) {
        is StringValue -> absoluteSpecPath.contains(this.value)
        is ObjectValue -> this.specs.any { absoluteSpecPath.contains(it) }
        is ConfigValue -> this.specs.any { absoluteSpecPath.contains(it) }
    }

    @JsonIgnore
    fun specs(): List<String> {
        return when (this) {
            is StringValue -> listOf(this.value)
            is ObjectValue -> this.specs
            is ConfigValue -> this.specs
        }
    }

    @JsonIgnore
    fun specToBaseUrlPairList(defaultBaseUrl: String?, baseUrlFrom: (ConfigValue) -> String?): List<Pair<String, String?>> {
        return when (this) {
            is StringValue -> listOf(this.value to null)
            is ObjectValue -> this.specs.map { specPath ->
                specPath to this.toBaseUrl(defaultBaseUrl)
            }
            is ConfigValue -> this.specs.map { specPath ->
                specPath to baseUrlFrom(this)
            }
        }
    }

    abstract fun resolveAgainst(baseDirectory: File): SpecExecutionConfig

    abstract fun createSpecificationEntriesFrom(source: Source, baseDir: File, resiliencyTestSuite: ResiliencyTestSuite? = null): List<SpecificationSourceEntry>

    abstract fun use(baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): SpecExecutionConfig

    abstract fun use(port: Int): SpecExecutionConfig

    protected fun resolveSpecFile(source: Source, baseDir: File, specPath: String): File {
        return if (source.provider == SourceProvider.web) {
            val webBaseUrl = source.webBaseUrl ?: error("Web source url should have been validated before use")
            ResolvedWebSource.localPathFor(baseDir.resolve(".specmatic/web"), webBaseUrl, specPath)
        } else {
            baseDir.resolve(specPath).canonicalFile
        }
    }
}

class ConsumesDeserializer(private val consumes: Boolean = true) : JsonDeserializer<List<SpecExecutionConfig>>() {
    private val keyBeingDeserialized = if(consumes) "consumes" else "provides"

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<SpecExecutionConfig> {
        return p.codec.readTree<JsonNode>(p).takeIf(JsonNode::isArray)?.map { element ->
            when {
                element.isTextual -> SpecExecutionConfig.StringValue(element.asText())
                element.isObject -> element.parseObjectValue(p)
                else -> throw JsonMappingException(p, "Consumes entry must be string or object")
            }
        } ?: throw JsonMappingException(p, "Consumes should be an array")
    }

    private fun JsonNode.parseObjectValue(p: JsonParser): SpecExecutionConfig {
        if (has("specType") || has("config")) {
            return p.codec.treeToValue(this, SpecExecutionConfig.ConfigValue::class.java)
        }

        val validatedJsonNode = this.getValidatedJsonNode(p)
        return when {
            has("baseUrl") -> SpecExecutionConfig.ObjectValue.FullUrl(
                baseUrl = validatedJsonNode.get("baseUrl").asText(),
                specs = validatedJsonNode.get("specs").map(JsonNode::asText),
                resiliencyTests = if (consumes) null else p.codec.treeToValue(get("resiliencyTests"), ResiliencyTestsConfig::class.java),
            )

            else -> SpecExecutionConfig.ObjectValue.PartialUrl(
                host = validatedJsonNode.get("host")?.asText(),
                port = validatedJsonNode.get("port")?.asInt(),
                basePath = validatedJsonNode.get("basePath")?.asText(),
                specs = validatedJsonNode.get("specs").map(JsonNode::asText),
                resiliencyTests = if (consumes) null else p.codec.treeToValue(get("resiliencyTests"), ResiliencyTestsConfig::class.java),
            )
        }
    }

    private fun JsonNode.validateConfigValueFields(p: JsonParser) {
        validateSpecsFieldAndReturn(p)
        val specTypeField = get("specType")
        if (specTypeField == null || !specTypeField.isTextual) {
            throw JsonMappingException(p, "Missing or invalid required field 'specType' key in '$keyBeingDeserialized' field in Specmatic configuration")
        }
        val configField = get("config")
        if (configField == null || !configField.isObject) {
            throw JsonMappingException(p, "Missing or invalid required field 'config' key in '$keyBeingDeserialized' field in Specmatic configuration")
        }
    }

    private fun JsonNode.validateSpecsFieldAndReturn(p: JsonParser): JsonNode {
        val specsField = get("specs")
        when {
            specsField == null -> throw JsonMappingException(p, "Missing required field 'specs' in '$keyBeingDeserialized' field in Specmatic configuration")
            !specsField.isArray -> throw JsonMappingException(p, "'specs' must be an array in '$keyBeingDeserialized' field in Specmatic configuration")
            specsField.isEmpty -> throw JsonMappingException(p, "'specs' array cannot be empty in '$keyBeingDeserialized' field in Specmatic configuration")
            specsField.any { !it.isTextual } -> throw JsonMappingException(p, "'specs' must contain only strings in '$keyBeingDeserialized' field in Specmatic configuration")
        }
        return specsField
    }


    private fun JsonNode.getValidatedJsonNode(p: JsonParser): JsonNode {
        val allowedFields = buildSet {
            addAll(listOf("baseUrl", "host", "port", "basePath", "specs"))
            if (!consumes) add("resiliencyTests")
        }
        val unknownFields = fieldNames().asSequence().filterNot(allowedFields::contains).toSet()
        if (unknownFields.isNotEmpty()) {
            throw JsonMappingException(p,
                "Unknown fields: ${unknownFields.joinToString(", ")}\nAllowed fields: ${allowedFields.joinToString(", ")}"
            )
        }

        if (!consumes && has("basePath")) {
            throw JsonMappingException(p, "Field 'basePath' is not supported in provides")
        }

        validateSpecsFieldAndReturn(p)

        val hasBaseUrl = has("baseUrl")

        if (consumes) {
            val partialFields = listOf("host", "port", "basePath").filter(::has)
            val hasPartialFields = partialFields.isNotEmpty()
            when {
                hasBaseUrl && hasPartialFields -> throw JsonMappingException(p, "Cannot combine baseUrl with ${partialFields.joinToString(", ")}")
                !hasBaseUrl && !hasPartialFields -> throw JsonMappingException(p, "Must provide baseUrl or one or combination of host, port, and basePath")
            }
        } else {
            val hasHostOrPort = has("host") || has("port")
            when {
                hasBaseUrl && hasHostOrPort -> throw JsonMappingException(p, "Cannot combine baseUrl with host or port")
                !hasBaseUrl && !hasHostOrPort -> throw JsonMappingException(p, "Must provide baseUrl or one or combination of host and port")
            }
        }

        return this
    }

}
