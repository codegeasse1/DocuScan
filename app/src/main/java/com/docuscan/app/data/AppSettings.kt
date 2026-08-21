package com.docuscan.app.data

import android.content.Context

data class AppSettings(
    val defaultFilter: String = "original",
    val format: String = "both",
    val theme: String = "system",
    val ocrLang: String = "eng",
    val inboxEnabled: Boolean = false,
    val inboxUri: String = "",
    val accessibilityEnabled: Boolean = false,
    val jpegQuality: Int = 92,
    val jpegColor: Boolean = true
) {
    fun save(context: Context) {
        context.getSharedPreferences("docuscan", Context.MODE_PRIVATE).edit()
            .putString("defaultFilter", defaultFilter)
            .putString("format", format)
            .putString("theme", theme)
            .putString("ocrLang", ocrLang)
            .putBoolean("inboxEnabled", inboxEnabled)
            .putString("inboxUri", inboxUri)
            .putBoolean("accessibilityEnabled", accessibilityEnabled)
            .putInt("jpegQuality", jpegQuality)
            .putBoolean("jpegColor", jpegColor)
            .apply()
    }

    companion object {
        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences("docuscan", Context.MODE_PRIVATE)
            return AppSettings(
                p.getString("defaultFilter", "original") ?: "original",
                p.getString("format", "both") ?: "both",
                p.getString("theme", "system") ?: "system",
                p.getString("ocrLang", "eng") ?: "eng",
                p.getBoolean("inboxEnabled", false),
                p.getString("inboxUri", "") ?: "",
                p.getBoolean("accessibilityEnabled", false),
                p.getInt("jpegQuality", 92),
                p.getBoolean("jpegColor", true)
            )
        }
    }
}
