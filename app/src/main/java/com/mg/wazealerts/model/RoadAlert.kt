package com.mg.wazealerts.model

data class RoadAlert(
    val id: String,
    val kind: AlertKind,
    val title: String,
    val description: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float,
    val reportedAtMillis: Long
)
