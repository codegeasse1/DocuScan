package com.docuscan.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.View
import java.util.Locale

/**
 * Accessibility Mode (like MakeACopy): spoken feedback, haptic feedback and
 * volume-key shutter. Only active while the camera is open and the user has
 * enabled it in Settings.
 */
object A11y {
    var active: Boolean = false
    var captureHandler: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.language = Locale.US
            }
        }
    }

    fun speak(text: String) {
        if (active && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "a11y")
        }
    }

    fun buzz(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
