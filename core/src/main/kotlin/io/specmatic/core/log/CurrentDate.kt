package io.specmatic.core.log

import java.time.Instant
import java.util.*

class CurrentDate(private val date: Calendar = Calendar.getInstance()) {
    fun toLogString(): String {
        val year = date.get(Calendar.YEAR)
        val month = date.get(Calendar.MONTH)
        val day = date.get(Calendar.DATE)
        val hour = date.get(Calendar.HOUR)
        val minute = date.get(Calendar.MINUTE)
        val second = date.get(Calendar.SECOND)
        val millisecond = date.get(Calendar.MILLISECOND)

        return "$year-${month + 1}-$day $hour:$minute:$second.$millisecond"
    }

    override fun toString(): String {
        return toLogString()
    }

    fun toEpochMillis(): Long {
        return date.timeInMillis
    }

    fun toInstant(): Instant {
        return date.toInstant()
    }

    fun toFileNameString(): String {
        val parts = listOf(
            Calendar.YEAR,
            Calendar.MONTH,
            Calendar.DAY_OF_MONTH,
            Calendar.HOUR,
            Calendar.MINUTE,
            Calendar.SECOND
        )

        return parts.joinToString("-") {
            if(it == Calendar.MONTH)
                (date.get(it) + 1).toString()
            else
                date.get(it).toString()
        }
    }
}