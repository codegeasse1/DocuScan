package com.docuscan.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.docuscan.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer release found on the GitHub Releases feed. */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val notes: String,
    val sizeBytes: Long,
    val buildNumber: Int
)

/**
 * Checks the project's GitHub Releases for a newer build. Releases are published by CI with the
 * tag `v1.0.<build>`, matching the `1.0.<build>` version name baked into the APK, so the build
 * number embedded in the version name is enough to compare versions.
 */
object UpdateChecker {

    const val REPO = "codegeasse1/DocuScan"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** The numeric build, taken from the "1.0.<n>" version name CI bakes in. */
    fun currentBuildNumber(): Int =
        BuildConfig.VERSION_NAME.substringAfterLast('.').toIntOrNull() ?: 0

    /**
     * Returns the latest release when it is newer than this build; null when already up to date,
     * when the release carries no APK, or when anything goes wrong (offline, rate-limited, ...).
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = runCatching { fetchJson(LATEST_API) }.getOrNull() ?: return@withContext null
        val tag = json.optString("tag_name")
        val build = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        if (build <= currentBuildNumber()) return@withContext null

        var apkUrl = ""
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size")
                    break
                }
            }
        }
        if (apkUrl.isEmpty()) return@withContext null

        UpdateInfo(
            versionName = tag.removePrefix("v"),
            tagName = tag,
            downloadUrl = apkUrl,
            releasePageUrl = json.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
            notes = json.optString("body").trim(),
            sizeBytes = apkSize,
            buildNumber = build
        )
    }

    private fun fetchJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DocuScan-Android")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

/** Downloads a release APK and hands it to the system package installer. */
object ApkInstaller {

    /** True when the OS will let us launch the installer (Android 8+ gates installs per-app). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" screen so the user can allow DocuScan to install updates. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Streams [url] into the cache directory, reporting progress in the 0..1 range (or -1 while
     * the total size is unknown), and returns the downloaded APK.
     */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "DocuScan-update.apk")
            val conn = openFollowingRedirects(url)
            val total = conn.contentLengthLong
            try {
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastReported >= 128 * 1024) {
                                lastReported = downloaded
                                onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            onProgress(1f)
            out
        }

    /** Fires the system package installer for a downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Opens the release page in a browser, for anyone who prefers to download manually. */
    fun openReleasePage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DocuScan-Android")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || ++hops > 8) throw IllegalStateException("Bad redirect")
                current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            return conn
        }
    }
}
