package com.guardian.parent.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Timestamp helpers.
 *
 * The API contract expects ISO-8601 instants in UTC with millisecond
 * precision, e.g. `2026-10-21T15:49:00.000Z`.
 */
object TimeFmt {

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    private val HUMAN: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

    /** ISO-8601 UTC instant with millisecond precision. */
    fun iso(millis: Long): String = ISO_UTC.format(Instant.ofEpochMilli(millis))

    /** Local, human readable timestamp used in the UI. */
    fun human(millis: Long): String = HUMAN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** "x minutes ago" style label used by the status card. */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val deltaSeconds = ((now - millis) / 1000L).coerceAtLeast(0L)
        return when {
            deltaSeconds < 60L -> "$deltaSeconds s"
            deltaSeconds < 3600L -> "${deltaSeconds / 60L} min"
            deltaSeconds < 86400L -> "${deltaSeconds / 3600L} h"
            else -> "${deltaSeconds / 86400L} d"
        }
    }
}
