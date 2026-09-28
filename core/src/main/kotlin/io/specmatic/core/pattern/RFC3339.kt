package io.specmatic.core.pattern

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher
import java.util.regex.Pattern

private const val DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
private const val DATE_FORMAT = "yyyy-MM-dd"

private val RFC3339_PATTERN: Pattern = Pattern.compile(
    """^(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})""" +
        """[Tt](?<hour>\d{2}):(?<minute>\d{2}):(?<second>\d{2})(?:\.\d+)?""" +
        """(?<offset>[Zz]|[+-](?<offsetHour>\d{2}):(?<offsetMinute>\d{2}))$"""
)

class RFC3339 {
    companion object {
        val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT)

        fun parse(dateTime: String) {
            val matcher = RFC3339_PATTERN.matcher(dateTime)
            if (!matcher.matches()) {
                throw invalidDateTime(dateTime)
            }

            try {
                LocalDate.of(
                    matcher.group("year").toInt(),
                    matcher.group("month").toInt(),
                    matcher.group("day").toInt()
                )
            } catch (_: DateTimeException) {
                throw invalidDateTime(dateTime)
            }

            validateRange(matcher, "hour", 0..23, dateTime)
            validateRange(matcher, "minute", 0..59, dateTime)
            validateRange(matcher, "second", 0..60, dateTime)

            if (!matcher.group("offset").equals("Z", ignoreCase = true)) {
                validateRange(matcher, "offsetHour", 0..23, dateTime)
                validateRange(matcher, "offsetMinute", 0..59, dateTime)
            }
        }

        private fun validateRange(matcher: Matcher, group: String, range: IntRange, dateTime: String) {
            if (matcher.group(group).toInt() !in range) throw invalidDateTime(dateTime)
        }

        private fun invalidDateTime(dateTime: String) =
            ContractException("Error while parsing the dateTime as per RFC 3339: $dateTime")

        fun currentDateTime(): String {
            val dateTimeWithSystemOffset = ZonedDateTime.of(LocalDateTime.now(), ZoneId.systemDefault())
            return dateTimeWithSystemOffset.format(DateTimeFormatter.ofPattern(DATETIME_FORMAT))
        }

        fun currentDate(): String = LocalDateTime.now().format(
            dateFormatter
        )
    }
}
