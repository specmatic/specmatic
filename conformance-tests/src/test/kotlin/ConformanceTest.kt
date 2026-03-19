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
}
