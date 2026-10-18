package com.shadowprotectors.alarmapp.data

import com.shadowprotectors.alarmapp.engine.TripMode

/**
 * Unified contract representing a request to start a trip.
 * Both fresh map pin drops, shared links, and saved location taps produce this request object.
 */
data class TripStartRequest(
    val destinationName: String,
    val latitude: Double,
    val longitude: Double,
    val tripMode: TripMode = TripMode.BUS_CAR,
    val languageCode: String = "en",
    val savedLocationId: Long? = null
)
