package org.example.util

import io.github.cdimascio.dotenv.dotenv

object Env {
    private val dotenv = dotenv()

    val DISCORD_TOKEN = dotenv["DISCORD_TOKEN"] ?: error("Missing DISCORD_TOKEN")
    val DEADLOCK_BASE_URL = dotenv["DEADLOCK_BASE_URL"] ?: "https://api.deadlock-api.com/v1"
    val CHANNEL_ID = dotenv["CHANNEL_ID"] ?: error("Missing CHANNEL_ID")
}