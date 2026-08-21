package com.docuscan.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DocRecord(
    val id: Long,
    val title: String,
    val timestamp: Long,
    val pageCount: Int,
    val format: String,
    val pdfUri: String?,
    val jpgUris: List<String>,
    val searchText: String = ""
)

class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "documents.json")

    fun load(): MutableList<DocRecord> {
        val list = mutableListOf<DocRecord>()
        try {
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pages = mutableListOf<String>()
                val pa = o.optJSONArray("jpgUris") ?: continue
                for (j in 0 until pa.length()) pages.add(pa.getString(j))
                list.add(
                    DocRecord(
                        o.getLong("id"),
                        o.getString("title"),
                        o.getLong("timestamp"),
                        o.getInt("pageCount"),
                        o.getString("format"),
                        if (o.has("pdfUri")) o.getString("pdfUri") else null,
                        pages,
                        o.optString("searchText", "")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun add(rec: DocRecord) {
        val l = load()
        l.add(0, rec)
        save(l)
    }

    fun delete(id: Long) {
        save(load().filter { it.id != id })
    }

    private fun save(list: List<DocRecord>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("title", r.title)
                        put("timestamp", r.timestamp)
                        put("pageCount", r.pageCount)
                        put("format", r.format)
                        put("pdfUri", r.pdfUri)
                        put("jpgUris", JSONArray(r.jpgUris))
                        put("searchText", r.searchText)
                    }
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
        }
    }
}
