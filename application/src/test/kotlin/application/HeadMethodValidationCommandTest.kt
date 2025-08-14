package application

import application.ExamplesCommand
import com.ginsberg.junit.exit.ExpectSystemExitWithStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File

class HeadMethodValidationCommandTest {

    @TempDir
    lateinit var tempDir: File
    
    private lateinit var specFile: File

    @BeforeEach
    fun setup() {
        specFile = tempDir.resolve("head-api.yaml")
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    fun `validation should succeed for HEAD method with valid internal example`() {
        // Create simple OpenAPI spec with valid internal example for HEAD method
        specFile.writeText("""
            openapi: 3.0.0
            info:
              title: HEAD Method API
              version: 1.0.0
            paths:
              /resource:
                head:
                  summary: Check resource
                  responses:
                    200:
                      description: Resource exists
                      headers:
                        Content-Type:
                          schema:
                            type: string
                          examples:
                            success_case:
                              value: "application/json"
        """.trimIndent())

        val command = ExamplesCommand.Validate()
        command.contractFile = specFile
        
        CommandLine(command).execute()
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    fun `validation should fail for HEAD method with invalid internal example`() {
        // Create OpenAPI spec with invalid internal example for HEAD method
        specFile.writeText("""
            openapi: 3.0.0
            info:
              title: HEAD Method API Invalid
              version: 1.0.0
            paths:
              /resource:
                head:
                  summary: Check resource
                  parameters:
                    - name: invalidParam
                      in: query
                      required: true
                      schema:
                        type: integer
                      examples:
                        invalid_case:
                          value: "not_a_number"  # Invalid: string instead of integer
                  responses:
                    200:
                      description: Resource exists
        """.trimIndent())

        val command = ExamplesCommand.Validate()
        command.contractFile = specFile
        
        CommandLine(command).execute()
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    fun `validation should succeed for HEAD method with valid external example`() {
        // Create OpenAPI spec with external example reference for HEAD method
        specFile.writeText("""
            openapi: 3.0.0
            info:
              title: HEAD Method API External
              version: 1.0.0
            paths:
              /resource:
                head:
                  summary: Check resource
                  responses:
                    200:
                      description: Resource exists
                      headers:
                        Content-Type:
                          schema:
                            type: string
                          examples:
                            success_case:
                              ${'$'}ref: '#/components/examples/ValidContentType'
            components:
              examples:
                ValidContentType:
                  value: "application/json"
        """.trimIndent())
        
        val command = ExamplesCommand.Validate()
        command.contractFile = specFile
        
        CommandLine(command).execute()
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    fun `validation should fail for HEAD method with invalid external example`() {
        // Create OpenAPI spec with invalid external example reference for HEAD method  
        specFile.writeText("""
            openapi: 3.0.0
            info:
              title: HEAD Method API External Invalid
              version: 1.0.0
            paths:
              /resource:
                head:
                  summary: Check resource
                  parameters:
                    - name: invalidParam
                      in: query
                      required: true
                      schema:
                        type: integer
                      examples:
                        invalid_case:
                          ${'$'}ref: '#/components/examples/InvalidParam'
                  responses:
                    200:
                      description: Resource exists
            components:
              examples:
                InvalidParam:
                  value: "not_a_number"  # Invalid: string instead of integer
        """.trimIndent())
        
        val command = ExamplesCommand.Validate()
        command.contractFile = specFile
        
        CommandLine(command).execute()
    }
}