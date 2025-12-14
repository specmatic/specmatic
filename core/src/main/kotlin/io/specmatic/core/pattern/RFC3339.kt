package io.specmatic.core.pattern

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

private const val DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
private const val DATE_FORMAT = "yyyy-MM-dd"

private val RFC3339_PATTERN: Pattern = Pattern.compile (
    "^(\\d{4})-(\\d{2})-(\\d{2})" // yyyy-MM-dd
            + "([Tt](\\d{2}):(\\d{2})(:(\\d{2})(\\.\\d+)?)?)?" // 'T'HH:mm:ss.milliseconds
            + "([Zz]|([+-])(\\d{2}):(\\d{2}))?" // 'Z' or time zone shift HH:mm following '+' or '-'
)

class RFC3339 {
    companion object{
        val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT)

        fun parse(dateTime: String) {
            val matcher = RFC3339_PATTERN.matcher(dateTime)
            if(!matcher.matches()) {
                throw ContractException("Error while parsing the dateTime as per RFC 3339: $dateTime")
            }
        }

        fun currentDateTime(): String {
            val dateTimeWithSystemOffset = ZonedDateTime.of(LocalDateTime.now(), ZoneId.systemDefault())
            return dateTimeWithSystemOffset.format(DateTimeFormatter.ofPattern(DATETIME_FORMAT))
        }

        fun currentDate(): String = LocalDateTime.now().format(
            dateFormatter
        )
    }
}