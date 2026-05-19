package io.specmatic.core.config.v3.upgrade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.specmatic.core.config.ConfigTemplateUtils
import java.util.Locale

object TemplatePreservingConfigUpgrade {
    private val sectionsWhoseStructureChanged = listOf(
        SectionMigration("/test", listOf("/systemUnderTest/service/runOptions", "/specmatic/settings/test")),
        SectionMigration("/stub", listOf("/dependencies", "/systemUnderTest/service/data", "/specmatic/settings/mock")),
        SectionMigration("/hooks", listOf("/dependencies/data/adapters", "/proxies")),
        SectionMigration("/auth", listOf("/systemUnderTest/service/definitions", "/dependencies/services")),
        SectionMigration("/security", listOf("/systemUnderTest/service/runOptions/openapi")),
        SectionMigration("/workflow", listOf("/systemUnderTest/service/runOptions/openapi/workflow"), allowFieldFallback = false),
        SectionMigration("/license_path", listOf("/specmatic/license")),
        SectionMigration("/report_dir_path", listOf("/specmatic/governance/report")),
        SectionMigration("/report", listOf("/specmatic/governance")),
        SectionMigration("/mcp", listOf("/mcp")),
        SectionMigration("/logging", listOf("/specmatic/settings/general/logging")),
        SectionMigration("/backwardCompatibility", listOf("/specmatic/settings/backwardCompatibility")),
        SectionMigration("/globalSettings", listOf("/specmatic/settings/general")),
    )

    private val sectionsWhoseLeadNodeNameChanged = listOf(
        SectionMigration("/proxy", listOf("/proxies")),
        SectionMigration("/examples", listOf("/systemUnderTest/service/data/examples", "/dependencies/data/examples")),
    )

    private val nodeNameChangedMigrations = listOf(
        NodeNameChangedMigration(legacyNodeName = "examples", upgradedNodeName = "directories"),
        NodeNameChangedMigration(legacyNodeName = "basePath", upgradedNodeName = "urlPathPrefix"),
        NodeNameChangedMigration(legacyNodeName = "bearer-environment-variable", upgradedNodeName = "bearerEnvironmentVariable"),
        NodeNameChangedMigration(legacyNodeName = "personal-access-token", upgradedNodeName = "personalAccessToken"),
        NodeNameChangedMigration(legacyNodeName = "bearer-file", upgradedNodeName = "bearerFile"),
    )

    private val exactNodeMigrations = listOf(
        ExactNodeMigration("/license_path", "/specmatic/license/path"),
        ExactNodeMigration("/licensePath", "/specmatic/license/path"),
        ExactNodeMigration("/report_dir_path", "/specmatic/governance/report/outputDirectory"),
        ExactNodeMigration("/reportDirPath", "/specmatic/governance/report/outputDirectory"),
        ExactNodeMigration("/stub/dictionary", "/systemUnderTest/service/data/dictionary/path"),
        ExactNodeMigration("/stub/dictionary", "/dependencies/data/dictionary/path"),
        ExactNodeMigration("/test/resiliencyTests/enable", "/specmatic/settings/test/schemaResiliencyTests"),
        ExactNodeMigration(
            "/report/types/APICoverage/OpenAPI/successCriteria/minThresholdPercentage",
            "/specmatic/governance/successCriteria/minCoveragePercentage"
        ),
        ExactNodeMigration(
            "/report/types/APICoverage/OpenAPI/successCriteria/maxMissedEndpointsInSpec",
            "/specmatic/governance/successCriteria/maxMissedOperationsInSpec"
        ),
        ExactNodeMigration("/schemaExampleDefault", "/specmatic/settings/general/featureFlags/schemaExampleDefault"),
        ExactNodeMigration("/fuzzy", "/specmatic/settings/general/featureFlags/fuzzyMatcherForPayloads"),
        ExactNodeMigration("/escapeSoapAction", "/specmatic/settings/general/featureFlags/escapeSoapAction"),
        ExactNodeMigration("/disable_telemetry", "/specmatic/settings/general/disableTelemetry"),
        ExactNodeMigration("/disableTelemetry", "/specmatic/settings/general/disableTelemetry"),
    )

    private val valueShapeChangedMigrations = listOf(
        ValueShapeChangedMigration(
            rawPointer = "/stub/hotReload",
            targetPointers = listOf("/dependencies/settings/hotReload", "/specmatic/settings/mock/hotReload"),
            defaultValueRewrites = listOf(
                TemplateDefaultRewrite(legacyDefault = "enabled", upgradedDefault = "true"),
                TemplateDefaultRewrite(legacyDefault = "disabled", upgradedDefault = "false"),
            )
        )
    )

    fun preserveTemplates(rawLegacyConfig: JsonNode, upgradedConfig: JsonNode): JsonNode {
        val patchedConfig = upgradedConfig.deepCopy<JsonNode>()
        if (rawLegacyConfig["version"]?.asInt() != 2) return patchedConfig

        val patcher = TemplatePatcher(rawLegacyConfig, patchedConfig)
        sectionsWhoseStructureChanged.forEach(patcher::patchSectionWhoseStructureChanged)
        sectionsWhoseLeadNodeNameChanged.forEach(patcher::patchSectionWhoseLeadNodeNameChanged)
        exactNodeMigrations.forEach(patcher::patchExactNodeMigration)
        valueShapeChangedMigrations.forEach(patcher::patchValueShapeChangedMigration)
        patcher.patchTopLevelFlags()
        patcher.patchContracts()

        return patchedConfig
    }

    private class TemplatePatcher(private val rawConfig: JsonNode, private val root: JsonNode) {
        fun patchSectionWhoseStructureChanged(migration: SectionMigration) {
            patchSection(migration)
        }

        fun patchSectionWhoseLeadNodeNameChanged(migration: SectionMigration) {
            patchSection(migration)
        }

        private fun patchSection(migration: SectionMigration) {
            val rawSection = rawConfig.at(migration.rawPointer).takeUnless(JsonNode::isMissingNode) ?: return
            val rootFieldName = migration.rawPointer.trim('/').substringAfterLast('/').takeIf(String::isNotBlank)
            val templates = rawSection.collectTemplateValues(fieldName = rootFieldName)
            if (templates.isEmpty()) return
            migration.targetPointers
                .mapNotNull { root.at(it).takeUnless(JsonNode::isMissingNode) }
                .forEach { target -> target.replaceMatchingLeaves(templates, allowFieldFallback = migration.allowFieldFallback) }
        }

        fun patchTopLevelFlags() {
            val topLevelFlagNames = listOf(
                "ignoreInlineExamples",
                "ignoreInlineExampleWarnings",
                "prettyPrint",
            )
            val rawFlags = ObjectNode(JsonNodeFactory.instance).also { flags ->
                topLevelFlagNames.forEach { name ->
                    rawConfig[name]?.let { flags.set<JsonNode>(name, it) }
                }
            }
            val templates = rawFlags.collectTemplateValues()
            if (templates.isEmpty()) return
            root.at("/specmatic/settings/general")
                .takeUnless(JsonNode::isMissingNode)
                ?.replaceMatchingLeaves(templates)
        }

        fun patchExactNodeMigration(migration: ExactNodeMigration) {
            patchRawTemplate(migration.rawPointer, migration.targetPointer)
        }

        fun patchValueShapeChangedMigration(migration: ValueShapeChangedMigration) {
            patchSwitchTemplate(migration)
        }

        fun patchContracts() {
            val contracts = rawConfig["contracts"]?.asContractSections().orEmpty()
            contracts.forEach { contract ->
                patchContractSourceTemplates(contract)
                ContractSide.entries.forEach { side -> patchContractSide(contract, side) }
            }
        }

        private fun patchContractSourceTemplates(contract: JsonNode) {
            val sourceTemplates = contract.sourceTemplates()
            listOf("/systemUnderTest/service/definitions", "/dependencies/services")
                .mapNotNull { root.at(it).takeUnless(JsonNode::isMissingNode) }
                .forEach { target -> target.replaceMatchingLeaves(sourceTemplates) }
        }

        private fun JsonNode.asContractSections(): List<JsonNode> {
            return when {
                isArray -> elements().asSequence().toList()
                isObject -> listOf(this)
                else -> emptyList()
            }
        }

        private fun patchContractSide(contract: JsonNode, side: ContractSide) {
            val rawEntries = contract[side.legacyKey]?.takeIf(JsonNode::isArray) ?: return
            val targets = side.targetPointers
                .mapNotNull { targetPointer -> root.at(targetPointer).takeUnless(JsonNode::isMissingNode) }

            rawEntries.forEachIndexed { index, entry ->
                targets.forEach { target -> target.patchContractEntryTemplates(entry, index) }
            }
            targets.forEach { target -> patchGenericRunOptionConfigTemplates(rawEntries, target) }
            targets.forEach { target -> patchGenericBaseUrlTemplates(rawEntries, target) }
        }

        private fun JsonNode.patchContractEntryTemplates(entry: JsonNode, index: Int) {
            when {
                entry.isTextual -> patchSpecPathTemplate(entry)
                entry.isObject -> {
                    entry["specs"]?.takeIf(JsonNode::isArray)?.forEach { spec -> patchSpecPathTemplate(spec) }
                    patchOpenApiRunOptionTemplate(entry, "/baseUrl", index, "baseUrl")
                    patchOpenApiRunOptionTemplate(entry, "/config/baseUrl", index, "baseUrl")
                    patchOpenApiRunOptionTemplate(entry, "/host", index, "host")
                    patchOpenApiRunOptionTemplate(entry, "/port", index, "port")
                    patchDefinitionTemplate(entry, "/basePath", "urlPathPrefix")
                    patchDefinitionTemplate(entry, "/resiliencyTests/enable", "schemaResiliencyTests")
                }
            }
        }

        private fun JsonNode.patchSpecPathTemplate(rawSpecPath: JsonNode) {
            val template = rawSpecPath.toTemplateValue("path") ?: return
            replaceMatchingLeaf(template, setOf("path"), includeArrayValues = true)
        }

        private fun JsonNode.patchOpenApiRunOptionTemplate(entry: JsonNode, rawPointer: String, index: Int, targetField: String) {
            val template = entry.at(rawPointer).toTemplateValue(targetField) ?: return
            findRunOptionsSections("openapi").forEach { runOptions ->
                val specs = runOptions["specs"]?.takeIf(JsonNode::isArray) ?: return@forEach
                val candidates = listOfNotNull(
                    specs.get(index)?.get("spec") as? ObjectNode,
                    specs.takeIf { it.size() == 1 }?.get(0)?.get("spec") as? ObjectNode,
                ).distinct()
                candidates.forEach { spec ->
                    val currentValue = spec[targetField] ?: return@forEach
                    if (currentValue.matchesResolvedValue(template.resolved)) {
                        spec.set<JsonNode>(targetField, TextNode.valueOf(template.rawText))
                    }
                }
            }
        }

        private fun JsonNode.patchDefinitionTemplate(entry: JsonNode, rawPointer: String, targetField: String) {
            val template = entry.at(rawPointer).toTemplateValue(targetField) ?: return
            replaceMatchingLeaf(template, setOf(targetField), includeArrayValues = false)
        }

        private fun patchGenericBaseUrlTemplates(rawEntries: JsonNode, target: JsonNode) {
            rawEntries.filter(JsonNode::isObject).forEach { entry ->
                val runOptionsName = entry.genericRunOptionsName() ?: return@forEach

                val baseUrl = entry.at("/config/baseUrl")
                    .takeIf { it.isTextual && ConfigTemplateUtils.isConfigTemplate(it.asText()) }
                    ?: return@forEach

                entry.specPaths().forEach { specPath ->
                    target.findGenericSpecOverrides(runOptionsName, specPath).forEach { spec ->
                        spec.set<JsonNode>("baseUrl", TextNode.valueOf(baseUrl.asText()))
                        spec.remove("host")
                        spec.remove("port")
                    }
                }
            }
        }

        private fun patchGenericRunOptionConfigTemplates(rawEntries: JsonNode, target: JsonNode) {
            rawEntries.filter(JsonNode::isObject).forEach { entry ->
                val runOptionsName = entry.genericRunOptionsName() ?: return@forEach
                val configTemplates = entry["config"]?.collectTemplateValues().orEmpty()
                if (configTemplates.isEmpty()) return@forEach

                entry.specPaths().forEach { specPath ->
                    target.findGenericSpecOverrides(runOptionsName, specPath).forEach { spec ->
                        spec.replaceMatchingLeaves(configTemplates)
                    }
                }
            }
        }

        private fun JsonNode.specPaths(): List<String> {
            return this["specs"]?.takeIf(JsonNode::isArray)?.map(JsonNode::asText).orEmpty()
        }

        private fun JsonNode.genericRunOptionsName(): String? {
            return when (this["specType"]?.asText()?.uppercase(Locale.ROOT)) {
                "ASYNCAPI" -> "asyncapi"
                "GRAPHQL" -> "graphqlsdl"
                "PROTOBUF" -> "protobuf"
                else -> null
            }
        }

        private fun JsonNode.findGenericSpecOverrides(runOptionsName: String, specPath: String): List<ObjectNode> {
            val specId = findSpecificationIdForPath(specPath) ?: return emptyList()
            return findRunOptionsSections(runOptionsName).flatMap { runOptions ->
                runOptions["specs"]
                    ?.takeIf(JsonNode::isArray)
                    ?.mapNotNull { specOverride -> specOverride["spec"] as? ObjectNode }
                    ?.filter { specOverride -> specOverride["id"]?.asText() == specId }
                    .orEmpty()
            }
        }

        private fun JsonNode.findSpecificationIdForPath(specPath: String): String? {
            return findObjectNodesWithField("path", specPath)
                .firstNotNullOfOrNull { spec -> spec["id"]?.asText() }
        }

        private fun JsonNode.sourceTemplates(): List<TemplateValue> {
            val source = ObjectNode(JsonNodeFactory.instance)
            listOf("filesystem", "git", "web").forEach { key ->
                this[key]?.let { source.set<JsonNode>(key, it) }
            }
            return source.collectTemplateValues()
        }

        private fun patchRawTemplate(rawPointer: String, targetPointer: String) {
            val rawValue = rawConfig.at(rawPointer)
                .takeIf { it.isTextual && ConfigTemplateUtils.isConfigTemplate(it.asText()) }
                ?: return
            val targetValue = root.at(targetPointer).takeUnless(JsonNode::isMissingNode) ?: return
            val resolved = ConfigTemplateUtils.resolveTemplateValue(TextNode.valueOf(rawValue.asText()))
            if (targetValue.matchesResolvedValue(resolved)) {
                root.replaceAt(targetPointer, TextNode.valueOf(rawValue.asText()))
            }
        }

        private fun patchSwitchTemplate(migration: ValueShapeChangedMigration) {
            val rawValue = rawConfig.at(migration.rawPointer)
                .takeIf { it.isTextual && ConfigTemplateUtils.isConfigTemplate(it.asText()) }
                ?: return
            val migratedTemplate = migration.defaultValueRewrites.fold(rawValue.asText()) { template, rewrite ->
                template.replace(":${rewrite.legacyDefault}}", ":${rewrite.upgradedDefault}}")
            }
            val resolved = ConfigTemplateUtils.resolveTemplateValue(TextNode.valueOf(migratedTemplate))
            migration.targetPointers.forEach { targetPointer ->
                val targetValue = root.at(targetPointer).takeUnless(JsonNode::isMissingNode) ?: return@forEach
                if (targetValue.matchesResolvedValue(resolved)) {
                    root.replaceAt(targetPointer, TextNode.valueOf(migratedTemplate))
                }
            }
        }
    }

    private data class SectionMigration(
        val rawPointer: String,
        val targetPointers: List<String>,
        val allowFieldFallback: Boolean = true
    )

    private data class NodeNameChangedMigration(val legacyNodeName: String, val upgradedNodeName: String)

    private data class ExactNodeMigration(val rawPointer: String, val targetPointer: String)

    private data class ValueShapeChangedMigration(
        val rawPointer: String,
        val targetPointers: List<String>,
        val defaultValueRewrites: List<TemplateDefaultRewrite>
    )

    private data class TemplateDefaultRewrite(val legacyDefault: String, val upgradedDefault: String)

    private enum class ContractSide(val legacyKey: String, val targetPointers: List<String>) {
        PROVIDES(
            legacyKey = "provides",
            targetPointers = listOf("/systemUnderTest/service", "/components/runOptions")
        ),
        CONSUMES(
            legacyKey = "consumes",
            targetPointers = listOf("/dependencies/services")
        )
    }

    private data class TemplateValue(val rawText: String, val resolved: JsonNode, val fieldName: String?, val path: List<String>)

    private fun JsonNode.collectTemplateValues(fieldName: String? = null, path: List<String> = emptyList()): List<TemplateValue> {
        return when {
            isTextual && ConfigTemplateUtils.isConfigTemplate(asText()) -> toTemplateValue(fieldName, path)?.let(::listOf).orEmpty()
            isObject -> properties().asSequence().flatMap { (name, child) -> child.collectTemplateValues(name, path + name) }.toList()
            isArray -> elements().asSequence().flatMap { child -> child.collectTemplateValues(fieldName, path) }.toList()
            else -> emptyList()
        }
    }

    private fun JsonNode.toTemplateValue(fieldName: String?, path: List<String> = emptyList()): TemplateValue? {
        if (!isTextual || !ConfigTemplateUtils.isConfigTemplate(asText())) return null
        val resolved = ConfigTemplateUtils.resolveTemplateValue(TextNode.valueOf(asText()))
        if (!resolved.isValueNode) return null
        return TemplateValue(
            rawText = asText(),
            resolved = resolved,
            fieldName = fieldName,
            path = path,
        )
    }

    private fun JsonNode.replaceMatchingLeaves(
        templates: List<TemplateValue>,
        fieldName: String? = null,
        path: List<String> = emptyList(),
        allowFieldFallback: Boolean = true
    ) {
        when (this) {
            is ObjectNode -> properties().asSequence().toList().forEach { (fieldName, child) ->
                val childPath = path + fieldName
                val match = templates.firstOrNull {
                    it.matchesLocation(fieldName, childPath, allowFieldFallback) && child.matchesResolvedValue(it.resolved)
                }
                if (match != null) {
                    set<JsonNode>(fieldName, TextNode.valueOf(match.rawText))
                } else {
                    child.replaceMatchingLeaves(templates, fieldName, childPath, allowFieldFallback)
                }
            }
            is ArrayNode -> elements().asSequence().toList().forEachIndexed { index, child ->
                val match = templates.firstOrNull {
                    it.matchesLocation(fieldName, path, allowFieldFallback) && child.matchesResolvedValue(it.resolved)
                }
                if (match != null) {
                    set(index, TextNode.valueOf(match.rawText))
                } else {
                    child.replaceMatchingLeaves(templates, fieldName, path, allowFieldFallback)
                }
            }
        }
    }

    private fun TemplateValue.matchesLocation(fieldName: String?, path: List<String>, allowFieldFallback: Boolean): Boolean {
        return this.path == path || (allowFieldFallback && matchesFieldName(fieldName))
    }

    private fun JsonNode.replaceMatchingLeaf(template: TemplateValue, fieldNames: Set<String>, includeArrayValues: Boolean) {
        when (this) {
            is ObjectNode -> properties().asSequence().toList().forEach { (fieldName, child) ->
                if (fieldName in fieldNames && child.matchesResolvedValue(template.resolved)) {
                    set<JsonNode>(fieldName, TextNode.valueOf(template.rawText))
                } else {
                    child.replaceMatchingLeaf(template, fieldNames, includeArrayValues)
                }
            }
            is ArrayNode -> elements().asSequence().toList().forEachIndexed { index, child ->
                if (includeArrayValues && child.matchesResolvedValue(template.resolved)) {
                    set(index, TextNode.valueOf(template.rawText))
                } else {
                    child.replaceMatchingLeaf(template, fieldNames, includeArrayValues)
                }
            }
        }
    }

    private fun TemplateValue.matchesFieldName(fieldName: String?): Boolean {
        return this.fieldName == fieldName ||
            nodeNameChangedMigrations.any {
                it.legacyNodeName == this.fieldName && it.upgradedNodeName == fieldName
            }
    }

    private fun JsonNode.replaceAt(pointer: String, value: JsonNode) {
        val segments = pointer.trim('/').split('/').filter(String::isNotBlank).map(::unescapePointerSegment)
        val parent = segments.dropLast(1).fold(this) { node, segment ->
            when (node) {
                is ObjectNode -> node[segment]
                is ArrayNode -> segment.toIntOrNull()?.let(node::get)
                else -> null
            } ?: return
        }
        when (parent) {
            is ObjectNode -> parent.set<JsonNode>(segments.last(), value)
            is ArrayNode -> segments.last().toIntOrNull()?.let { index -> parent.set(index, value) }
        }
    }

    private fun unescapePointerSegment(segment: String): String {
        return segment.replace("~1", "/").replace("~0", "~")
    }

    private fun JsonNode.matchesResolvedValue(resolved: JsonNode): Boolean {
        return this == resolved || (isValueNode && resolved.isValueNode && asText() == resolved.asText())
    }

    private fun JsonNode.findObjectNodesWithField(fieldName: String, fieldValue: String): List<ObjectNode> {
        return when {
            this is ObjectNode -> {
                val self = listOf(this).filter { it[fieldName]?.asText() == fieldValue }
                self + properties().asSequence().flatMap { (_, child) -> child.findObjectNodesWithField(fieldName, fieldValue) }.toList()
            }
            isArray -> elements().asSequence().flatMap { child -> child.findObjectNodesWithField(fieldName, fieldValue) }.toList()
            else -> emptyList()
        }
    }

    private fun JsonNode.findRunOptionsSections(runOptionsName: String): List<ObjectNode> {
        return when {
            this is ObjectNode -> {
                properties().asSequence().flatMap { (key, child) ->
                    val matchingChild = if (key == runOptionsName && child is ObjectNode) listOf(child) else emptyList()
                    matchingChild + child.findRunOptionsSections(runOptionsName)
                }.toList()
            }
            isArray -> elements().asSequence().flatMap { child -> child.findRunOptionsSections(runOptionsName) }.toList()
            else -> emptyList()
        }
    }
}
