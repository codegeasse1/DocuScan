package com.docuscan.app.scan

import android.content.Context

/**
 * OCR post-processing dictionary: validates recognized words and suggests
 * corrections for low-confidence words (like MakeACopy's dictionary feature).
 *
 * Word frequency list: "Word Frequency" by Hermit Dave (FrequencyWords),
 * data from Wikipedia word-frequency lists, CC BY-SA 4.0.
 */
object Dictionary {

    private const val MAX_WORDS = 30000
    private var loaded: List<String>? = null

    @Synchronized
    private fun load(context: Context): List<String> {
        loaded?.let { return it }
        val list = ArrayList<String>(MAX_WORDS)
        try {
            context.assets.open("words/en_50k.txt").bufferedReader().use { br ->
                var count = 0
                for (line in br) {
                    if (count >= MAX_WORDS) break
                    val w = line.substringBefore('\t').substringBefore(' ').trim()
                    if (w.length in 2..30 && w.all { it.isLetter() }) {
                        list.add(w)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
        }
        val result = list.distinct()
        loaded = result
        return result
    }

    /** Nearest dictionary words (edit distance 1-2), sorted by frequency rank. */
    fun suggest(context: Context, word: String, max: Int = 4): List<String> {
        val dict = load(context)
        val w = word.lowercase()
        if (w.isEmpty()) return emptyList()
        val target = w.length
        val scored = ArrayList<Pair<String, Int>>(64)
        for (cand in dict) {
            val len = cand.length
            if (len < target - 2 || len > target + 2) continue
            if (cand == w) continue
            val d = levenshtein(w, cand)
            if (d in 1..2) scored.add(cand to d)
        }
        scored.sortWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        return scored.take(max).map { it.first }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = curr
            curr = t
        }
        return prev[lb]
    }
}
