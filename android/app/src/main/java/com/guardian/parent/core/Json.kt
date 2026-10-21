package com.guardian.parent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small helpers around `org.json` (bundled with Android).
 *
 * The project deliberately avoids Moshi/Gson/kotlinx-serialization so that the
 * app has no extra runtime dependencies; these helpers simply make the manual
 * JSON building a bit less error prone.
 */

/** Builds a [JSONObject]; `null` values are stored as JSON `null`. */
fun jsonObject(build: JSONObject.() -> Unit): JSONObject {
    val obj = JSONObject()
    obj.build()
    return obj
}

/** Builds a [JSONArray]. */
fun jsonArray(build: JSONArray.() -> Unit): JSONArray {
    val arr = JSONArray()
    arr.build()
    return arr
}

fun JSONObject.putStringOrNull(key: String, value: String?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

fun JSONObject.putIntOrNull(key: String, value: Int?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

fun JSONObject.putLongOrNull(key: String, value: Long?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

fun JSONObject.putDoubleOrNull(key: String, value: Double?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

fun JSONObject.putBooleanOrNull(key: String, value: Boolean?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

/** Returns the string at [key] or `null` when it is absent, null or empty. */
fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return if (value.isEmpty()) null else value
}

fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}

fun JSONObject.optObjectOrNull(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return optJSONObject(key)
}
