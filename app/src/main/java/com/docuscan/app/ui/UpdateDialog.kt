package com.docuscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.docuscan.app.BuildConfig
import com.docuscan.app.update.ApkInstaller
import com.docuscan.app.update.UpdateInfo
import java.io.File
import kotlinx.coroutines.launch

private enum class Stage { Offer, Downloading, Ready, Failed }

/**
 * Shown when a newer release exists. "Update" downloads the APK and launches the system installer;
 * a "Release page" button opens the GitHub release so it can be grabbed manually instead.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.Offer) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }

    fun startDownload() {
        error = null
        stage = Stage.Downloading
        progress = 0f
        scope.launch {
            runCatching {
                val apk = ApkInstaller.download(context, info.downloadUrl) { progress = it }
                apkFile = apk
                if (ApkInstaller.canInstall(context)) {
                    ApkInstaller.install(context, apk)
                    onDismiss()
                } else {
                    stage = Stage.Ready
                }
            }.onFailure {
                error = it.message ?: it.javaClass.simpleName
                stage = Stage.Failed
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (stage != Stage.Downloading) onDismiss() },
        title = { Text(if (stage == Stage.Ready) "Ready to install" else "Update available") },
        text = {
            Column {
                when (stage) {
                    Stage.Offer -> {
                        Text(
                            "DocuScan ${info.versionName} is available — you have " +
                                "${BuildConfig.VERSION_NAME}."
                        )
                        if (info.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                info.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                    Stage.Downloading -> {
                        Text("Downloading DocuScan ${info.versionName}…")
                        Spacer(Modifier.height(12.dp))
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Stage.Ready -> {
                        Text(
                            "The download is done. Allow DocuScan to install this update, then " +
                                "tap Install to finish."
                        )
                    }
                    Stage.Failed -> {
                        Text("The download failed. Try again, or get the APK from the release page.")
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                Stage.Offer, Stage.Failed -> Button(onClick = { startDownload() }) { Text("Update") }
                Stage.Downloading -> Button(onClick = {}, enabled = false) { Text("Downloading…") }
                Stage.Ready -> Button(onClick = {
                    val apk = apkFile
                    if (apk != null && ApkInstaller.canInstall(context)) {
                        runCatching { ApkInstaller.install(context, apk) }
                    } else {
                        ApkInstaller.openInstallPermissionSettings(context)
                    }
                }) { Text("Install") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { ApkInstaller.openReleasePage(context, info.releasePageUrl) }) {
                    Text("Release page")
                }
                TextButton(onClick = onDismiss, enabled = stage != Stage.Downloading) { Text("Later") }
            }
        }
    )
}
