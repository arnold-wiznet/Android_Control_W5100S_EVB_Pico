package com.example.new_iot_app

// DashboardModels.kt
import kotlinx.serialization.Serializable

@Serializable
data class Dashboard(
    val widgets: List<Widget>
)

@Serializable
data class Widget(
    val id: String,
    val name: String,
    val value: Double
)