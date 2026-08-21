package com.docuscan.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.docuscan.app.data.AppSettings
import com.docuscan.app.data.DocRecord
import com.docuscan.app.data.HistoryStore
import com.docuscan.app.scan.BitmapUtil
import com.docuscan.app.scan.Ocr
import com.docuscan.app.scan.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

sealed class Screen {
    object Tabs : Screen()
    object Camera : Screen()
    object Editor : Screen()
}

enum class Tab { Home, Documents, Settings }

data class ScannedPage(
    val id: Long,
    val bitmap: Bitmap,
    val filterId: String = "original",
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val ocrText: String? = null,
    val ocrWords: List<Word>? = null,
    val ocrBusy: Boolean = false
)

class DocViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val store = HistoryStore(context)
    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var tab by mutableStateOf(Tab.Home)
        private set
    var screen by mutableStateOf<Screen>(Screen.Tabs)
        private set

    val pages = mutableStateListOf<ScannedPage>()
    var selectedPage by mutableIntStateOf(0)
        private set

    var settings by mutableStateOf(AppSettings.load(context))
        private set

    var docs by mutableStateOf<List<DocRecord>>(emptyList())
        private set

    /** When the camera is opened from the editor, returning lands back in the editor. */
    var returnToEditor by mutableStateOf(false)
        private set

    init {
        docs = store.load()
    }

    val ocrLangs: List<String> get() = Ocr.availableLanguages(context)

    fun selectTab(t: Tab) {
        tab = t
        screen = Screen.Tabs
    }

    fun openCamera(fromEditor: Boolean) {
        returnToEditor = fromEditor
        screen = Screen.Camera
    }

    fun closeCamera() {
        screen = if (returnToEditor) Screen.Editor else Screen.Tabs
    }

    fun addBitmap(raw: Bitmap) {
        var b = BitmapUtil.fitMax(raw, 2400)
        if (b !== raw) raw.recycle()
        // Images are never cropped automatically - the user crops (or auto-aligns
        // the crop frame) in the editor, so nothing is lost on import.
        pages.add(ScannedPage(idCounter.incrementAndGet(), b, settings.defaultFilter))
        selectedPage = pages.size - 1
        screen = Screen.Editor
    }

    /** Adds several bitmaps at once (e.g. pages of an imported PDF). */
    fun addPdfPages(bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        for (raw in bitmaps) {
            var b = BitmapUtil.fitMax(raw, 2400)
            if (b !== raw) raw.recycle()
            pages.add(ScannedPage(idCounter.incrementAndGet(), b, settings.defaultFilter))
        }
        selectedPage = pages.size - 1
        screen = Screen.Editor
    }

    fun replaceSelected(bmp: Bitmap) {
        val i = selectedPage
        if (i in pages.indices) {
            val old = pages[i]
            pages[i] = old.copy(bitmap = bmp)
        }
    }

    fun selectPage(i: Int) {
        if (i in pages.indices) selectedPage = i
    }

    fun removePage(i: Int) {
        if (pages.size <= 1) return
        pages.removeAt(i)
        selectedPage = selectedPage.coerceAtMost(pages.size - 1).coerceAtLeast(0)
    }

    fun movePage(i: Int, dir: Int) {
        val j = i + dir
        if (i in pages.indices && j in pages.indices) {
            val tmp = pages[i]
            pages[i] = pages[j]
            pages[j] = tmp
            selectedPage = j
        }
    }

    fun rotateSelected() {
        val i = selectedPage
        if (i in pages.indices) {
            val p = pages[i]
            pages[i] = p.copy(bitmap = BitmapUtil.rotate90(p.bitmap))
        }
    }

    fun setFilter(f: String) {
        val i = selectedPage
        if (i in pages.indices) pages[i] = pages[i].copy(filterId = f)
    }

    fun setBrightness(v: Float) {
        val i = selectedPage
        if (i in pages.indices) pages[i] = pages[i].copy(brightness = v)
    }

    fun setContrast(v: Float) {
        val i = selectedPage
        if (i in pages.indices) pages[i] = pages[i].copy(contrast = v)
    }

    fun resetAdjustments() {
        val i = selectedPage
        if (i in pages.indices) pages[i] = pages[i].copy(brightness = 0f, contrast = 1f)
    }

    /** Runs OCR on one page (only when the user asks; never automatically). */
    fun runOcr(pageId: Long, force: Boolean = false) {
        val i = pages.indexOfFirst { it.id == pageId }
        if (i !in pages.indices) return
        val p = pages[i]
        if (p.ocrBusy || (!force && !p.ocrText.isNullOrBlank())) return
        pages[i] = p.copy(
            ocrBusy = true,
            ocrText = if (force) null else p.ocrText,
            ocrWords = if (force) null else p.ocrWords
        )
        val lang = settings.ocrLang
        vmScope.launch {
            val res = withContext(Dispatchers.IO) { Ocr.recognizeWithWords(context, p.bitmap, lang) }
            val j = pages.indexOfFirst { it.id == pageId }
            if (j in pages.indices) {
                val cur = pages[j]
                pages[j] = cur.copy(
                    ocrText = res?.text,
                    ocrWords = res?.words,
                    ocrBusy = false
                )
            }
        }
    }

    /** OCRs every page that doesn't have text yet. */
    fun runOcrAll() {
        val targets = pages.filter { !it.ocrBusy && it.ocrText.isNullOrBlank() }.map { it.id }
        for (id in targets) runOcr(id)
    }

    /** Re-runs OCR on a single word's region (normalized bounds). */
    fun reOcrWord(pageId: Long, wordIndex: Int) {
        val i = pages.indexOfFirst { it.id == pageId }
        if (i !in pages.indices) return
        val p = pages[i]
        val w = p.ocrWords?.getOrNull(wordIndex) ?: return
        val norm = RectF(w.left, w.top, w.right, w.bottom)
        val lang = settings.ocrLang
        vmScope.launch {
            val newText = withContext(Dispatchers.IO) {
                Ocr.recognizeRegion(context, p.bitmap, norm, lang)
            }
            val j = pages.indexOfFirst { it.id == pageId }
            if (j in pages.indices) {
                val cur = pages[j]
                val words = cur.ocrWords?.toMutableList() ?: return@launch
                if (wordIndex in words.indices) {
                    val newTextClean = newText?.trim()
                    words[wordIndex] = if (newTextClean.isNullOrBlank()) {
                        words[wordIndex].copy(confidence = 10f)
                    } else {
                        words[wordIndex].copy(text = newTextClean, confidence = 95f)
                    }
                    pages[j] = cur.copy(ocrWords = words, ocrText = Ocr.buildText(words))
                }
            }
        }
    }

    /** Replaces a word's text after manual editing in the OCR review. */
    fun setWordText(pageId: Long, wordIndex: Int, newText: String) {
        val i = pages.indexOfFirst { it.id == pageId }
        if (i !in pages.indices) return
        val p = pages[i]
        val words = p.ocrWords ?: return
        if (wordIndex !in words.indices) return
        val clean = newText.trim()
        val nw = words.toMutableList()
        val cur = nw[wordIndex]
        nw[wordIndex] = if (clean.isEmpty()) {
            cur.copy(text = " ", confidence = 100f)
        } else {
            cur.copy(text = clean, confidence = 100f)
        }
        pages[i] = p.copy(ocrWords = nw, ocrText = Ocr.buildText(nw))
    }

    fun setOcrText(pageId: Long, text: String?) {
        val i = pages.indexOfFirst { it.id == pageId }
        if (i in pages.indices) pages[i] = pages[i].copy(ocrText = text)
    }

    fun setOcrLang(lang: String) {
        updateSettings(settings.copy(ocrLang = lang))
        val p = pages.getOrNull(selectedPage)
        if (p != null) runOcr(p.id, force = true)
    }

    /** Imports a user-picked .traineddata language pack; returns its code. */
    fun importOcrLang(uri: Uri): String? = Ocr.importTrainedData(context, uri)

    override fun onCleared() {
        vmScope.cancel()
        super.onCleared()
    }

    fun updateSettings(s: AppSettings) {
        settings = s
        s.save(context)
    }

    fun addHistory(
        title: String,
        pageCount: Int,
        format: String,
        pdfUri: Uri?,
        jpgUris: List<Uri>,
        searchText: String = ""
    ) {
        val rec = DocRecord(
            idCounter.incrementAndGet(),
            title,
            System.currentTimeMillis(),
            pageCount,
            format,
            pdfUri?.toString(),
            jpgUris.map { it.toString() },
            searchText
        )
        store.add(rec)
        docs = store.load()
    }

    fun deleteDoc(id: Long) {
        val rec = docs.firstOrNull { it.id == id }
        store.delete(id)
        docs = store.load()
        if (rec != null) {
            // Best-effort removal of the exported media.
            val resolver = context.contentResolver
            rec.pdfUri?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
            rec.jpgUris.forEach { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        }
    }

    fun refreshDocs() {
        docs = store.load()
    }

    fun newDoc() {
        pages.clear()
        selectedPage = 0
    }
}
