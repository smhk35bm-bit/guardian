package com.guardian.parent.service.collectors

import com.guardian.parent.data.CollectedItem

/**
 * Every collector reads one kind of information from the device and returns
 * it in the shape described by the API contract.
 *
 * Implementations must never throw: a failing collector returns an empty list
 * and logs the problem, so a single broken sensor can never take the whole
 * monitoring service down.
 */
interface Collector {
    /** Stable id, also used as the preference key for its switch/interval. */
    val id: String

    /** Collects everything that is new since the previous run. */
    suspend fun collect(): List<CollectedItem>
}
