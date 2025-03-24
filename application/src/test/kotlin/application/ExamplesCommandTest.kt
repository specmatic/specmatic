package application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class ExamplesCommandTest {

    @Test
    fun `examples validate command should not print an empty error when it sees an inline example for a filtered-out scenario`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("spec.yaml")
        val examplesDir = tempDir.resolve("spec_examples")

        specFile.createNewFile()
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 1.0.0
paths:
  /product/{id}:
    get:
      summary: Get product details
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: Product details
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
                  price:
                    type: number
                    format: float
        """.trimIndent()
        specFile.writeText(spec)

        examplesDir.mkdirs()
        val example = """
{
  "http-request": {
    "method": "GET",
    "path": "/products/1"
  },
  "http-response": {
    "status": 200,
    "body": {
      "id": 1,
      "name": "Laptop",
      "price": 1000.99
    },
    "headers": {
      "Content-Type": "application/json"
    }
  }
}
        """.trimIndent()

        val exampleFile = examplesDir.resolve("example.json")
        exampleFile.writeText(example)

        val command = ExamplesCommand.Validate().also {
            it.contractFile = specFile
        }

        val (output, returnValue: Int) = captureStandardOutput {
            command.call()
        }

        println(output)

        assertThat(returnValue).isNotEqualTo(0)
        assertThat(output).contains("No matching specification found for this example")
    }

    @Test
    fun `should display an error message for an invalid example`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("spec.yaml")
        val examplesDir = tempDir.resolve("spec_examples")

        specFile.createNewFile()
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 1.0.0
paths:
  /product/{id}:
    get:
      summary: Get product details
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: Product details
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
                  price:
                    type: number
                    format: float
        """.trimIndent()
        specFile.writeText(spec)

        examplesDir.mkdirs()
        val example = """
{
  "http-request": {
    "method": "GET",
    "path": "/product/abc123"
  },
  "http-response": {
    "status": 200,
    "body": {
      "id": 1,
      "name": "Laptop",
      "price": 1000.99
    },
    "headers": {
      "Content-Type": "application/json"
    }
  }
}
        """.trimIndent()

        val exampleFile = examplesDir.resolve("example.json")
        exampleFile.writeText(example)

        val command = ExamplesCommand.Validate().also {
            it.contractFile = specFile
        }

        val (output, returnValue: Int) = captureStandardOutput {
            command.call()
        }

        println(output)

        assertThat(returnValue).isNotEqualTo(0)
        assertThat(output).contains("""expected number but example contained""")
    }

    @Test
    fun `should not display an error message when all examples are valid`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("spec.yaml")
        val examplesDir = tempDir.resolve("spec_examples")

        specFile.createNewFile()
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 1.0.0
paths:
  /product/{id}:
    get:
      summary: Get product details
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: Product details
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
                  price:
                    type: number
                    format: float
        """.trimIndent()
        specFile.writeText(spec)

        examplesDir.mkdirs()
        val example = """
{
  "http-request": {
    "method": "GET",
    "path": "/product/1"
  },
  "http-response": {
    "status": 200,
    "body": {
      "id": 1,
      "name": "Laptop",
      "price": 1000.99
    },
    "headers": {
      "Content-Type": "application/json"
    }
  }
}
        """.trimIndent()

        val exampleFile = examplesDir.resolve("example.json")
        exampleFile.writeText(example)

        val command = ExamplesCommand.Validate().also {
            it.contractFile = specFile
        }

        val (output, returnValue: Int) = captureStandardOutput {
            command.call()
        }

        println(output)

        assertThat(returnValue).isEqualTo(0)
        assertThat(output).contains("are valid")
    }

    @Nested
    inner class ValidateTests {
        private val specFile = File("src/test/resources/specifications/simpleSpec/spec.yaml")

        @Test
        fun `should validate both inline and external examples by default`() {
            val command = ExamplesCommand.Validate().also { it.contractFile = specFile }
            val (stdOut, exitCode) = captureStandardOutput { command.call() }
            println(stdOut)

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces("""
            =============== Inline Example Validation Summary ===============
            All 1 example(s) are valid.
            =================================================================  
            """.trimIndent())
            assertThat(stdOut).containsIgnoringWhitespaces("""
            =============== Example File Validation Summary ===============
            All 1 example(s) are valid.
            ===============================================================
            """.trimIndent())
        }

        @Test
        fun `should validate inline only when the examplesToValidate flag is set to inline`() {
            val command = ExamplesCommand.Validate().also {
                it.contractFile = specFile
                it.examplesToValidate = ExamplesCommand.Validate.ExamplesToValidate.INLINE
            }
            val (stdOut, exitCode) = captureStandardOutput { command.call() }
            println(stdOut)

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces("""
            =============== Inline Example Validation Summary ===============
            All 1 example(s) are valid.
            =================================================================
            """.trimIndent())
            assertThat(stdOut).doesNotContain("""
            =============== Example File Validation Summary ===============
            """.trimIndent())
        }

        @Test
        fun `should validate external only when the examplesToValidate flag is set to external`() {
            val command = ExamplesCommand.Validate().also {
                it.contractFile = specFile
                it.examplesToValidate = ExamplesCommand.Validate.ExamplesToValidate.EXTERNAL
            }
            val (stdOut, exitCode) = captureStandardOutput { command.call() }
            println(stdOut)

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces("""
            =============== Example File Validation Summary ===============
            All 1 example(s) are valid.
            ===============================================================
            """.trimIndent())
            assertThat(stdOut).doesNotContain("""
            =============== Inline Example Validation Summary =============
            """.trimIndent())
        }

        @ParameterizedTest
        @CsvSource(
            "inline, INLINE, InLine, INLINE",
            "external, EXTERNAL, External, EXTERNAL",
            "both, BOTH, Both, BOTH"
        )
        fun `should convert to examplesToValidate enum ignoring case`(
            lowerCase: String, upperCase: String, titleCase: String, expected: String
        ) {
            val cases = listOf(lowerCase, upperCase, titleCase)
            assertThat(cases).allSatisfy {
                val examplesToValidate = ExamplesCommand.Validate.ExamplesToValidateConverter().convert(it)
                println("$it -> $examplesToValidate")
                assertThat(examplesToValidate.name).isEqualTo(expected)
            }
        }

        @Test
        fun `should not result in exit code 1 when example folder is empty`(@TempDir tempDir: File) {
            val copiedSpecFile = specFile.copyTo(tempDir.resolve("spec.yaml"))
            val examplesDir = tempDir.resolve("spec_examples").also { it.mkdirs() }

            val command = ExamplesCommand.Validate().also {
                it.contractFile = copiedSpecFile
                it.examplesToValidate = ExamplesCommand.Validate.ExamplesToValidate.EXTERNAL
            }
            val (stdOut, exitCode) = captureStandardOutput { command.call() }
            println(stdOut)

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces("""
            No example files found in $examplesDir
            """.trimIndent())
        }
    }
}