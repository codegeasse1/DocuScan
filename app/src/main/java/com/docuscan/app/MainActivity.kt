package com.docuscan.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.docuscan.app.ui.theme.DocuScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        A11y.init(this)
        enableEdgeToEdge()
        setContent {
            DocuScanTheme {
                App()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Accessibility Mode: the hardware volume keys act as the shutter.
        if (A11y.active) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        A11y.captureHandler?.invoke()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        A11y.shutdown()
        super.onDestroy()
    }
}
