package io.specmatic.conversions.links

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.stub.captureStandardOutput
import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.File

class OpenApiLinksRepositoryTest {
    @Test
    fun `should be able to parse valid openApiLinks from OpenAPI Specification and convert to feature`() {
        val specFile = File("src/test/resources/links/valid_links_spec/links.yaml")
        val openApiSpecification = assertDoesNotThrow {
            OpenApiSpecification.fromFile(specFile.canonicalPath)
        }

        assertDoesNotThrow { openApiSpecification.toFeature() }
        assertThat(openApiSpecification.openApiLinksRepository.size).isEqualTo(7)
    }

    @Test
    fun `In Lenient mode should log the failures from link extraction and filter them out`() {
        val specFile = File("src/test/resources/links/invalid_links/links.yaml")
        val openApi = specFile.toOpenApiPOJO()
        val (stdOut, linksRepository) = captureStandardOutput {
            OpenApiLinksRepository.from(openApi, specFile.canonicalPath)
        }

        assertThat(linksRepository).isInstanceOf(HasValue::class.java); linksRepository as HasValue
        assertThat(linksRepository.value.size).isEqualTo(3)
        assertThat(stdOut).containsIgnoringWhitespaces("""
        >> LINKS.getProductByIdDoesNotExist.extensions.x-StatusCode
        Invalid OpenApi Link getProductByIdDoesNotExist
        Invalid status Code for x-StatusCode '429' is not possible
        Must be one of 200, 404, 400

        >> LINKS.listProducts.operationId
        Invalid OpenApi Link listProducts
        No Operation Found with operationId 'getProductsTypo' in the specification
        
        >> LINKS.updateProduct.extensions.x-Partial
        Invalid OpenApi Link updateProduct
        Invalid Is-Partial value 'shouldBeABoolean' must be a valid boolean
        
        >> LINKS.deleteProductByIdDoesNotExist.extensions.x-StatusCode
        Invalid OpenApi Link deleteProductByIdDoesNotExist
        Invalid Expected Status Code 'SHOULD_BE_INTEGER_OR_DEFAULT' must be a valid integer or 'default'
        """.trimIndent())
    }

    @Test
    fun `In Non-Lenient mode should return an failure when there's an invalid link involved`() {
        val specFile = File("src/test/resources/links/invalid_links/links.yaml")
        val openApi = specFile.toOpenApiPOJO()
        val linksRepository = OpenApiLinksRepository.from(openApi, specFile.canonicalPath, lenient = false)

        assertThat(linksRepository).isInstanceOf(HasFailure::class.java); linksRepository as HasFailure
        assertThat(linksRepository.toFailure().reportString()).isEqualToIgnoringWhitespace("""
        >> LINKS.getProductByIdDoesNotExist.extensions.x-StatusCode
        Invalid OpenApi Link getProductByIdDoesNotExist
        Invalid status Code for x-StatusCode '429' is not possible
        Must be one of 200, 404, 400

        >> LINKS.listProducts.operationId
        Invalid OpenApi Link listProducts
        No Operation Found with operationId 'getProductsTypo' in the specification
        
        >> LINKS.updateProduct.extensions.x-Partial
        Invalid OpenApi Link updateProduct
        Invalid Is-Partial value 'shouldBeABoolean' must be a valid boolean
        
        >> LINKS.deleteProductByIdDoesNotExist.extensions.x-StatusCode
        Invalid OpenApi Link deleteProductByIdDoesNotExist
        Invalid Expected Status Code 'SHOULD_BE_INTEGER_OR_DEFAULT' must be a valid integer or 'default'
        """.trimIndent())
    }

    @Test
    fun `In Lenient mode should log links to example conversion failures and filter them out`() {
        val specFile = File("src/test/resources/links/invalid_links/links.yaml")
        val openApiSpecification = OpenApiSpecification.fromFile(specFile.canonicalPath)
        val (stdout, _) = captureStandardOutput { assertDoesNotThrow { openApiSpecification.toFeature() } }

        assertThat(stdout).containsIgnoringWhitespaces("""
        >> LINKS.getProduct.parameters.id
        Invalid Request in OpenApi Link getProduct
        Expected mandatory path parameter 'id' is missing from link parameters

        >> LINKS.updateProductByIdDoesNotExist.parameters.id
        Invalid Request in OpenApi Link updateProductByIdDoesNotExist
        Expected mandatory path parameter 'id' is missing from link parameters

        >> LINKS.deleteProduct.parameters.id
        Invalid Request in OpenApi Link deleteProduct
        Expected mandatory path parameter 'id' is missing from link parameters
        """.trimIndent())
    }

    @Test
    fun `In Non-Lenient mode should throw an contract exception if any of the links can't be converted to examples`() {
        val specFile = File("src/test/resources/links/invalid_links/links.yaml")
        val openApiSpecification = OpenApiSpecification.fromYAML(
            yamlContent = specFile.readText(),
            openApiFilePath = specFile.canonicalPath,
            strictMode = false,
        )

        val exception = assertThrows<ContractException> {
            val field = openApiSpecification::class.java.getDeclaredField("strictMode")
            field.isAccessible = true
            field.set(openApiSpecification, true)
            openApiSpecification.toFeature()
        }

        assertThat(exception.report()).isEqualToIgnoringWhitespace("""
        >> LINKS.getProduct.parameters.id
        Invalid Request in OpenApi Link getProduct
        Expected mandatory path parameter 'id' is missing from link parameters

        >> LINKS.updateProductByIdDoesNotExist.parameters.id
        Invalid Request in OpenApi Link updateProductByIdDoesNotExist
        Expected mandatory path parameter 'id' is missing from link parameters

        >> LINKS.deleteProduct.parameters.id
        Invalid Request in OpenApi Link deleteProduct
        Expected mandatory path parameter 'id' is missing from link parameters
        """.trimIndent())
    }

    companion object {
        private fun File.toOpenApiPOJO(): OpenAPI = OpenApiSpecification.getParsedOpenApi(this.canonicalPath)
    }
}
