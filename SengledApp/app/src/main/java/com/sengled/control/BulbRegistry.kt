package com.sengled.control

/**
 * Single source of truth for the bulb hardware (id, default name, IP).
 * Shared by the UI (MainActivity) and the scheduler (ScheduleReceiver) so
 * both sides always use the same IPs.
 */
object BulbRegistry {

    val bulbs = listOf(
        Bulb("sala", "Lampara de sala", "192.168.68.150"),
        Bulb("blanca", "Luz blanca", "192.168.68.118")
    )
}
