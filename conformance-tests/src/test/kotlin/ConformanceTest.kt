import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import java.util.stream.Stream

class ConformanceTest {

    @TestFactory
    fun conformanceTests(): Stream<DynamicNode> {
        val specsDir = File("build/resources/test/specs")
        val specFiles = specsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("yaml", "yml") }
            .map { it.relativeTo(specsDir).path }
            .sorted().toList()

        val maxCores = Runtime.getRuntime().availableProcessors()
        val concurrency = (System.getProperty("conformance.concurrency")
            ?: System.getenv("CONFORMANCE_CONCURRENCY")
            ?: "$maxCores").toInt().coerceIn(1, maxCores)

        val batches = specFiles.chunked(concurrency)

        return IntStream.range(0, batches.size).mapToObj { index ->
            DynamicContainer.dynamicContainer(
                "batch ${index + 1}",
                startBatchAndBuildTests(batches[index])
            ) as DynamicNode
        }
    }

    private fun startBatchAndBuildTests(batch: List<String>): Stream<DynamicNode> {
        val runs = batch.map {
            it to SpecRun(it, File("build/resources/test"))
        }
        val executor = Executors.newFixedThreadPool(batch.size)
        val futures = runs.map { (specFile, run) ->
            specFile to executor.submit { run.start() }
        }
        executor.shutdown()

        futures.forEach { it.second.get(60, TimeUnit.SECONDS) }

        return runs.map { (specFile, run) ->
            buildContainer(specFile, run) as DynamicNode
        }.stream()
    }

    private fun buildContainer(specFile: String, run: SpecRun): DynamicContainer {
        return DynamicContainer.dynamicContainer(
            specFile, listOf(
                loopTestExecutionResult(run),
                allRoutesExercisedTest(run),
                noUndocumentedRoutesTest(run),
                requestBodiesValidTest(run),
                responseBodiesValidTest(run),
                allRequestExamplesExercisedTest(run),
                allResponseExamplesExercisedTest(run),
                teardownTest(run)
            )
        )
    }

    private fun loopTestExecutionResult(run: SpecRun) =
        DynamicTest.dynamicTest("successfully completes a loop test") {
            assertThat(run.loopTestResult.exitCode)
                .withFailMessage(
                    "loopTest failed with exit code: ${run.loopTestResult.exitCode}. Logs: ${run.dockerCompose.mustGetAllLogsOutput()}"
                )
                .isEqualTo(0)
        }

    private fun teardownTest(run: SpecRun) =
        DynamicTest.dynamicTest("teardown") {
            run.stop()
        }

    private fun allRoutesExercisedTest(run: SpecRun) =
        DynamicTest.dynamicTest("all spec routes exercised") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val allRoutes = routeMatcher.allRoutes()
            val exercised = routeMatcher.exercisedRoutes(run.captures)
            val unexercised = allRoutes - exercised
            assertThat(unexercised)
                .withFailMessage("Routes not exercised: $unexercised")
                .isEmpty()
        }

    private fun noUndocumentedRoutesTest(run: SpecRun) =
        DynamicTest.dynamicTest("no undocumented routes accessed") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val undocumented = run.captures
                .filter { !isInfrastructureRequest(it) }
                .filter { !routeMatcher.matches(it.method, it.path) }
            assertThat(undocumented)
                .withFailMessage("Undocumented routes accessed: ${undocumented.map { "${it.method} ${it.path}" }}")
                .isEmpty()
        }

    private fun isInfrastructureRequest(capture: HttpCapture): Boolean =
        capture.path.startsWith("/swagger/") || (capture.method == "HEAD" && capture.path == "/")

    private fun requestBodiesValidTest(run: SpecRun) =
        DynamicTest.dynamicTest("request bodies valid") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val validator = SchemaValidator()
            val errors = run.captures
                .filter { it.requestBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val schema = run.openApiSpec.requestBodySchema(capture.method, template) ?: return@mapNotNull null
                    val result = validator.validate(capture.requestBody, schema)
                    if (!result.valid) "${capture.method} ${capture.path} ${capture.requestBody}: ${result.errors}" else null
                }
            assertThat(errors)
                .withFailMessage("Request body validation errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }

    private fun responseBodiesValidTest(run: SpecRun) =
        DynamicTest.dynamicTest("response bodies valid") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val validator = SchemaValidator()
            val errors = run.captures
                .filter { it.responseBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val schema = run.openApiSpec.responseBodySchema(capture.method, template, capture.statusCode)
                        ?: return@mapNotNull null
                    val result = validator.validate(capture.responseBody, schema)
                    if (!result.valid) "${capture.method} ${capture.path} ${capture.statusCode} ${capture.responseBody}: ${result.errors}" else null
                }
            assertThat(errors)
                .withFailMessage("Response body validation errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }

    private fun allRequestExamplesExercisedTest(run: SpecRun) =
        DynamicTest.dynamicTest("all request examples exercised") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val mapper = ObjectMapper()

            val allExamples = mutableMapOf<Triple<String, String, String>, JsonNode>()
            for ((method, template) in run.openApiSpec.allRoutes()) {
                val examples = run.openApiSpec.requestBodyExamples(method, template)
                for ((name, value) in examples) {
                    allExamples[Triple(method, template, name)] = value
                }
            }

            if (allExamples.isEmpty()) return@dynamicTest

            val unexercised = allExamples.toMutableMap()
            for (capture in run.captures) {
                if (capture.requestBody.isBlank()) continue
                val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: continue
                val body = mapper.readTree(capture.requestBody)
                val keysToRemove = unexercised.entries
                    .filter { it.key.first == capture.method.uppercase() && it.key.second == template && it.value == body }
                    .map { it.key }
                keysToRemove.forEach { unexercised.remove(it) }
            }

            assertThat(unexercised.keys)
                .withFailMessage("Request examples not exercised: ${unexercised.keys.map { "(${it.first} ${it.second} ${it.third})" }}")
                .isEmpty()
        }

    private fun allResponseExamplesExercisedTest(run: SpecRun) =
        DynamicTest.dynamicTest("all response examples exercised") {
            val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
            val mapper = ObjectMapper()

            data class ExampleKey(val method: String, val template: String, val statusCode: Int, val name: String)

            // Only collect response examples for operations that also have request body examples,
            // because Specmatic pairs examples by name. Without request examples, the mock
            // cannot determine which response example to return and falls back to generated values.
            val allExamples = mutableMapOf<ExampleKey, JsonNode>()
            for ((method, template) in run.openApiSpec.allRoutes()) {
                val requestExamples = run.openApiSpec.requestBodyExamples(method, template)
                if (requestExamples.isEmpty()) continue

                val model = run.openApiSpec.rawModel()
                val pathItem = model.paths?.get(template) ?: continue
                val operation = pathItem.readOperationsMap()?.entries
                    ?.firstOrNull { it.key.name.equals(method, ignoreCase = true) }?.value ?: continue
                for ((statusCodeStr, _) in operation.responses.orEmpty()) {
                    val statusCode = statusCodeStr.toIntOrNull() ?: continue
                    val examples = run.openApiSpec.responseBodyExamples(method, template, statusCode)
                    for ((name, value) in examples) {
                        allExamples[ExampleKey(method, template, statusCode, name)] = value
                    }
                }
            }

            if (allExamples.isEmpty()) return@dynamicTest

            val unexercised = allExamples.toMutableMap()
            for (capture in run.captures) {
                if (capture.responseBody.isBlank()) continue
                val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: continue
                val body = mapper.readTree(capture.responseBody)
                val keysToRemove = unexercised.entries
                    .filter { it.key.method == capture.method.uppercase() && it.key.template == template && it.key.statusCode == capture.statusCode && it.value == body }
                    .map { it.key }
                keysToRemove.forEach { unexercised.remove(it) }
            }

            assertThat(unexercised.keys)
                .withFailMessage("Response examples not exercised: ${unexercised.keys.map { "(${it.method} ${it.template} ${it.statusCode} ${it.name})" }}")
                .isEmpty()
        }
}
