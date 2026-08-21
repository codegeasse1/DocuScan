package com.docuscan.app.data

import android.content.Context

data class AppSettings(
    val autoCrop: Boolean = true,
    val defaultFilter: String = "original",
    val format: String = "both",
    val theme: String = "system"
) {
    fun save(context: Context) {
        context.getSharedPreferences("docuscan", Context.MODE_PRIVATE).edit()
            .putBoolean("autoCrop", autoCrop)
            .putString("defaultFilter", defaultFilter)
            .putString("format", format)
            .putString("theme", theme)
            .apply()
    }

    companion object {
        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences("docuscan", Context.MODE_PRIVATE)
            return AppSettings(
                p.getBoolean("autoCrop", true),
                p.getString("defaultFilter", "original") ?: "original",
                p.getString("format", "both") ?: "both",
                p.getString("theme", "system") ?: "system"
            )
        }
    }
}
