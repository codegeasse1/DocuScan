package com.docuscan.app.scan

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/** Application context holder for background inference; set in MainActivity. */
object AppCtx {
    @Volatile lateinit var app: Context
}

/**
 * ONNX Runtime inference for DocQuadNet-256 — direct port of makeacopy's
 * DocQuadOrtRunner (Apache 2.0). Input [1,3,256,256] float32 ("input"),
 * outputs "mask_logits" [1,1,64,64] and "corner_heatmaps" [1,4,64,64].
 *
 * The model asset is copied to cache once per app version so ORT can mmap it.
 */
class DocQuadOrtRunner private constructor(context: Context, modelAssetPath: String) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val sessionLock = Any()
    @Volatile private var closed = false

    class Outputs(val maskLogits: Array<FloatArray>, val cornerHeatmaps: Array<Array<FloatArray>>)

    init {
        val modelFile = copyAssetToCache(context, modelAssetPath)
        session = createSessionWithFallback(env, modelFile.absolutePath)
    }

    private fun createSessionWithFallback(env: OrtEnvironment, modelPath: String): OrtSession {
        return try {
            OrtSession.SessionOptions().use { opts ->
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        opts.addNnapi()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    opts.addXnnpack(emptyMap<String, String>())
                } catch (_: Throwable) {
                }
                env.createSession(modelPath, opts)
            }
        } catch (e: Exception) {
            OrtSession.SessionOptions().use { opts ->
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
                env.createSession(modelPath, opts)
            }
        }
    }

    fun run(inputNchw: FloatArray): Outputs {
        require(inputNchw.size == 3 * 256 * 256) { "inputNchw must have length 196608" }
        synchronized(sessionLock) {
            check(!closed) { "DocQuadOrtRunner is closed" }
            val inputShape = longArrayOf(1, 3, 256, 256)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inputNchw), inputShape).use { input ->
                session.run(mapOf("input" to input)).use { results ->
                    val mask4d = getRequiredFloat4d(results, "mask_logits") as Array<Array<Array<FloatArray>>>
                    val corners4d = getRequiredFloat4d(results, "corner_heatmaps") as Array<Array<Array<FloatArray>>>
                    return Outputs(mask4d[0][0], corners4d[0])
                }
            }
        }
    }

    private fun getRequiredFloat4d(results: OrtSession.Result, outputName: String): Any {
        val ov = results.get(outputName)
        if (!ov.isPresent) {
            throw IllegalStateException("ONNX output missing: '$outputName'")
        }
        val v = ov.get().value
        return v
    }

    override fun close() {
        synchronized(sessionLock) {
            if (!closed && session != null) {
                try {
                    session.close()
                } catch (_: OrtException) {
                }
            }
            closed = true
        }
    }

    private fun copyAssetToCache(context: Context, assetPath: String): File {
        val baseName = File(assetPath).name
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) {
            -1L
        }
        val versionedName = "${versionCode}_$baseName"
        val outFile = File(context.cacheDir, versionedName)
        if (!outFile.exists()) {
            context.assets.open(assetPath).use { ins ->
                FileOutputStream(outFile).use { fos ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val len = ins.read(buffer)
                        if (len == -1) break
                        fos.write(buffer, 0, len)
                    }
                }
            }
            context.cacheDir.listFiles { _, name ->
                name.endsWith("_$baseName") && name != versionedName
            }?.forEach { it.delete() }
        }
        return outFile
    }

    companion object {
        const val IN_H = 256
        const val IN_W = 256
        const val OUT_H = 64
        const val OUT_W = 64
        const val DEFAULT_MODEL_ASSET_PATH = "docquad/docquadnet256_trained_opset17.ort"

        @Volatile private var instance: DocQuadOrtRunner? = null
        private val lock = Any()

        fun getInstance(context: Context): DocQuadOrtRunner {
            instance?.let { return it }
            synchronized(lock) {
                instance?.let { return it }
                val created = DocQuadOrtRunner(context.applicationContext, DEFAULT_MODEL_ASSET_PATH)
                instance = created
                return created
            }
        }
        fun isInstanceLoaded(): Boolean = instance != null

        fun releaseInstance() {
            synchronized(lock) {
                instance?.let {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                    instance = null
                }
            }
        }
    }
}
