package io.specmatic.contract.utilities

import io.specmatic.core.utilities.URIUtils.parsePathParams
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URISyntaxException

internal class URIUtilsTest {
    @Test
    @Throws(URISyntaxException::class)
    fun shouldExtractPathParam() {
        val uri = URI("/pets/(petid:number)")
        val pathParameters = parsePathParams(uri.rawPath)
        Assertions.assertEquals(pathParameters["petid"], "(number)")
    }

    @Test
    @Throws(URISyntaxException::class)
    fun shouldExtractMultiplePathParam() {
        val uri = URI("/pets/(petid:number)/owners/(owner:string)")
        val pathParameters = parsePathParams(uri.rawPath)
        Assertions.assertEquals(pathParameters["petid"], "(number)")
        Assertions.assertEquals(pathParameters["owner"], "(string)")
    }
}
