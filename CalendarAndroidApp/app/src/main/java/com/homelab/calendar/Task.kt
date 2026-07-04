package com.homelab.calendar

data class Task(
    val id: Int,
    val description: String,
    val date: String, // YYYY-MM-DD
    val time: String, // HH:MM
    var completed: Boolean
)
