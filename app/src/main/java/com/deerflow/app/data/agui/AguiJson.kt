package com.deerflow.app.data.agui

import kotlinx.serialization.json.Json

/** Shared JSON configuration for AG-UI payloads. */
val AguiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
