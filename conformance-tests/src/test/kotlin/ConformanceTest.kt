import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Stream

class ConformanceTest {

    companion object {
        private val runs = CopyOnWriteArrayList<SpecRun>()

        @JvmStatic
        @AfterAll
        fun teardown() {
            runs.forEach { it.stop() }
        }
    }

    @TestFactory
    fun conformanceTests(): Stream<DynamicContainer> {
        val specFiles = File("build/resources/test/specs")
            .listFiles { f -> f.isFile }.orEmpty()
            .map { it.name }.sorted()

        val futures = specFiles.map { specFile ->
            val run = SpecRun(specFile, File("build/resources/test"))
            specFile to CompletableFuture.supplyAsync { run.start(); run }
        }

        return futures.map { (specFile, future) ->
            val run = future.get()
            runs.add(run)
            buildContainer(specFile, run)
        }.stream()
    }

    private fun buildContainer(specFile: String, run: SpecRun): DynamicContainer {
        val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
        val validator = SchemaValidator()

        return DynamicContainer.dynamicContainer(
            specFile, listOf(
                exitCodeTest(run),
                allRoutesExercisedTest(run, routeMatcher),
                noUndocumentedRoutesTest(run, routeMatcher),
                requestBodiesValidTest(run, routeMatcher, validator),
                responseBodiesValidTest(run, routeMatcher, validator),
                requestContentTypeTest(run, routeMatcher),
                responseContentTypeTest(run, routeMatcher)
            )
        )
    }

    private fun exitCodeTest(run: SpecRun) =
        DynamicTest.dynamicTest("exits with code 0") {
            assertThat(run.dockerCompose.exitCode)
                .withFailMessage("Docker Compose exited with code ${run.dockerCompose.exitCode}")
                .isEqualTo(0)
        }

    private fun allRoutesExercisedTest(run: SpecRun, routeMatcher: RouteMatcher) =
        DynamicTest.dynamicTest("all spec routes exercised") {
            val allRoutes = routeMatcher.allRoutes()
            val exercised = routeMatcher.exercisedRoutes(run.captures)
            val unexercised = allRoutes - exercised
            assertThat(unexercised)
                .withFailMessage("Routes not exercised: $unexercised")
                .isEmpty()
        }

    private fun noUndocumentedRoutesTest(run: SpecRun, routeMatcher: RouteMatcher) =
        DynamicTest.dynamicTest("no undocumented routes accessed") {
            val undocumented = run.captures
                .filter { !isInfrastructureRequest(it) }
                .filter { !routeMatcher.matches(it.method, it.path) }
            assertThat(undocumented)
                .withFailMessage("Undocumented routes accessed: ${undocumented.map { "${it.method} ${it.path}" }}")
                .isEmpty()
        }

    private fun isInfrastructureRequest(capture: HttpCapture): Boolean =
        capture.path.startsWith("/swagger/") || (capture.method == "HEAD" && capture.path == "/")

    private fun requestBodiesValidTest(run: SpecRun, routeMatcher: RouteMatcher, validator: SchemaValidator) =
        DynamicTest.dynamicTest("request bodies valid") {
            val errors = run.captures
                .filter { it.requestBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val schema = run.openApiSpec.requestBodySchema(capture.method, template) ?: return@mapNotNull null
                    val result = validator.validate(capture.requestBody, schema)
                    if (!result.valid) "${capture.method} ${capture.path}: ${result.errors}" else null
                }
            assertThat(errors)
                .withFailMessage("Request body validation errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }

    private fun responseBodiesValidTest(run: SpecRun, routeMatcher: RouteMatcher, validator: SchemaValidator) =
        DynamicTest.dynamicTest("response bodies valid") {
            val errors = run.captures
                .filter { it.responseBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val schema = run.openApiSpec.responseBodySchema(capture.method, template, capture.statusCode) ?: return@mapNotNull null
                    val result = validator.validate(capture.responseBody, schema)
                    if (!result.valid) "${capture.method} ${capture.path} ${capture.statusCode}: ${result.errors}" else null
                }
            assertThat(errors)
                .withFailMessage("Response body validation errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }

    private fun requestContentTypeTest(run: SpecRun, routeMatcher: RouteMatcher) =
        DynamicTest.dynamicTest("request Content-Type headers correct") {
            val errors = run.captures
                .filter { it.requestBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val hasRequestBodySpec = run.openApiSpec.requestBodySchema(capture.method, template) != null
                    if (!hasRequestBodySpec) return@mapNotNull null
                    val contentType = capture.requestHeaders["content-type"] ?: ""
                    if (!contentType.contains("application/json"))
                        "${capture.method} ${capture.path}: content-type was '$contentType'"
                    else null
                }
            assertThat(errors)
                .withFailMessage("Request Content-Type errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }

    private fun responseContentTypeTest(run: SpecRun, routeMatcher: RouteMatcher) =
        DynamicTest.dynamicTest("response Content-Type headers correct") {
            val errors = run.captures
                .filter { it.responseBody.isNotBlank() }
                .mapNotNull { capture ->
                    val template = routeMatcher.matchingTemplate(capture.method, capture.path) ?: return@mapNotNull null
                    val hasResponseBodySpec = run.openApiSpec.responseBodySchema(capture.method, template, capture.statusCode) != null
                    if (!hasResponseBodySpec) return@mapNotNull null
                    val contentType = capture.responseHeaders["content-type"] ?: ""
                    if (!contentType.contains("application/json"))
                        "${capture.method} ${capture.path} ${capture.statusCode}: content-type was '$contentType'"
                    else null
                }
            assertThat(errors)
                .withFailMessage("Response Content-Type errors:\n${errors.joinToString("\n")}")
                .isEmpty()
        }
}
