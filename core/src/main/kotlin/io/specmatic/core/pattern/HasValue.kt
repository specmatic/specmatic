package io.specmatic.core.pattern

data class HasValue<T>(override val value: T, val valueDetails: List<ValueDetails> = emptyList()): ReturnValue<T> {
    constructor(value: T, message: String): this(value, listOf(ValueDetails(listOf(message))))
    constructor(value: T, message: String, key: String): this(value, listOf(ValueDetails(listOf(message), listOf(key))))

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if(other !is HasValue<*>)
            return false

        val thisValue: T = this.value
        val otherValue: Any? = other.value

        return thisValue?.equals(otherValue) == true
    }

    override fun <U> withDefault(default: U, fn: (T) -> U): U {
        return fn(value)
    }

    override fun <U> ifValue(fn: (T) -> U): ReturnValue<U> {
        return try {
            HasValue(fn(value), valueDetails)
        } catch(t: Throwable) {
            HasException(t)
        }
    }

    override fun update(fn: (T) -> T): ReturnValue<T> {
        return try {
            val newValue = fn(value)
            HasValue(newValue, valueDetails)
        } catch(t: Throwable) {
            HasException(t)
        }
    }

    override fun <U> assimilate(acc: ReturnValue<U>, fn: (T, U) -> T): ReturnValue<T> {
        if(acc is ReturnFailure)
            return acc.cast()
        else if(acc is HasException)
            return acc.cast()

        acc as HasValue<U>

        return try {
            val newValue = fn(value, acc.value)
            HasValue(newValue, valueDetails.plus(acc.valueDetails))
        } catch(t: Throwable) {
            HasException(t)
        }
    }

    fun comments(): String? {
        if(valueDetails.isEmpty())
            return null

        val blankLineSeparator = System.lineSeparator() + System.lineSeparator()

        val comments = """
            ${valueDetails.mapNotNull { it.comments() }.joinToString(blankLineSeparator)}
        """.trimIndent().trim()

        return comments
    }

    override fun <U> realise(hasValue: (T, String?) -> U, orFailure: (HasFailure<T>) -> U, orException: (HasException<T>) -> U): U {
        return hasValue(value, comments())
    }

    override fun <U> ifHasValue(fn: (HasValue<T>) -> ReturnValue<U>): ReturnValue<U> {
        return fn(this)
    }

    override fun <U, V> combine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V> {
        if(acc is ReturnFailure)
            return acc.cast()

        acc as HasValue<U>

        return try {
            val newValue = fn(value, acc.value)
            HasValue(newValue, valueDetails.plus(acc.valueDetails))
        } catch(t: Throwable) {
            HasException(t)
        }
    }

    override fun addDetails(message: String, breadCrumb: String): ReturnValue<T> {
        if(message.isBlank() && breadCrumb.isBlank())
            return this

        return HasValue(
            value,
            valueDetails.map { it.addDetails(message, breadCrumb) })
    }
}
