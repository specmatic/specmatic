package application

import java.io.File

internal data class ParameterizedMediaTypeExampleFixture(
    val specFile: File,
    val examplesDir: File,
    val exampleFile: File
)

internal fun writeParameterizedMediaTypeExampleFixture(
    baseDir: File,
    examplesDirName: String = "orders_examples"
): ParameterizedMediaTypeExampleFixture {
    val specFile = baseDir.resolve("orders.yaml")
    specFile.writeText(
        """
        openapi: 3.0.1
        info:
          title: Orders API
          version: "1"
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  'application/json; charset=utf-8':
                    schema:
                      type: object
                      required:
                        - id
                      properties:
                        id:
                          type: integer
              responses:
                '201':
                  description: Created
                  content:
                    'application/json; charset=utf-8':
                      schema:
                        type: object
                        required:
                          - id
                        properties:
                          id:
                            type: integer
        """.trimIndent()
    )

    val examplesDir = baseDir.resolve(examplesDirName).also { it.mkdirs() }
    val exampleFile = examplesDir.resolve("create_order.json")
    exampleFile.writeText(
        """
        {
          "http-request": {
            "method": "POST",
            "path": "/orders",
            "headers": {
              "Content-Type": "application/json; charset=utf-8"
            },
            "body": {
              "id": 10
            }
          },
          "http-response": {
            "status": 201,
            "headers": {
              "Content-Type": "application/json; charset=utf-8"
            },
            "body": {
              "id": 10
            }
          }
        }
        """.trimIndent()
    )

    return ParameterizedMediaTypeExampleFixture(specFile, examplesDir, exampleFile)
}
