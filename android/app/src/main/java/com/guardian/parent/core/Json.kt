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

/** Builds a [JSONObject]. */
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

/** Returns the string at [key] or `null` when it is absent, null or empty. */
fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return if (value.isEmpty()) null else value
}

/** Returns the number at [key] or `null` when it is absent or null. */
fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

/** Returns the object at [key] or `null` when it is absent or null. */
fun JSONObject.optObjectOrNull(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return optJSONObject(key)
}
