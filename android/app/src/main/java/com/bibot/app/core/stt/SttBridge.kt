package com.bibot.app.core.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SttBridge(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun startListening(
        language: String = "vi-VN",
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (Int, String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(-1, "Thiết bị không hỗ trợ SpeechRecognizer")
            return
        }
        if (isListening) stopListening()
        isListening = true
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) {
                    isListening = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Lỗi audio"
                        SpeechRecognizer.ERROR_CLIENT -> "Lỗi client"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền RECORD_AUDIO"
                        SpeechRecognizer.ERROR_NETWORK -> "Lỗi mạng"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận diện được"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Bận"
                        SpeechRecognizer.ERROR_SERVER -> "Lỗi server"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Hết thời gian"
                        else -> "Lỗi $error"
                    }
                    onError(error, msg)
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) onResult(matches[0]) else onError(-1, "Không có kết quả")
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!p.isNullOrEmpty()) onPartial(p[0])
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    fun isListening(): Boolean = isListening
}
