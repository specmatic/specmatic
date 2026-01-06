package io.specmatic.proxy

import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.SET_COOKIE_SEPARATOR
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ValueTransformerTest {
    @Nested
    inner class GeneralizeToTypeTest {
        private val transformer = ValueTransformer.GeneralizeToType()

        @Test
        fun `should return null when value is null`() {
            val result = transformer.transform(null)
            assertThat(result).isNull()
        }

        @Test
        fun `should generalize scalar value to its type name`() {
            val value = NumberValue(10)
            val result = transformer.transform(value)
            assertThat(result)
                .isInstanceOf(StringValue::class.java)
                .extracting { (it as StringValue).nativeValue }
                .isEqualTo("(number)")
        }

        @Test
        fun `should generalize non scalar value to anyvalue`() {
            val value = JSONObjectValue(mapOf("key" to StringValue("value")))
            val result = transformer.transform(value)
            assertThat(result)
                .isInstanceOf(StringValue::class.java)
                .extracting { (it as StringValue).nativeValue }
                .isEqualTo("(anyvalue)")
        }
    }

    @Nested
    inner class CookieExpiryTransformerTest {
        private val transformer = ValueTransformer.CookieExpiryTransformer

        @Test
        fun `should return value unchanged when not a StringValue`() {
            val value = NumberValue(5)
            val result = transformer.transform(value)
            assertThat(result).isSameAs(value)
        }

        @Test
        fun `should remove expires and max-age attributes from cookie`() {
            val cookie = "SESSION=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/; Max-Age=3600"
            val value = StringValue(cookie)
            val result = transformer.transform(value) as StringValue
            assertThat(result.nativeValue)
                .doesNotContain("expires=")
                .doesNotContain("max-age=")
                .contains("SESSION=abc")
                .contains("Path=/")
        }

        @Test
        fun `should handle multiple cookies separated by null character`() {
            val cookies = "A=1; Max-Age=10${SET_COOKIE_SEPARATOR}B=2; Expires=Wed, 21 Oct 2015 07:28:00 GMT"
            val value = StringValue(cookies)
            val result = transformer.transform(value) as StringValue
            assertThat(result.nativeValue).isEqualTo("A=1${SET_COOKIE_SEPARATOR}B=2")
        }
    }
}
