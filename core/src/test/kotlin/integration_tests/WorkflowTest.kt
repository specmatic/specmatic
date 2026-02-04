package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.config.WorkflowConfiguration
import io.specmatic.core.config.WorkflowIDOperation
import io.specmatic.core.config.v2.SpecmaticConfigV2Impl
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowTest {
    @Test
    fun `a spec should be able to use the id of a newly created entity in another request for the same entity`() {
        val specmaticConfig =
            SpecmaticConfigV2Impl(
                workflow = WorkflowConfiguration(
                    ids = mapOf(
                        "POST /orders -> 201" to WorkflowIDOperation(extract = "BODY.id"),
                        "*" to WorkflowIDOperation(use = "PATH.orderId")
                    )
                )
            )

        val feature = OpenApiSpecification
            .fromFile(
                "src/test/resources/openapi/spec_for_workflow_test.yaml",
                specmaticConfig = specmaticConfig
            )
            .toFeature()

        var idInRequest = ""

        feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.method == "POST")
                    return HttpResponse(201, parsedJSONObject("""{id: "new-order-id"}"""))

                idInRequest = request.path!!.split("/").last()

                return HttpResponse(200, parsedJSONObject("""{productid: 20, quantity: 1}"""))
            }
        })

        assertThat(idInRequest).isEqualTo("new-order-id")

    }
}