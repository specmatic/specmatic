package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.cucumber.messages.types.FeatureChild
import io.cucumber.messages.types.GherkinDocument
import io.cucumber.messages.types.Background
import io.cucumber.messages.types.Step
import io.swagger.v3.parser.util.ClasspathHelper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

interface WSDLContent {
    val path: String

    fun read(): String?
}

class WSDLFile(private val location: String) : WSDLContent {
    override val path: String
        get() = location

    override fun read(): String? {
        val adjustedLocation = location.replace("\\\\".toRegex(), "/")
        val fileScheme = "file:"
        val path = if (adjustedLocation.lowercase()
                .startsWith(fileScheme)
        ) Paths.get(URI.create(adjustedLocation)) else Paths.get(adjustedLocation)
        return if (Files.exists(path)) {
            path.toFile().readText()
        } else {
            ClasspathHelper.loadFileFromClasspath(adjustedLocation)
        }
    }
}

class WsdlSpecification(
    private val wsdlFile: WSDLContent,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig()
) : IncludedSpecification {
    private val openApiScenarioInfos: List<ScenarioInfo> = toScenarioInfos().first

    override fun matches(
        specmaticScenarioInfo: ScenarioInfo,
        steps: List<Step>
    ): List<ScenarioInfo> {
        if (openApiScenarioInfos.isEmpty() || steps.isEmpty()) return listOf(specmaticScenarioInfo)
        val result: MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> =
            specmaticScenarioInfo to openApiScenarioInfos to
                    ::matchesRequest then
                    ::matchesResponse otherwise
                    ::handleError
        when (result) {
            is MatchFailure -> throw ContractException(result.error.message)
            is MatchSuccess -> return result.value.second
        }
    }

    private fun matchesRequest(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, wsdlScenarioInfos) = parameters

        val matchingScenarioInfos = wsdlScenarioInfos.filter { scenarioInfo ->
            scenarioInfo.httpRequestPattern.matches(
                specmaticScenarioInfo.httpRequestPattern.generate(
                    Resolver(newPatterns = scenarioInfo.patterns)
                ), Resolver(newPatterns = scenarioInfo.patterns)
            ).isSuccess()
        }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Result.Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" request is not as per included wsdl / OpenApi spec"""
                )
            )
            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun matchesResponse(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, wsdlScenarioInfos) = parameters

        val matchingScenarioInfos = wsdlScenarioInfos.filter {
            it.httpResponsePattern.matchesResponse(
                specmaticScenarioInfo.httpResponsePattern.fillInTheBlanks(
                    Resolver()
                ), Resolver()
            ).isSuccess()
        }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Result.Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" response is not as per included wsdl / OpenApi spec"""
                )
            )
            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    override fun toScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        return scenarioInfos(
            wsdlToFeatureChildren(wsdlFile),
            "",
            true,
            specmaticConfig = specmaticConfig,
        ) to emptyMap()
    }

    private fun wsdlToFeatureChildren(wsdlFile: WSDLContent): List<FeatureChild> {
        val wsdlContent = wsdlFile.read() ?: throw ContractException("Could not read WSDL file $wsdlFile")
        val wsdl = WSDL(toXMLNode(wsdlContent), wsdlFile.path)
        val gherkin = wsdl.convertToGherkin().trim()
        val gherkinDocument = parseGherkinString(gherkin, wsdlFile.path)
        return gherkinDocument.unwrapFeature().children
    }
}

fun GherkinDocument.unwrapFeature(): io.cucumber.messages.types.Feature {
    return feature.orElseThrow {
        IllegalStateException("Expected a Feature in GherkinDocument, but none was present.")
    }
}

fun FeatureChild.unwrapBackground(): Background {
    return background.orElseThrow {
        IllegalStateException("Expected a Background in FeatureChild, but none was present.")
    }
}

fun wsdlContentToFeature(
    wsdlContent: String,
    path: String,
    specmaticConfig: SpecmaticConfig = SpecmaticConfig()
): Feature {
    val wsdl = WSDL(toXMLNode(wsdlContent), path)
    val gherkin = wsdl.convertToGherkin().trim()
    return parseGherkinStringToFeature(gherkin, path, true, specmaticConfig)
}
