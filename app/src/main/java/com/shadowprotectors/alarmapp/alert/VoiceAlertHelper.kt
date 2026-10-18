package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

enum class SupportedLanguage(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.ENGLISH),
    TAMIL("ta", "தமிழ் (Tamil)", Locale("ta", "IN")),
    HINDI("hi", "हिन्दी (Hindi)", Locale("hi", "IN"))
}

class VoiceAlertHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeechText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var repeatSpeechRunnable: Runnable? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var currentLanguage: SupportedLanguage = SupportedLanguage.ENGLISH
        set(value) {
            field = value
            applyLanguage(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
            tts?.setAudioAttributes(attributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    releaseAudioFocus()
                }

                override fun onError(utteranceId: String?) {
                    releaseAudioFocus()
                }
            })

            applyLanguage(currentLanguage)

            // Play any pending speech that was requested during initialization
            pendingSpeechText?.let {
                speakInternal(it, TextToSpeech.QUEUE_FLUSH, "InitPending")
                pendingSpeechText = null
            }
        } else {
            Log.e("VoiceAlertHelper", "TTS Initialization failed with status: $status")
        }
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error requesting audio focus: ${e.message}")
            false
        }
    }

    private fun releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error abandoning audio focus: ${e.message}")
        }
    }

    private fun playPreSpeechChime() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 95)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            mainHandler.postDelayed({
                try { toneGen.release() } catch (_: Exception) {}
            }, 300)
        } catch (e: Exception) {
            Log.w("VoiceAlertHelper", "Could not play pre-speech chime: ${e.message}")
        }
    }

    private fun speakInternal(text: String, queueMode: Int, utteranceTag: String) {
        requestAudioFocus()
        playPreSpeechChime()

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }

        // 220ms delay to allow pre-chime to complete and audio focus to duck background music
        mainHandler.postDelayed({
            if (tts != null && isInitialized) {
                tts?.speak(text, queueMode, params, "TravelAlarm_${utteranceTag}_${System.currentTimeMillis()}")
            }
        }, 220)
    }

    private fun applyLanguage(language: SupportedLanguage) {
        if (!isInitialized || tts == null) return
        val result = tts?.setLanguage(language.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("VoiceAlertHelper", "Language ${language.code} not supported on device, falling back to English")
            tts?.setLanguage(Locale.ENGLISH)
        }
    }

    fun speakApproachAlert(destinationName: String, distanceKm: Double) {
        cancelPendingRepeat()
        val formattedDistance = String.format(Locale.US, "%.1f", distanceKm)
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> {
                "கவனம்! $destinationName இன்னும் $formattedDistance கிலோமீட்டரில் வரவுள்ளது. இறங்குவதற்கு தயாராகுங்கள்."
            }
            SupportedLanguage.HINDI -> {
                "ध्यान दें! $destinationName $formattedDistance किलोमीटर में आने वाला है। कृपया उतरने के लिए तैयार रहें।"
            }
            SupportedLanguage.ENGLISH -> {
                "Attention! Approaching $destinationName in $formattedDistance kilometers. Please prepare your luggage."
            }
        }

        if (isInitialized && tts != null) {
            speakInternal(text, TextToSpeech.QUEUE_FLUSH, "Approach")

            // Schedule a reinforcement repeat after 4.5 seconds
            val repeatText = when (currentLanguage) {
                SupportedLanguage.TAMIL -> "நினைவூட்டல்! $destinationName இலக்கை நெருங்குகிறீர்கள்."
                SupportedLanguage.HINDI -> "याद दिलाना! आप $destinationName के पास पहुंच रहे हैं।"
                SupportedLanguage.ENGLISH -> "Reminder: Approaching $destinationName. Prepare to alight."
            }
            repeatSpeechRunnable = Runnable {
                speakInternal(repeatText, TextToSpeech.QUEUE_ADD, "ApproachRepeat")
            }
            mainHandler.postDelayed(repeatSpeechRunnable!!, 4500)
        } else {
            pendingSpeechText = text
        }
    }

    fun speakRecedingAlert(destinationName: String) {
        cancelPendingRepeat()
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> {
                "எச்சரிக்கை! $destinationName இலக்கிலிருந்து நீங்கள் விலகிச் செல்கிறீர்கள்."
            }
            SupportedLanguage.HINDI -> {
                "चेतावनी! आप $destinationName से दूर जा रहे हैं।"
            }
            SupportedLanguage.ENGLISH -> {
                "Warning! You are moving away from $destinationName."
            }
        }

        if (isInitialized && tts != null) {
            speakInternal(text, TextToSpeech.QUEUE_FLUSH, "Receding")
        } else {
            pendingSpeechText = text
        }
    }

    fun speakArrivalAlert(destinationName: String) {
        cancelPendingRepeat()
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> {
                "கவனம்! நீங்கள் $destinationName இலக்கை நெருங்கிவிட்டீர்கள். உடனே எழுந்திருக்கவும்!"
            }
            SupportedLanguage.HINDI -> {
                "ध्यान दें! आप $destinationName के बहुत करीब हैं। कृपया उठें!"
            }
            SupportedLanguage.ENGLISH -> {
                "Wake up! You have arrived at $destinationName."
            }
        }

        if (isInitialized && tts != null) {
            speakInternal(text, TextToSpeech.QUEUE_ADD, "Arrival")
        } else {
            pendingSpeechText = text
        }
    }

    fun speakTestAlert() {
        cancelPendingRepeat()
        val text = when (currentLanguage) {
            SupportedLanguage.TAMIL -> "வேபாயிண்ட் சோதனை எச்சரிக்கை. ஒலி மற்றும் அதிர்வு சரியாக வேலை செய்கிறது."
            SupportedLanguage.HINDI -> "वेपॉइंट टेस्ट अलार्म। ध्वनि और कंपन सत्यापित हैं।"
            SupportedLanguage.ENGLISH -> "WayPoint test alert. Audio focus and speech volume verified."
        }
        if (isInitialized && tts != null) {
            speakInternal(text, TextToSpeech.QUEUE_FLUSH, "Test")
        } else {
            pendingSpeechText = text
        }
    }

    private fun cancelPendingRepeat() {
        repeatSpeechRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatSpeechRunnable = null
    }

    fun stop() {
        cancelPendingRepeat()
        pendingSpeechText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error stopping TTS: ${e.message}")
        } finally {
            releaseAudioFocus()
        }
    }

    fun shutdown() {
        cancelPendingRepeat()
        pendingSpeechText = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("VoiceAlertHelper", "Error shutting down TTS: ${e.message}")
        } finally {
            tts = null
            isInitialized = false
            releaseAudioFocus()
        }
    }
}
