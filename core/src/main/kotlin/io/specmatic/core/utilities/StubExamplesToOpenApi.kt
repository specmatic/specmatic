package io.specmatic.core.utilities

import io.specmatic.core.NamedStub
import io.specmatic.mock.ScenarioStub
import io.specmatic.proxy.ProxyOperation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.core.util.Yaml
import java.io.File
import kotlin.collections.flatten

fun openApiYamlFromExampleDir(examplesDir: File, featureName: String = "New feature", sortOrder: List<ProxyOperation>): String? {
    if (!examplesDir.exists() || !examplesDir.isDirectory) return null

    val namedStubs = examplesDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.map { file ->
        val stub = ScenarioStub.readFromFile(file)
        val name = stub.name ?: file.nameWithoutExtension
        NamedStub(name, file.nameWithoutExtension, stub)
    }

    if (namedStubs.isEmpty()) return null

    return Yaml.pretty(openApiFromTraffic(featureName, namedStubs, sortOrder))
}

fun openApiFromTraffic(featureName: String, namedStubs: List<NamedStub>, sortOrder: List<ProxyOperation> = emptyList()): OpenAPI? {
    if (namedStubs.isEmpty()) return null

    val orderedStubs = orderStubsByProxyOperations(namedStubs, sortOrder)
    return featureFromTraffic(featureName, orderedStubs).toOpenApi()
}

private fun indexOperationsByMethod(sortOrder: List<ProxyOperation>): Map<String, List<IndexedValue<ProxyOperation>>> {
    return sortOrder.withIndex().groupBy(keySelector = { it.value.method.lowercase() }, valueTransform = { it })
}

private fun orderStubsByProxyOperations(namedStubs: List<NamedStub>, sortOrder: List<ProxyOperation>): List<NamedStub> {
    if (sortOrder.isEmpty()) return namedStubs
    val operationsByMethod = indexOperationsByMethod(sortOrder)
    val buckets: List<MutableList<NamedStub>> = List(sortOrder.size + 1) { mutableListOf() }
    for (stub in namedStubs) {
        val request = stub.stub.requestElsePartialRequest()
        val method = request.method?.lowercase()
        val opIndex =
            operationsByMethod[method]
                ?.firstOrNull { (_, proxyOperation) ->
                    proxyOperation.matches(request)
                }?.index
                ?: sortOrder.size
        buckets[opIndex].add(stub)
    }

    return buckets.flatten()
}
