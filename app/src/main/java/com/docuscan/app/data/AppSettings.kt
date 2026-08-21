package com.docuscan.app.data

import android.content.Context

data class AppSettings(
    val defaultFilter: String = "original",
    val format: String = "both",
    val theme: String = "system",
    val inboxEnabled: Boolean = false,
    val inboxUri: String = "",
    val accessibilityEnabled: Boolean = false,
    val jpegQuality: Int = 92,
    val jpegColor: Boolean = true,
    val pageFormat: String = "FIT_TO_IMAGE",
    val pdfQuality: String = "STANDARD"
) {
    fun save(context: Context) {
        context.getSharedPreferences("docuscan", Context.MODE_PRIVATE).edit()
            .putString("defaultFilter", defaultFilter)
            .putString("format", format)
            .putString("theme", theme)
            .putBoolean("inboxEnabled", inboxEnabled)
            .putString("inboxUri", inboxUri)
            .putBoolean("accessibilityEnabled", accessibilityEnabled)
            .putInt("jpegQuality", jpegQuality)
            .putBoolean("jpegColor", jpegColor)
            .putString("pageFormat", pageFormat)
            .putString("pdfQuality", pdfQuality)
            .apply()
    }

    companion object {
        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences("docuscan", Context.MODE_PRIVATE)
            return AppSettings(
                p.getString("defaultFilter", "original") ?: "original",
                p.getString("format", "both") ?: "both",
                p.getString("theme", "system") ?: "system",
                p.getBoolean("inboxEnabled", false),
                p.getString("inboxUri", "") ?: "",
                p.getBoolean("accessibilityEnabled", false),
                p.getInt("jpegQuality", 92),
                p.getBoolean("jpegColor", true),
                p.getString("pageFormat", "FIT_TO_IMAGE") ?: "FIT_TO_IMAGE",
                p.getString("pdfQuality", "STANDARD") ?: "STANDARD"
            )
        }
    }
}
