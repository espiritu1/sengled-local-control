package com.sengled.control

data class Bulb(
    val id: String,
    val name: String,
    val ip: String,
    val brightness: Int = 1,
    val isOn: Boolean = false,
    val connected: Boolean? = null
)
