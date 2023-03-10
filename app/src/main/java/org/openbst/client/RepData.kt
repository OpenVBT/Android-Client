package org.openbst.client

data class RepData(
    val timestamp_ms: Long,
    val date_string : String,
    val max_velocity: Float,
    val min_velocity: Float,
    val max_accel: Float,
    val min_accel: Float
    )