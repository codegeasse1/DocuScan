package com.docuscan.app.data

import android.content.Context

data class AppSettings(
    val defaultFilter: String = "original",
    val format: String = "both",
    val theme: String = "system",
    val inboxEnabled: Boolean = false,
    val inboxUri: String = "",
    val accessibilityEnabled: Boolean = false,
    val autoEnhance: Boolean = true,
    val jpegQuality: Int = 100,
    val jpegColor: Boolean = true,
    val pageFormat: String = "FIT_TO_IMAGE",
    val pdfQuality: String = "HIGH"
) {
    fun save(context: Context) {
        context.getSharedPreferences("docuscan", Context.MODE_PRIVATE).edit()
            .putString("defaultFilter", defaultFilter)
            .putString("format", format)
            .putString("theme", theme)
            .putBoolean("inboxEnabled", inboxEnabled)
            .putString("inboxUri", inboxUri)
            .putBoolean("accessibilityEnabled", accessibilityEnabled)
            .putBoolean("autoEnhance", autoEnhance)
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
                p.getBoolean("autoEnhance", true),
                p.getInt("jpegQuality", 100),
                p.getBoolean("jpegColor", true),
                p.getString("pageFormat", "FIT_TO_IMAGE") ?: "FIT_TO_IMAGE",
                p.getString("pdfQuality", "HIGH") ?: "HIGH"
            )
        }
    }
}
