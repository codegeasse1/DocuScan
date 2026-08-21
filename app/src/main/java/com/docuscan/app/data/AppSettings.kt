package com.docuscan.app.data

import android.content.Context

data class AppSettings(
    val autoCrop: Boolean = true,
    val defaultFilter: String = "original",
    val format: String = "both",
    val theme: String = "system",
    val pageFormat: String = "FIT_TO_IMAGE",
    val pdfQuality: String = "STANDARD"
) {
    fun save(context: Context) {
        context.getSharedPreferences("docuscan", Context.MODE_PRIVATE).edit()
            .putBoolean("autoCrop", autoCrop)
            .putString("defaultFilter", defaultFilter)
            .putString("format", format)
            .putString("theme", theme)
            .putString("pageFormat", pageFormat)
            .putString("pdfQuality", pdfQuality)
            .apply()
    }

    companion object {
        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences("docuscan", Context.MODE_PRIVATE)
            return AppSettings(
                p.getBoolean("autoCrop", true),
                p.getString("defaultFilter", "original") ?: "original",
                p.getString("format", "both") ?: "both",
                p.getString("theme", "system") ?: "system",
                p.getString("pageFormat", "FIT_TO_IMAGE") ?: "FIT_TO_IMAGE",
                p.getString("pdfQuality", "STANDARD") ?: "STANDARD"
            )
        }
    }
}
