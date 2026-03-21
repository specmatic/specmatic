import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
abstract class AbstractConformanceTest(private val specFile: String) {

    private lateinit var run: SpecRun

    @BeforeAll
    fun setUp() {
        run = SpecRun(specFile, File("build/resources/test"))
        run.start()
    }

    @AfterAll
    fun tearDown() {
        if (::run.isInitialized) {
            run.stop()
        }
    }

    @Test
    @Order(1)
    fun `successfully completes a loop test`() {
        assertThat(run.loopTestResult.exitCode)
            .withFailMessage(
                "loopTest failed with exit code: ${run.loopTestResult.exitCode}. Logs: ${run.dockerCompose.mustGetAllLogsOutput()}"
            )
            .isEqualTo(0)
    }

    @Test
    @Order(2)
    fun `all spec routes exercised`() {
        val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
        val allRoutes = routeMatcher.allRoutes()
        val exercised = routeMatcher.exercisedRoutes(run.captures)
        val unexercised = allRoutes - exercised
        assertThat(unexercised)
            .withFailMessage("Routes not exercised: $unexercised")
            .isEmpty()
    }

    @Test
    @Order(3)
    fun `no undocumented routes accessed`() {
        val routeMatcher = RouteMatcher(run.openApiSpec.rawModel())
        val undocumented = run.captures
            .filter { !isInfrastructureRequest(it) }
            .filter { !routeMatcher.matches(it.method, it.path) }
        assertThat(undocumented)
            .withFailMessage("Undocumented routes accessed: ${undocumented.map { "${it.method} ${it.path}" }}")
            .isEmpty()
    }

    @Test
    @Order(4)
    fun `request bodies valid`() {
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

    @Test
    @Order(5)
    fun `response bodies valid`() {
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

    private fun isInfrastructureRequest(capture: HttpCapture): Boolean =
        capture.path.startsWith("/swagger/") || (capture.method == "HEAD" && capture.path == "/")
}
