package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

interface MatcherFactory {
    val matcherKey: String?

    fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher>

    fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean

    fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher>
}
