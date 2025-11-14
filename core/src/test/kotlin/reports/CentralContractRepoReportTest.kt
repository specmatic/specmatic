package reports

import io.specmatic.osAgnosticPath
import io.specmatic.reports.CentralContractRepoReport
import io.specmatic.reports.CentralContractRepoReportJson
import io.specmatic.reports.OpenAPISpecificationOperation
import io.specmatic.reports.SpecificationRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

class CentralContractRepoReportTest {

    @Test
    fun `test generates report based on all the open api specifications present in the specified dir`() {
        val report = CentralContractRepoReport().generate("./specifications/service1")
        assertThat(osAgnosticPaths(report)).isEqualTo(
            osAgnosticPaths(
                CentralContractRepoReportJson(
                    listOf(
                        SpecificationRow(
                            "specifications/service1/service1.yaml",
                            "HTTP",
                            "OPENAPI",
                            listOf(
                                OpenAPISpecificationOperation(
                                    "/hello/{id}",
                                    "GET",
                                    200
                                ),
                                OpenAPISpecificationOperation(
                                    "/hello/{id}",
                                    "GET",
                                    404
                                ),
                                OpenAPISpecificationOperation(
                                    "/hello/{id}",
                                    "GET",
                                    400
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `test generates report based on asyncapi 3_0_0 spec with operations section`() {
        val report = CentralContractRepoReport().generate("./specifications/asyncapi3spec")
        assertThat(osAgnosticPaths(report)).isEqualTo(
            osAgnosticPaths(
                CentralContractRepoReportJson(
                    listOf(
                        SpecificationRow(
                            "specifications/asyncapi3spec/asyncapi3spec.yaml",
                            "ASYNCAPI",
                            "ASYNCAPI",
                            listOf(
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "placeOrder",
                                    channel = "NewOrderPlaced",
                                    action = "receive"
                                ),
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "placeOrder",
                                    channel = "OrderInitiated",
                                    action = "send"
                                ),
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "cancelOrder",
                                    channel = "OrderCancellationRequested",
                                    action = "receive"
                                ),
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "cancelOrder",
                                    channel = "OrderCancelled",
                                    action = "send"
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `test generates report based on asyncapi 2 6 0 spec with publish and subscribe operations`() {
        val report = CentralContractRepoReport().generate("./specifications/asyncapi2spec")
        assertThat(osAgnosticPaths(report)).isEqualTo(
            osAgnosticPaths(
                CentralContractRepoReportJson(
                    listOf(
                        SpecificationRow(
                            "specifications/asyncapi2spec/asyncapi2spec.yaml",
                            "ASYNCAPI",
                            "ASYNCAPI",
                            listOf(
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "placeOrder",
                                    channel = "place-order",
                                    action = "receive"
                                ),
                                io.specmatic.reports.AsyncAPISpecificationOperation(
                                    operation = "processOrder",
                                    channel = "process-order",
                                    action = "send"
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private fun osAgnosticPaths(report: CentralContractRepoReportJson): CentralContractRepoReportJson {
        return report.copy(
            specifications = report.specifications.map {
                it.copy(
                    specification = osAgnosticPath(it.specification)
                )
            }
        )
    }

    companion object {

        @JvmStatic
        @BeforeAll
        fun setupBeforeAll() {
            createSpecFiles()
        }

        @JvmStatic
        @AfterAll
        fun tearDownAfterAll() {
            File("./specifications").deleteRecursively()
        }

        private fun createSpecFiles() {
            val service1spec = """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://api.example.com/v1
    description: Optional server description, e.g. Main (production) server
  - url: http://staging-api.example.com
    description: Optional server description, e.g. Internal staging server for testing
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: path
          name: id
          schema:
            type: integer
          required: true
          description: Numeric ID
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                type: string
        '400':
          description: Bad Request
          content:
            application/json:
              schema:
                type: string
        """
            val service1File = File("./specifications/service1/service1.yaml")
            service1File.parentFile.mkdirs()
            service1File.writeText(service1spec)


            val service2spec= """
openapi: 3.0.0
info:
  title: Order API
  version: '1.0'
servers:
  - url: 'http://localhost:3000'
paths:
  '/products/{id}':
    parameters:
      - schema:
          type: number
        name: id
        in: path
        required: true
        examples:
          GET_DETAILS_10:
            value: 10
          GET_DETAILS_20:
            value: 20
    get:
      summary: Fetch product details
      tags: []
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: './common.yaml#/components/schemas/Product'
              examples:
                GET_DETAILS_10:
                  value:
                    name: 'XYZ Phone'
                    type: 'gadget'
                    inventory: 10
                    id: 10
                GET_DETAILS_20:
                  value:
                     name: 'Macbook'
                     type: 'gadget'
                     inventory: 10
                     id: 20
            """.trimIndent()

            val service2File = File("./specifications/service2/service2.yaml")
            service2File.parentFile.mkdirs()
            service2File.writeText(service2spec)

            val commonSpec = """
            openapi: 3.0.0
            info:
              title: Common schema
              version: '1.0'
            paths: {}
            components:
              schemas:
                ProductDetails:
                  title: Product Details
                  type: object
                  properties:
                    name:
                      type: string
                    type:
                      ${'$'}ref: '#/components/schemas/ProductType'
                    inventory:
                      type: integer
                  required:
                    - name
                    - type
                    - inventory
                ProductType:
                  type: string
                  title: Product Type
                  enum:
                    - book
                    - food
                    - gadget
                    - other
                ProductId:
                  title: Product Id
                  type: object
                  properties:
                    id:
                      type: integer
                  required:
                    - id
                Product:
                  title: Product
                  allOf:
                    - ${'$'}ref: '#/components/schemas/ProductId'
                    - ${'$'}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

            val commonFile = File("./specifications/service2/common.yaml")
            commonFile.writeText(commonSpec)

            createAsyncAPI3_0_0Spec()
            createAsyncAPI2_6_0Spec()
        }

        private fun createAsyncAPI3_0_0Spec() {
            val asyncapi3Spec = """
                        asyncapi: 3.0.0
                        info:
                          title: Order API
                          version: 1.0.0
                        channels:
                          NewOrderPlaced:
                            address: new-orders
                            messages:
                              placeOrder.message:
                                ${'$'}ref: '#/components/messages/OrderRequest'
                          OrderInitiated:
                            address: wip-orders
                            messages:
                              processOrder.message:
                                ${'$'}ref: '#/components/messages/Order'
                          OrderCancellationRequested:
                            address: to-be-cancelled-orders
                            messages:
                              cancelOrder.message:
                                ${'$'}ref: '#/components/messages/CancelOrderRequest'
                          OrderCancelled:
                            address: cancelled-orders
                            messages:
                              processCancellation.message:
                                ${'$'}ref: '#/components/messages/CancellationReference'
                        operations:
                          placeOrder:
                            action: receive
                            channel:
                              ${'$'}ref: '#/channels/NewOrderPlaced'
                            messages:
                              - ${'$'}ref: '#/channels/NewOrderPlaced/messages/placeOrder.message'
                            reply:
                              channel:
                                ${'$'}ref: '#/channels/OrderInitiated'
                              messages:
                                - ${'$'}ref: '#/channels/OrderInitiated/messages/processOrder.message'
                          cancelOrder:
                            action: receive
                            channel:
                              ${'$'}ref: '#/channels/OrderCancellationRequested'
                            messages:
                              - ${'$'}ref: '#/channels/OrderCancellationRequested/messages/cancelOrder.message'
                            reply:
                              channel:
                                ${'$'}ref: '#/channels/OrderCancelled'
                              messages:
                                - ${'$'}ref: '#/channels/OrderCancelled/messages/processCancellation.message'
                        """
            val asyncapi3File = File("./specifications/asyncapi3spec/asyncapi3spec.yaml")
            asyncapi3File.parentFile.mkdirs()
            asyncapi3File.writeText(asyncapi3Spec)
        }

        private fun createAsyncAPI2_6_0Spec() {
            val asyncapi2Spec = """
                asyncapi: 2.6.0
                info:
                  title: Order API
                  version: '1.0.0'
                channels:
                  place-order:
                    publish:
                      operationId: placeOrder
                      message:
                        ${'$'}ref: "#/components/messages/OrderRequest"
                  process-order:
                    subscribe:
                      operationId: processOrder
                      message:
                        ${'$'}ref: "#/components/messages/Order"
                        """
            val asyncapi2File = File("./specifications/asyncapi2spec/asyncapi2spec.yaml")
            asyncapi2File.parentFile.mkdirs()
            asyncapi2File.writeText(asyncapi2Spec)
        }
    }
}