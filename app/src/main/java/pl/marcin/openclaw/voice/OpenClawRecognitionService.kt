package pl.marcin.openclaw.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/**
 * Declares the recognition capability required of an Android voice-interaction app.
 * Conversation dictation still deliberately uses the user's configured system recognizer.
 */
class OpenClawRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit

    @RequiresApi(33)
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) {
        supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
    }

    @RequiresApi(33)
    override fun onTriggerModelDownload(recognizerIntent: Intent) = Unit
}
