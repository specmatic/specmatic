package io.specmatic.core.utilities

import io.specmatic.core.NamedStub
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.toGherkinFeature
import io.specmatic.core.urlDecodePathSegments
import io.specmatic.mock.ScenarioStub
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.Paths
import java.io.File

fun openApiYamlFromExampleDir(examplesDir: File, featureName: String = "New feature"): String? {
    if (!examplesDir.exists() || !examplesDir.isDirectory) return null

    val namedStubs = examplesDir.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.name }
        .map { file ->
            val stub = ScenarioStub.readFromFile(file)
            val request = stub.requestElsePartialRequest()
            val decodedPath = request.path?.let(::decodePathSegments)
            val decodedStub = if (decodedPath != null && decodedPath != request.path) {
                stub.updateRequest(request.copy(path = decodedPath))
            } else {
                stub
            }
            val name = stub.name ?: file.nameWithoutExtension
            NamedStub(name, file.nameWithoutExtension, decodedStub)
        }

    if (namedStubs.isEmpty()) return null

    val gherkin = toGherkinFeature(featureName, namedStubs)
    val feature = parseGherkinStringToFeature(gherkin)
    val openApi = feature.toOpenApi()
    val paths = openApi.paths
    if (paths != null) {
        val decodedPaths = Paths()
        paths.forEach { (path, item) ->
            decodedPaths.addPathItem(urlDecodePathSegments(path), item)
        }
        openApi.paths = decodedPaths
    }
    return Yaml.pretty(openApi)
}

private fun decodePathSegments(path: String): String {
    var decoded = path
    repeat(3) {
        val next = urlDecodePathSegments(decoded)
        if (next == decoded) return decoded
        decoded = next
    }
    return decoded
}
