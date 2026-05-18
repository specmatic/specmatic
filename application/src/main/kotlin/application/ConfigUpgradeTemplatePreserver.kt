package application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.config.ConfigTemplateUtils

internal class ConfigUpgradeTemplatePreserver(private val objectMapper: ObjectMapper) {
    fun preserveTemplates(originalConfigYaml: String, upgradedConfigYaml: String): String {
        val originalTree = objectMapper.readTree(originalConfigYaml)
        val upgradedTree = objectMapper.readTree(upgradedConfigYaml)
        val templates = TemplateCollector().collect(originalTree)
        if (templates.isEmpty()) return upgradedConfigYaml

        TemplateApplier(templates).applyTo(upgradedTree)
        return objectMapper.writeValueAsString(upgradedTree)
    }

    private class TemplateCollector {
        fun collect(node: JsonNode): TemplateSet {
            val fieldTemplates = mutableListOf<FieldTemplate>()
            val valueTemplates = mutableListOf<ValueTemplate>()
            collectFrom(node, emptyList(), fieldTemplates, valueTemplates)
            return TemplateSet(
                fieldTemplates = fieldTemplates.toUnambiguousTemplates().sortedBy { it.targetContainers == null },
                valueTemplates = valueTemplates.toUniqueResolvedValueTemplates(),
            )
        }

        private fun collectFrom(
            node: JsonNode,
            path: List<String>,
            fieldTemplates: MutableList<FieldTemplate>,
            valueTemplates: MutableList<ValueTemplate>,
        ) {
            when {
                node.isObject -> node.properties().asSequence().forEach { entry ->
                    collectFrom(entry.value, path + entry.key, fieldTemplates, valueTemplates)
                }

                node.isArray -> node.elements().asSequence().forEachIndexed { index, element ->
                    collectFrom(element, path + index.toString(), fieldTemplates, valueTemplates)
                }

                node.isTextual && ConfigTemplateUtils.isConfigTemplate(node.asText()) -> {
                    val rawText = node.asText()
                    val resolvedText = ConfigTemplateUtils.resolveTemplateValue(node).scalarValueText() ?: return
                    val sourceFieldName = path.sourceFieldName()
                    if (sourceFieldName != null) {
                        fieldTemplates.add(fieldTemplateFor(path, sourceFieldName, rawText, resolvedText))
                    } else {
                        valueTemplates.add(ValueTemplate(rawText, resolvedText))
                    }
                }
            }
        }

        private fun fieldTemplateFor(
            path: List<String>,
            sourceFieldName: String,
            rawText: String,
            resolvedText: String,
        ): FieldTemplate {
            return FieldTemplate(
                rawText = targetRawTextFor(path, sourceFieldName, rawText),
                resolvedText = targetResolvedTextFor(path, sourceFieldName, resolvedText),
                targetFieldNames = targetFieldNamesFor(path, sourceFieldName),
                targetContainers = targetContainersFor(path, sourceFieldName),
                targetParentPathSuffix = targetParentPathSuffixFor(path),
            )
        }

        private fun targetRawTextFor(path: List<String>, sourceFieldName: String, rawText: String): String {
            return when {
                path.firstOrNull() == "stub" && sourceFieldName == "hotReload" ->
                    rewriteSwitchTemplateDefault(rawText)
                else -> rawText
            }
        }

        private fun targetFieldNamesFor(path: List<String>, sourceFieldName: String): Set<String> {
            if (path.endsWith("resiliencyTests", "enable")) return setOf("schemaResiliencyTests")

            return when (sourceFieldName) {
                "swaggerUIBaseURL" -> setOf("swaggerUiBaseUrl")
                "disable_telemetry" -> setOf("disableTelemetry")
                "fuzzy" -> setOf("fuzzyMatcherForPayloads")
                "license_path", "licensePath" -> setOf("path")
                "report_dir_path", "reportDirPath" -> setOf("junitReportDir")
                "dictionary" -> setOf("path")
                "targetUrl" -> setOf("target")
                "outputDirectory" -> setOf("recordingsDirectory", "outputDirectory")
                "minThresholdPercentage" -> setOf("minCoveragePercentage")
                "maxMissedEndpointsInSpec" -> setOf("maxMissedOperationsInSpec")
                "basePath" -> setOf("urlPathPrefix")
                "pre_specmatic_request_processor" -> setOf("preSpecmaticRequestProcessor")
                "post_specmatic_response_processor" -> setOf("postSpecmaticResponseProcessor")
                "pre_specmatic_response_processor" -> setOf("preSpecmaticResponseProcessor")
                "bearer-file" -> setOf("bearerFile")
                "bearer-environment-variable" -> setOf("bearerEnvironmentVariable")
                "personal-access-token" -> setOf("personalAccessToken")
                "value" -> if (path.firstOrNull() == "security") setOf("token") else setOf(sourceFieldName)
                else -> setOf(sourceFieldName)
            }
        }

        private fun targetContainersFor(path: List<String>, sourceFieldName: String): Set<TargetContainer>? {
            val root = path.firstOrNull()
            return when (root) {
                "test" if sourceFieldName in testSettingsFields ->
                    setOf(TargetContainer.TEST_SETTINGS)

                "test" if path.endsWith("resiliencyTests", "enable") ->
                    setOf(TargetContainer.TEST_SETTINGS)

                "contracts" if "provides" in path && path.endsWith("resiliencyTests", "enable") ->
                    setOf(TargetContainer.TEST_SETTINGS)

                "test" ->
                    setOf(TargetContainer.TEST_RUN_OPTIONS)

                "stub" if sourceFieldName in mockSettingsFields ->
                    setOf(TargetContainer.MOCK_SETTINGS)

                "stub" if sourceFieldName == "dictionary" ->
                    setOf(TargetContainer.DATA_DICTIONARY)

                "stub" ->
                    setOf(TargetContainer.MOCK_RUN_OPTIONS)

                "proxy" ->
                    setOf(TargetContainer.PROXY)

                "mcp" ->
                    setOf(TargetContainer.MCP)

                "report" ->
                    setOf(TargetContainer.GOVERNANCE)

                "license_path", "licensePath" ->
                    setOf(TargetContainer.LICENSE)

                "report_dir_path", "reportDirPath" ->
                    setOf(TargetContainer.TEST_SETTINGS)

                "hooks" ->
                    setOf(TargetContainer.ADAPTERS)

                "security" ->
                    setOf(TargetContainer.SECURITY)

                "workflow" ->
                    setOf(TargetContainer.TEST_RUN_OPTIONS)

                "auth" ->
                    setOf(TargetContainer.AUTH)

                "backwardCompatibility" ->
                    setOf(TargetContainer.BACKWARD_COMPATIBILITY)

                "logging" ->
                    setOf(TargetContainer.GENERAL_SETTINGS)

                "schemaExampleDefault", "fuzzy", "escapeSoapAction" ->
                    setOf(TargetContainer.GENERAL_FEATURE_FLAGS)

                in generalSettingsFields ->
                    setOf(TargetContainer.GENERAL_SETTINGS)

                "globalSettings" ->
                    setOf(TargetContainer.GENERAL_SETTINGS)

                "contracts" if "provides" in path ->
                    setOf(TargetContainer.TEST_RUN_OPTIONS, TargetContainer.RUN_OPTIONS)

                "contracts" if "consumes" in path ->
                    setOf(TargetContainer.MOCK_RUN_OPTIONS, TargetContainer.RUN_OPTIONS)

                "default_pattern_values" ->
                    setOf(TargetContainer.DATA_DICTIONARY)

                else -> null
            }
        }

        private fun targetResolvedTextFor(path: List<String>, sourceFieldName: String, resolvedText: String): String {
            return when {
                path.firstOrNull() == "stub" && sourceFieldName == "hotReload" ->
                    when (resolvedText) {
                        "enabled" -> "true"
                        "disabled" -> "false"
                        else -> resolvedText
                    }
                else -> resolvedText
            }
        }

        private fun rewriteSwitchTemplateDefault(rawText: String): String {
            return ConfigTemplateUtils.findVariableTokens(rawText).asReversed().fold(rawText) { text, token ->
                val booleanDefault = when (token.default) {
                    "enabled" -> "true"
                    "disabled" -> "false"
                    else -> token.default
                }

                if (booleanDefault == token.default) return@fold text
                text.replaceRange(
                    startIndex = token.startIndex,
                    endIndex = token.endIndex + 1,
                    replacement = ConfigTemplateUtils.createTemplate(token.names, booleanDefault),
                )
            }
        }

        private fun targetParentPathSuffixFor(path: List<String>): List<String>? {
            if (path.firstOrNull() != "workflow") return null
            return path.dropLast(1)
        }

        private fun List<ValueTemplate>.toUniqueResolvedValueTemplates(): List<ValueTemplate> {
            return groupBy(ValueTemplate::resolvedText)
                .filterValues { templates -> templates.map(ValueTemplate::rawText).distinct().size == 1 }
                .values
                .map { templates -> templates.first() }
        }

        private fun List<FieldTemplate>.toUnambiguousTemplates(): List<FieldTemplate> {
            return groupBy { template ->
                FieldTemplateIdentity(
                    targetFieldNames = template.targetFieldNames,
                    targetContainers = template.targetContainers,
                    targetParentPathSuffix = template.targetParentPathSuffix,
                    resolvedText = template.resolvedText,
                )
            }.filterValues { templates ->
                templates.map(FieldTemplate::rawText).distinct().size == 1
            }.values.map { templates ->
                templates.first()
            }
        }
    }

    private class TemplateApplier(private val templates: TemplateSet) {
        fun applyTo(node: JsonNode) {
            applyTo(node, emptyList())
        }

        private fun applyTo(node: JsonNode, path: List<String>) {
            when (node) {
                is ObjectNode -> applyToObject(node, path)
                is ArrayNode -> applyToArray(node, path)
            }
        }

        private fun applyToObject(objectNode: ObjectNode, path: List<String>) {
            objectNode.properties().toList().forEach { entry ->
                val childPath = path + entry.key
                val rawFieldReplacement = templates.fieldTemplates.asSequence()
                    .filter { template -> template.matches(entry.key, entry.value, path) }
                    .map(FieldTemplate::rawText)
                    .distinct()
                    .singleOrNull()

                when {
                    rawFieldReplacement != null ->
                        objectNode.put(entry.key, rawFieldReplacement)

                    entry.key in arrayValueTargetFieldNames && entry.value.isScalarValue() ->
                        templates.valueTemplates.firstOrNull { template ->
                            template.resolvedText == entry.value.scalarValueText()
                        }?.let { template -> objectNode.put(entry.key, template.rawText) }

                    else ->
                        applyTo(entry.value, childPath)
                }
            }
        }

        private fun applyToArray(arrayNode: ArrayNode, path: List<String>) {
            arrayNode.elements().asSequence().toList().forEachIndexed { index, element ->
                val replacement = templates.valueTemplates.firstOrNull { template ->
                    template.resolvedText == element.scalarValueText()
                }

                when {
                    replacement != null ->
                        arrayNode.set(index, arrayNode.textNode(replacement.rawText))
                    else ->
                        applyTo(element, path + index.toString())
                }
            }
        }
    }

    private data class TemplateSet(
        val fieldTemplates: List<FieldTemplate>,
        val valueTemplates: List<ValueTemplate>,
    ) {
        fun isEmpty(): Boolean = fieldTemplates.isEmpty() && valueTemplates.isEmpty()
    }

    private data class FieldTemplate(
        val rawText: String,
        val resolvedText: String,
        val targetFieldNames: Set<String>,
        val targetContainers: Set<TargetContainer>?,
        val targetParentPathSuffix: List<String>?,
    ) {
        fun matches(fieldName: String, value: JsonNode, parentPath: List<String>): Boolean {
            val targetContainer = targetContainerFor(parentPath)
            val containerMatches = targetContainers?.let { targetContainer in it } ?: (targetContainer == null)
            val pathMatches = targetParentPathSuffix?.let { parentPath.endsWith(it) } ?: true
            return fieldName in targetFieldNames && containerMatches && pathMatches && value.scalarValueText() == resolvedText
        }
    }

    private data class ValueTemplate(val rawText: String, val resolvedText: String)

    private data class FieldTemplateIdentity(
        val targetFieldNames: Set<String>,
        val targetContainers: Set<TargetContainer>?,
        val targetParentPathSuffix: List<String>?,
        val resolvedText: String,
    )

    private enum class TargetContainer {
        TEST_SETTINGS,
        MOCK_SETTINGS,
        GENERAL_SETTINGS,
        GENERAL_FEATURE_FLAGS,
        TEST_RUN_OPTIONS,
        MOCK_RUN_OPTIONS,
        DATA_DICTIONARY,
        ADAPTERS,
        PROXY,
        MCP,
        LICENSE,
        GOVERNANCE,
        GOVERNANCE_REPORT,
        SECURITY,
        AUTH,
        BACKWARD_COMPATIBILITY,
        RUN_OPTIONS,
    }

    private companion object {
        val testSettingsFields = setOf(
            "validateResponseValues",
            "timeoutInMilliseconds",
            "strictMode",
            "lenientMode",
            "parallelism",
            "maxTestRequestCombinations",
            "maxTestCount",
            "junitReportDir",
        )

        val mockSettingsFields = setOf(
            "generative",
            "delayInMilliseconds",
            "strictMode",
            "lenientMode",
            "hotReload",
            "startTimeoutInMilliseconds",
            "gracefulRestartTimeoutInMilliseconds",
        )

        val generalSettingsFields = setOf(
            "ignoreInlineExamples",
            "ignoreInlineExampleWarnings",
            "prettyPrint",
            "disable_telemetry",
            "disableTelemetry",
        )

        val arrayValueTargetFieldNames = setOf("path")

        fun targetContainerFor(path: List<String>): TargetContainer? {
            return when {
                path.lastOrNull() == "test" && "settings" in path ->
                    TargetContainer.TEST_SETTINGS
                (path.lastOrNull() == "mock" && "settings" in path) || path.endsWith("dependencies", "settings") ->
                    TargetContainer.MOCK_SETTINGS
                path.lastOrNull() == "general" && "settings" in path ->
                    TargetContainer.GENERAL_SETTINGS
                path.any { it == "logging" } ->
                    TargetContainer.GENERAL_SETTINGS
                path.endsWith("featureFlags") ->
                    TargetContainer.GENERAL_FEATURE_FLAGS
                path.endsWith("dictionary") ->
                    TargetContainer.DATA_DICTIONARY
                path.lastOrNull() == "settings" && "systemUnderTest" in path ->
                    TargetContainer.TEST_SETTINGS
                path.any { it == "adapters" } ->
                    TargetContainer.ADAPTERS
                path.any { it == "proxies" } ->
                    TargetContainer.PROXY
                path.firstOrNull() == "mcp" ->
                    TargetContainer.MCP
                path.endsWith("specmatic", "license") ->
                    TargetContainer.LICENSE
                path.endsWith("governance", "report") ->
                    TargetContainer.GOVERNANCE_REPORT
                path.any { it == "governance" } ->
                    TargetContainer.GOVERNANCE
                path.any { it == "securitySchemes" } ->
                    TargetContainer.SECURITY
                path.endsWith("auth") ->
                    TargetContainer.AUTH
                path.any { it == "backwardCompatibility" } ->
                    TargetContainer.BACKWARD_COMPATIBILITY
                "systemUnderTest" in path && "definitions" in path && "specs" in path ->
                    TargetContainer.TEST_RUN_OPTIONS
                "dependencies" in path && "definitions" in path && "specs" in path ->
                    TargetContainer.MOCK_RUN_OPTIONS
                "systemUnderTest" in path && "runOptions" in path ->
                    TargetContainer.TEST_RUN_OPTIONS
                "dependencies" in path && "runOptions" in path ->
                    TargetContainer.MOCK_RUN_OPTIONS
                path.firstOrNull() == "components" && "runOptions" in path ->
                    TargetContainer.RUN_OPTIONS
                else -> null
            }
        }

        fun List<String>.endsWith(vararg values: String): Boolean {
            if (size < values.size) return false
            return takeLast(values.size) == values.toList()
        }

        fun List<String>.endsWith(values: List<String>): Boolean {
            if (size < values.size) return false
            return takeLast(values.size) == values
        }

        fun List<String>.sourceFieldName(): String? {
            return lastOrNull()?.takeUnless { it.toIntOrNull() != null }
        }

        fun JsonNode.isScalarValue(): Boolean {
            return isTextual || isNumber || isBoolean
        }

        fun JsonNode.scalarValueText(): String? {
            return when {
                isScalarValue() -> asText()
                else -> null
            }
        }
    }
}
