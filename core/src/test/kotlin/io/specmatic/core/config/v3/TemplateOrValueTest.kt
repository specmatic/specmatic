package io.specmatic.core.config.v3

import io.specmatic.core.config.ConfigTemplateUtils
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.utilities.yamlMapper
import com.fasterxml.jackson.core.type.TypeReference
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.utilities.Flags
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TemplateOrValueTest {
    data class HolderAny(val item: TemplateOrValue<Any>)
    data class HolderInt(val item: TemplateOrValue<Int>)
    data class Composite(val name: String, val count: Int)
    data class HolderString(val item: TemplateOrValue<String>)
    data class HolderObject(val item: TemplateOrValue<Composite>)
    data class HolderList(val item: TemplateOrValue<List<String>>)
    data class NestedListHolder(val item: TemplateOrValue<List<TemplateOrValue<String>>>)
    data class HolderMap(val item: TemplateOrValue<Map<String, TemplateOrValue<String>>>)
    data class HolderRef(val item: TemplateOrValue<RefOrValue<String>>)
    data class HolderRefList(val item: TemplateOrValue<List<TemplateOrValue<RefOrValue<String>>>>)
    data class HolderRefMap(val item: TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<String>>>>)
    data class NestedHolder<T : Any>(val item: TemplateOrValue<RefOrValue<T>>)
    data class ConfigLike(val title: TemplateOrValue<String>, val payload: TemplateOrValue<Any>)

    @Test
    fun `deserialize template into Template`() {
        val holder = yamlMapper.readValue("item: '{HOST:localhost}'", HolderString::class.java)
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((holder.item as TemplateOrValue.Template).template).isEqualTo("{HOST:localhost}")
    }

    @Test
    fun `deserialize plain value into Value`() {
        val holder = yamlMapper.readValue("item: api.example.com", HolderString::class.java)
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isEqualTo("api.example.com")
    }

    @Test
    fun `resolve helpers handle null input`() {
        assertThat((null as String?).wrapOrNull()).isNull()
        assertThat("api.example.com".wrap()).isEqualTo(TemplateOrValue.Value("api.example.com"))
        assertThat(listOf("a", "b").wrapFully()).isEqualTo(
            TemplateOrValue.Value(listOf(TemplateOrValue.Value("a"), TemplateOrValue.Value("b")))
        )
        assertThat((null as List<String>?).wrapFullyOrNull()).isNull()
        assertThat(mapOf("one" to "1").wrapValuesFully()).isEqualTo(
            TemplateOrValue.Value(mapOf("one" to TemplateOrValue.Value("1")))
        )
        assertThat((null as Map<String, String>?).wrapValuesFullyOrNull()).isNull()

        assertThat((null as TemplateOrValue<String>?).resolveOrNull()).isNull()
        assertThat((null as TemplateOrValue<String>?).resolveOrDefault("fallback")).isEqualTo("fallback")
        assertThat((null as TemplateOrValue<List<TemplateOrValue<String>>>?).resolveFullyOrEmpty()).isEmpty()
        assertThat((null as TemplateOrValue<List<TemplateOrValue<String>>>?).resolveFullyOrNull()).isNull()
        assertThat((null as TemplateOrValue<Map<String, TemplateOrValue<String>>>?).resolveMapValuesOrEmpty()).isEmpty()
        assertThat((null as TemplateOrValue<Map<String, TemplateOrValue<String>>>?).resolveMapValuesOrNull()).isNull()

        val resolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Any = error("not used")
        }
        assertThat(RefOrValue.Value("direct").wrapRef()).isEqualTo(TemplateOrValue.Value(RefOrValue.Value("direct")))
        assertThat((null as RefOrValue<String>?).wrapRefOrNull()).isNull()
        assertThat(listOf(RefOrValue.Value("one"), RefOrValue.Reference("#/components/values/two")).wrapRefListFully()).isEqualTo(
            TemplateOrValue.Value(
                listOf(
                    TemplateOrValue.Value(RefOrValue.Value("one")),
                    TemplateOrValue.Value(RefOrValue.Reference("#/components/values/two"))
                )
            )
        )
        assertThat((null as List<RefOrValue<String>>?).wrapRefListFullyOrNull()).isNull()
        assertThat(mapOf("one" to RefOrValue.Value("1")).wrapRefMapValuesFully()).isEqualTo(
            TemplateOrValue.Value(mapOf("one" to TemplateOrValue.Value(RefOrValue.Value("1"))))
        )
        assertThat((null as Map<String, RefOrValue<String>>?).wrapRefMapValuesFullyOrNull()).isNull()

        assertThat((null as TemplateOrValue<RefOrValue<String>>?).resolveRefOrNull(resolver)).isNull()
        assertThat((null as TemplateOrValue<List<TemplateOrValue<RefOrValue<String>>>>?).resolveRefListOrEmpty(resolver)).isEmpty()
        assertThat((null as TemplateOrValue<List<TemplateOrValue<RefOrValue<String>>>>?).resolveRefListOrNull(resolver)).isNull()
        assertThat((null as TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<String>>>>?).resolveRefMapValuesOrEmpty(resolver)).isEmpty()
        assertThat((null as TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<String>>>>?).resolveRefMapValuesOrNull(resolver)).isNull()
    }

    @Test
    fun `resolve helpers handle TemplateOrValue value scalar`() {
        val value: TemplateOrValue<String>? = TemplateOrValue.Value("api.example.com")
        assertThat(value.resolveOrNull()).isEqualTo("api.example.com")
        assertThat(value.resolveOrDefault("fallback")).isEqualTo("api.example.com")
    }

    @Test
    fun `resolve helpers handle TemplateOrValue value list and map`() {
        val list: TemplateOrValue<List<TemplateOrValue<String>>>? = TemplateOrValue.Value(
            listOf(TemplateOrValue.Value("first"), TemplateOrValue.Value("second"))
        )
        assertThat(list.resolveFullyOrEmpty()).containsExactly("first", "second")
        assertThat(list.resolveFullyOrNull()).containsExactly("first", "second")

        val map: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = TemplateOrValue.Value(
            mapOf("one" to TemplateOrValue.Value("1"), "two" to TemplateOrValue.Value("2"))
        )
        assertThat(map.resolveMapValuesOrEmpty()).containsEntry("one", "1").containsEntry("two", "2")
        assertThat(map.resolveMapValuesOrNull()).containsEntry("one", "1").containsEntry("two", "2")
    }

    @Test
    fun `resolve helpers handle nested TemplateOrValue list values`() {
        val holder = yamlMapper.readValue(
            """
            item:
              - first
              - '{SECOND:second}'
            """.trimIndent(),
            NestedListHolder::class.java
        )

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        val rawList = holder.item.getUnsafe()
        assertThat(rawList[0]).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(rawList[1]).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat(holder.item.resolveFullyOrEmpty()).containsExactly("first", "second")
    }

    @Test
    fun `resolve helpers handle TemplateOrValue value ref scalar list and map`() {
        val resolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Any = when (reference) {
                "#/components/values/example" -> "resolved-from-ref"
                "#/components/values/list-one" -> "resolved-list-one"
                "#/components/values/map-one" -> "resolved-map-one"
                else -> error("unexpected ref: $reference")
            }
        }

        val scalar: TemplateOrValue<RefOrValue<String>>? = TemplateOrValue.Value(RefOrValue.Value("direct"))
        assertThat(scalar.resolveRefOrNull(resolver)).isEqualTo("direct")

        val refScalar: TemplateOrValue<RefOrValue<String>>? = TemplateOrValue.Value(RefOrValue.Reference("#/components/values/example"))
        assertThat(refScalar.resolveRefOrNull(resolver)).isEqualTo("resolved-from-ref")

        val list: TemplateOrValue<List<TemplateOrValue<RefOrValue<String>>>>? = TemplateOrValue.Value(
            listOf(
                TemplateOrValue.Value(RefOrValue.Value("direct-list")),
                TemplateOrValue.Value(RefOrValue.Reference("#/components/values/list-one"))
            )
        )
        assertThat(list.resolveRefListOrEmpty(resolver)).containsExactly("direct-list", "resolved-list-one")
        assertThat(list.resolveRefListOrNull(resolver)).containsExactly("direct-list", "resolved-list-one")

        val map: TemplateOrValue<Map<String, TemplateOrValue<RefOrValue<String>>>>? = TemplateOrValue.Value(
            mapOf(
                "direct" to TemplateOrValue.Value(RefOrValue.Value("direct-map")),
                "ref" to TemplateOrValue.Value(RefOrValue.Reference("#/components/values/map-one"))
            )
        )
        assertThat(map.resolveRefMapValuesOrEmpty(resolver)).containsEntry("direct", "direct-map").containsEntry("ref", "resolved-map-one")
        assertThat(map.resolveRefMapValuesOrNull(resolver)).containsEntry("direct", "direct-map").containsEntry("ref", "resolved-map-one")
    }

    @Test
    fun `templates remain raw until helper called`() {
        val template = yamlMapper.readValue("item: '{HOST:localhost}'", HolderString::class.java).item

        assertThat(template).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((template as TemplateOrValue.Template).template).isEqualTo("{HOST:localhost}")
        assertThat(template.resolveOrNull()).isEqualTo("localhost")
    }

    @Test
    fun `serialize and deserialize template round trip`() {
        val original = HolderString(TemplateOrValue.Template("{HOST:localhost}"))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, HolderString::class.java)

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((roundTripped.item as TemplateOrValue.Template).template).isEqualTo("{HOST:localhost}")
    }

    @Test
    fun `serialize and deserialize value round trip`() {
        val original = HolderString(TemplateOrValue.Value("api.example.com"))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, HolderString::class.java)

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(roundTripped.item.getUnsafe()).isEqualTo("api.example.com")
    }

    @Test
    fun `deserialize interpolated template into Template`() {
        val holder = yamlMapper.readValue("item: 'pre-{HOST:localhost}-post'", HolderString::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((holder.item as TemplateOrValue.Template).template).isEqualTo("pre-{HOST:localhost}-post")
    }

    @Test
    fun `serialize and deserialize interpolated template round trip`() {
        val original = HolderString(TemplateOrValue.Template("pre-{HOST:localhost}-post"))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, HolderString::class.java)

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((roundTripped.item as TemplateOrValue.Template).template).isEqualTo("pre-{HOST:localhost}-post")
    }

    @Test
    fun `resolve uses injected lookup and template default`() {
        val template = "{HOST:localhost}"
        Flags.using("HOST" to "from-sys-prop") {
            assertThat(ConfigTemplateUtils.resolveTemplateValue(template)).isEqualTo("from-sys-prop")
        }

        assertThat(ConfigTemplateUtils.resolveTemplateValue(template)).isEqualTo("localhost")
    }

    @Test
    fun `resolve interpolated template uses injected lookup and defaults`() {
        val template = "pre-{HOST:localhost}-post"
        Flags.using("HOST" to "from-sys-prop") {
            assertThat(ConfigTemplateUtils.resolveTemplateValue(template)).isEqualTo("pre-from-sys-prop-post")
        }

        assertThat(ConfigTemplateUtils.resolveTemplateValue(template)).isEqualTo("pre-localhost-post")
    }

    @Test
    fun `deserialize composite object into Value`() {
        val holder = yamlMapper.readValue("""
        item:
          name: alpha
          count: 2
        """.trimIndent(),
        HolderAny::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isInstanceOf(Map::class.java)
    }

    @Test
    fun `deserialize typed int into Value`() {
        val holder = yamlMapper.readValue("""
        item: 10
        """.trimIndent(), HolderInt::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isEqualTo(10)
    }

    @Test
    fun `deserialize typed list into Value`() {
        val holder = yamlMapper.readValue("""
        item:
          - a
          - b
        """.trimIndent(), HolderList::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `deserialize typed object into Value`() {
        val holder = yamlMapper.readValue("""
        item:
          name: alpha
          count: 2
        """.trimIndent(), HolderObject::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isEqualTo(Composite(name = "alpha", count = 2))
    }

    @Test
    fun `deserialize array into Value`() {
        val holder = yamlMapper.readValue("""
        item:
          - first
          - second
        """.trimIndent(),
        HolderAny::class.java)

        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(holder.item.getUnsafe()).isInstanceOf(List::class.java)
    }

    @Test
    fun `deserialize dataclass object into Value`() {
        val original = HolderAny(TemplateOrValue.Value(Composite(name = "alpha", count = 2)))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, HolderAny::class.java)

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(roundTripped.item.getUnsafe()).isInstanceOf(Map::class.java)
    }

    @Test
    fun `config like holder keeps template and object payload shapes`() {
        val holder = yamlMapper.readValue("""
        title: '{APP_TITLE:default-title}'
        payload:
          nested: value
        """.trimIndent(), ConfigLike::class.java)

        assertThat(holder.title).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat(holder.payload).isInstanceOf(TemplateOrValue.Value::class.java)
    }

    @Test
    fun `deserialize nested template of ref or value as outer Template`() {
        val yaml = """
        item: '{HOST:localhost}'
        """.trimIndent()

        val holder = yamlMapper.readValue(yaml, object : TypeReference<NestedHolder<String>>() {})
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((holder.item as TemplateOrValue.Template).template).isEqualTo("{HOST:localhost}")
    }

    @Test
    fun `deserialize nested ref or value reference as outer Value inner Reference`() {
        val yaml = $$"""
        item:
          $ref: "#/components/values/example"
        """.trimIndent()

        val holder = yamlMapper.readValue(yaml, object : TypeReference<NestedHolder<String>>() {})
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Value::class.java)

        val inner = holder.item.getUnsafe()
        assertThat(inner).isInstanceOf(RefOrValue.Reference::class.java)
        assertThat((inner as RefOrValue.Reference).ref).isEqualTo("#/components/values/example")
    }

    @Test
    fun `serialize and deserialize nested ref or value value round trip`() {
        val original = NestedHolder(TemplateOrValue.Value(RefOrValue.Value("api.example.com")))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, object : TypeReference<NestedHolder<String>>() {})

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(roundTripped.item.getUnsafe()).isInstanceOf(RefOrValue.Value::class.java)
        assertThat((roundTripped.item.getUnsafe() as RefOrValue.Value<String>).value).isEqualTo("api.example.com")
    }

    @Test
    fun `serialize and deserialize nested ref or value reference round trip`() {
        val original = NestedHolder(TemplateOrValue.Value(RefOrValue.Reference("#/components/values/example")))

        val yaml = yamlMapper.writeValueAsString(original)
        val roundTripped = yamlMapper.readValue(yaml, object : TypeReference<NestedHolder<String>>() {})

        assertThat(roundTripped.item).isInstanceOf(TemplateOrValue.Value::class.java)
        assertThat(roundTripped.item.getUnsafe()).isInstanceOf(RefOrValue.Reference::class.java)
        assertThat((roundTripped.item.getUnsafe() as RefOrValue.Reference).ref).isEqualTo("#/components/values/example")
    }

    @Test
    fun `resolve nested ref or value returns value as is when already Value`() {
        val value: TemplateOrValue<RefOrValue<String>> = TemplateOrValue.Value(RefOrValue.Value("api.example.com"))
        val resolved = value.resolve()

        assertThat(resolved).isInstanceOf(RefOrValue.Value::class.java)
        assertThat((resolved as RefOrValue.Value<String>).value).isEqualTo("api.example.com")
    }

    @Test
    fun `resolve nested ref or value resolves template then deserializes to RefOrValue`() {
        val template: TemplateOrValue<RefOrValue<String>> = TemplateOrValue.Template($$"{NESTED_REF_OR_VALUE:{\\\"$ref\\\":\\\"#/components/values/example\\\"}}")
        val resolved = Flags.using("NESTED_REF_OR_VALUE" to $$"""{"$ref":"#/components/values/from-env"}""") {
            template.resolve()
        }

        assertThat(resolved).isInstanceOf(RefOrValue.Reference::class.java)
        assertThat((resolved as RefOrValue.Reference).ref).isEqualTo("#/components/values/from-env")
    }

    @Test
    fun `invalid template-like text should throw`() {
        assertThatThrownBy { yamlMapper.readValue("item: '{HOST}'", HolderString::class.java) }
            .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException::class.java)
            .hasMessageContaining("Invalid template syntax")
    }

    @Test
    fun `template with multiple variable names is valid`() {
        val holder = yamlMapper.readValue("item: '{DB_HOST|HOST:localhost}'", HolderString::class.java)
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((holder.item as TemplateOrValue.Template).template).isEqualTo("{DB_HOST|HOST:localhost}")
    }

    @Test
    fun `template with multiple tokens is valid`() {
        val holder = yamlMapper.readValue("item: '{HOST:localhost}-{PORT:8080}'", HolderString::class.java)
        assertThat(holder.item).isInstanceOf(TemplateOrValue.Template::class.java)
        assertThat((holder.item as TemplateOrValue.Template).template).isEqualTo("{HOST:localhost}-{PORT:8080}")
    }

    @Test
    fun `resolveElseThrow should deserialize simple TemplateOrValue with RefOrValue`() {
        val refResolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Map<*, *> {
                return mapOf("hooks" to mapOf("beforeAll" to "echo from ref"))
            }
        }

        val template: TemplateOrValue<RefOrValue<Adapter>> = TemplateOrValue.Template("{HOOKS_BEFORE_ALL:{}}")
        val resolved = Flags.using("HOOKS_BEFORE_ALL" to """{"beforeAll":"echo from ref"}""") {
            template.resolveElseThrow(refResolver)
        }

        assertThat(resolved.hooks).isEqualTo(mapOf("beforeAll" to "echo from ref"))
    }

    @Test
    fun `resolveElseThrow should deserialize merged ref and extras into typed test service config via TemplateOrValue`() {
        val refResolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Map<*, *> {
                return mapOf(
                    "definitions" to emptyList<Map<String, Any>>(),
                    "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-reference")),
                    "settings" to mapOf("strictMode" to false)
                )
            }
        }

        val value: TemplateOrValue<RefOrValue<CommonServiceConfig<TestRunOptions, TestSettings>>> = TemplateOrValue.Template("{SERVICE_REF:{}}")
        val resolved = Flags.using(
            "SERVICE_REF" to $$"""{
              "$ref": "#/components/services/myTestService",
              "runOptions": {
                "openapi": {
                  "baseUrl": "http://from-extra"
                }
              },
              "settings": {
                "timeoutInMilliseconds": 1234
              }
            }"""
        ) {
            value.resolveElseThrow(refResolver)
        }

        val settings = resolved.settings!!.resolveElseThrow(refResolver)
        val runOptions = resolved.runOptions!!.resolveElseThrow(refResolver)
        assertThat(settings).isInstanceOf(TestSettings::class.java)
        assertThat(settings.timeoutInMilliseconds).isEqualTo(1234)
        assertThat(runOptions).isInstanceOf(TestRunOptions::class.java)
        assertThat(runOptions.openapi?.baseUrl).isEqualTo("http://from-extra")
    }

    @Test
    fun `resolveElseThrow should deserialize merged ref and extras into typed mock service config via TemplateOrValue`() {
        val refResolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Map<*, *> {
                return mapOf(
                    "definitions" to emptyList<Map<String, Any>>(),
                    "runOptions" to mapOf("openapi" to mapOf("baseUrl" to "http://from-reference")),
                    "settings" to mapOf("delayInMilliseconds" to 100)
                )
            }
        }

        val value: TemplateOrValue<RefOrValue<CommonServiceConfig<MockRunOptions, MockSettings>>> = TemplateOrValue.Template("{SERVICE_REF:{}}")
        val resolved = Flags.using(
            "SERVICE_REF" to $$"""{
              "$ref": "#/components/services/myMockService",
              "runOptions": {
                "openapi": {
                  "baseUrl": "http://from-extra"
                }
              },
              "settings": {
                "strictMode": true
              }
            }"""
        ) {
            value.resolveElseThrow(refResolver)
        }

        val runOptions = resolved.runOptions!!.resolveElseThrow(refResolver)
        val settings = resolved.settings!!.resolveElseThrow(refResolver)
        assertThat(runOptions).isInstanceOf(MockRunOptions::class.java)
        assertThat(runOptions.openapi?.baseUrl).isEqualTo("http://from-extra")
        assertThat(settings).isInstanceOf(MockSettings::class.java)
        assertThat(settings.strictMode).isTrue()
    }

    @Test
    fun `resolveElseThrow should resolve referenced examples via TemplateOrValue`() {
        val refResolver = object : RefOrValueResolver {
            override fun resolveRef(reference: String): Any {
                return listOf(
                    mapOf("directories" to listOf("examples-from-ref"))
                )
            }
        }

        val value: TemplateOrValue<RefOrValue<List<RefOrValue<ExampleDirectories>>>> = TemplateOrValue.Template("{EXAMPLES_REF:{}}")
        val resolved = Flags.using("EXAMPLES_REF" to $$"""{ "$ref": "#/components/examples/testExamples" }""") {
            value.resolveElseThrow(refResolver)
        }

        val data = Data(examples = RefOrValue.Value(resolved))
        assertThat(data.toExampleDirs(refResolver)).containsExactly("examples-from-ref")
    }
}
