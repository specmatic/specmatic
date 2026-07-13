package io.specmatic.core.value.fold

import io.specmatic.core.value.XMLValue

data class Field<out T>(
    val name: String,
    val value: T
)

data class Item<out T>(
    val index: Int,
    val value: T
)

data class XmlProjectedChild<out T>(
    val index: Int,
    val value: T
)

data class XmlAttribute<out T>(
    val name: String,
    val value: T
)

data class XmlValueChild<out T : XMLValue>(
    val index: Int,
    val value: T
)
