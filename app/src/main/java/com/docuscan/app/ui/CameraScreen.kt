package com.docuscan.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.docuscan.app.A11y
import com.docuscan.app.DocViewModel
import com.docuscan.app.scan.BitmapUtil
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun CameraScreen(vm: DocViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    AppIcons.Camera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    "Camera permission is required to scan documents",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
                )
                Surface(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text("Grant permission", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    fun bind() {
        val pv = previewViewRef.value ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                providerRef.value = provider
            } catch (e: Exception) {
                scope.launch { snackbar.showSnackbar("Camera error: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture() {
        if (capturing) return
        capturing = true
        A11y.buzz(view)
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rot = image.imageInfo.rotationDegrees
                image.close()
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val rotated = if (decoded != null && rot != 0) BitmapUtil.rotate(decoded, rot) else decoded
                if (rotated != null) {
                    A11y.speak("Document scanned")
                    mainExecutor.execute {
                        capturing = false
                        vm.addBitmap(rotated)
                    }
                } else {
                    mainExecutor.execute {
                        capturing = false
                        scope.launch { snackbar.showSnackbar("Capture failed") }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                A11y.speak("Capture failed")
                mainExecutor.execute {
                    capturing = false
                    scope.launch { snackbar.showSnackbar("Capture failed") }
                }
            }
        })
    }

    DisposableEffect(lensFacing) {
        bind()
        onDispose {}
    }

    DisposableEffect(Unit) {
        onDispose {
            providerRef.value?.unbindAll()
            executor.shutdown()
        }
    }

    // Accessibility Mode: volume keys become the shutter, with spoken/haptic feedback.
    DisposableEffect(Unit) {
        A11y.captureHandler = { capture() }
        A11y.active = vm.settings.accessibilityEnabled
        if (vm.settings.accessibilityEnabled) {
            A11y.speak("Camera open. Press the volume button to capture.")
        }
        onDispose {
            A11y.active = false
            A11y.captureHandler = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewViewRef.value = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.closeCamera() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
            }
            Text("Scan document", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row {
                PillButton(if (flashOn) "Flash On" else "Flash") { flashOn = !flashOn }
                Spacer(w = 8)
                PillButton(if (lensFacing == CameraSelector.LENS_FACING_BACK) "Flip" else "Flip") {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .background(Color.White.copy(alpha = 0.25f), CircleShape)
                    .padding(6.dp)
                    .background(if (capturing) Color.Gray else Color.White, CircleShape)
                    .clickableCapture { capture() },
                contentAlignment = Alignment.Center
            ) {
                if (capturing) {
                    Text("…", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Spacer(w: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.size(w.dp))
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.2f),
        contentColor = Color.White
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

private fun Modifier.clickableCapture(onClick: () -> Unit): Modifier = this.then(
    clickable(onClick = onClick)
)
