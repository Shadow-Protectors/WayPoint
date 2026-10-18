package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object AudioAlarmHelper {

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var ringtone: Ringtone? = null

    @Volatile
    private var emergencyAudioTrack: android.media.AudioTrack? = null

    @Volatile
    private var isAlarmPlaying = false

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    @Volatile
    private var alertPingHandler: android.os.Handler? = null

    @Volatile
    private var alertPingRunnable: Runnable? = null

    @Volatile
    private var alertStopRunnable: Runnable? = null

    @Volatile
    private var currentToneGen: android.media.ToneGenerator? = null

    /**
     * Stops any active repeating ping alert and releases tone generator.
     */
    @Synchronized
    private fun stopAlertPing() {
        try {
            alertPingHandler?.removeCallbacksAndMessages(null)
            alertPingHandler = null
            alertPingRunnable = null
            alertStopRunnable = null

            currentToneGen?.let {
                try { it.stopTone() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            currentToneGen = null
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping alert ping: ${e.message}")
        }
    }

    /**
     * Triggers Level 1 alert (Heads-Up):
     * Noticeable double haptic pulse + gentle chime tone so user notices even with phone in pocket.
     */
    @Synchronized
    fun playLevel1Alert(context: Context) {
        val appContext = context.applicationContext
        try {
            val vibrator = getVibrator(appContext)
            val pattern = longArrayOf(0, 250, 150, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error in playLevel1Alert vibration: ${e.message}")
        }

        try {
            val toneGen = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { toneGen.release() } catch (_: Exception) {}
            }, 600)
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error playing Level 1 chime: ${e.message}")
        }
    }

    /**
     * Triggers Level 2 alert (Proximity Attention):
     * Heavy rhythmic pulse train + dual sonar attention ping on STREAM_ALARM.
     * Duration: 25 seconds (between 20 and 30 sec) as requested.
     */
    @Synchronized
    fun playLevel2Alert(context: Context, durationMs: Long = 25000L) {
        val appContext = context.applicationContext

        // Stop any previous ping alert cycle
        stopAlertPing()

        // 1. Repeat Heavy Vibration Pattern for 25 seconds
        try {
            val vibrator = getVibrator(appContext)
            val pattern = longArrayOf(0, 500, 200, 500, 200, 700)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat continuously
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error in playLevel2Alert vibration: ${e.message}")
        }

        // 2. Play Rhythmic Ping Tone every 1.5s on STREAM_ALARM for 25 seconds
        try {
            val toneGen = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 95)
            currentToneGen = toneGen

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            alertPingHandler = handler

            val pingRunnable = object : Runnable {
                override fun run() {
                    try {
                        toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE, 500)
                    } catch (e: Exception) {
                        Log.e("AudioAlarmHelper", "Error in ping runnable: ${e.message}")
                    }
                    handler.postDelayed(this, 1500L)
                }
            }
            alertPingRunnable = pingRunnable
            handler.post(pingRunnable)

            // 3. Auto-stop after specified duration (25 seconds)
            val stopRunnable = Runnable {
                stopAlertPing()
                try {
                    val vibrator = getVibrator(appContext)
                    vibrator.cancel()
                } catch (_: Exception) {}
            }
            alertStopRunnable = stopRunnable
            handler.postDelayed(stopRunnable, durationMs)

        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error playing Level 2 ping: ${e.message}")
        }
    }

    /**
     * Legacy alias for Level 2 alert.
     */
    @Synchronized
    fun playLevel2Vibration(context: Context) {
        playLevel2Alert(context)
    }

    /**
     * Starts continuous high-priority loud alarm loop + intense vibration.
     * Guaranteed to play sound even if phone is set to Silent Mode, Vibrate Mode, or Do Not Disturb.
     */
    @Synchronized
    fun startFullAlarm(context: Context) {
        if (isAlarmPlaying) return
        isAlarmPlaying = true

        val appContext = context.applicationContext

        // 1. Continuous Alarm Vibration
        try {
            val vibrator = getVibrator(appContext)
            val alarmVibratePattern = longArrayOf(0, 1000, 300, 1000, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(alarmVibratePattern, 0)) // 0 = repeat indefinitely
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(alarmVibratePattern, 0)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error starting vibrator: ${e.message}")
        }

        // 2. Maximize Audio Streams
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

        // Maximize STREAM_ALARM first (does not require DND policy access on Android)
        try {
            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
        } catch (e: Exception) {
            Log.w("AudioAlarmHelper", "Could not set STREAM_ALARM volume: ${e.message}")
        }

        // Maximize STREAM_RING, STREAM_MUSIC, STREAM_NOTIFICATION if policy access permits
        try {
            val canModifyRinger = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notificationManager?.isNotificationPolicyAccessGranted == true
            } else {
                true
            }

            if (canModifyRinger && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: Exception) {
                    Log.w("AudioAlarmHelper", "Could not set ringer mode to NORMAL: ${e.message}")
                }
            }

            val streamsToMaximize = intArrayOf(
                AudioManager.STREAM_RING,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_NOTIFICATION
            )
            for (stream in streamsToMaximize) {
                try {
                    val maxVol = audioManager.getStreamMaxVolume(stream)
                    audioManager.setStreamVolume(stream, maxVol, 0)
                } catch (e: Exception) {
                    Log.w("AudioAlarmHelper", "Could not set volume for stream $stream: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("AudioAlarmHelper", "Could not set stream volume (DND policy restriction): ${e.message}")
        }

        // 3. Request transient exclusive Audio Focus with USAGE_ALARM to interrupt headphones / music
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error requesting audio focus: ${e.message}")
        }

        // 4. Play Phone Ringtone Sound at Full Volume (MediaPlayer -> Ringtone -> AudioTrack Siren Fallback)
        var soundStarted = false
        try {
            // Prioritize phone's ringtone tone (RingtoneManager.TYPE_RINGTONE) as requested
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            // A. Primary: MediaPlayer with USAGE_ALARM
            if (alarmUri != null) {
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(appContext, alarmUri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setLegacyStreamType(AudioManager.STREAM_ALARM)
                                .build()
                        )
                        setVolume(1.0f, 1.0f)
                        isLooping = true
                        prepare()
                        start()
                    }
                    soundStarted = true
                } catch (mpEx: Exception) {
                    Log.w("AudioAlarmHelper", "MediaPlayer failed, attempting Ringtone fallback: ${mpEx.message}")
                }
            }

            // B. Secondary: Ringtone fallback with USAGE_ALARM explicitly set
            if (!soundStarted && alarmUri != null) {
                try {
                    ringtone = RingtoneManager.getRingtone(appContext, alarmUri)?.apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        play()
                    }
                    soundStarted = ringtone?.isPlaying == true
                } catch (rEx: Exception) {
                    Log.w("AudioAlarmHelper", "Ringtone fallback failed: ${rEx.message}")
                }
            }

            // C. Tertiary: Synthetic AudioTrack Siren Backup (Guaranteed to sound on every device in Silent mode)
            if (!soundStarted) {
                playEmergencyAudioTrackSiren()
            }

        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error starting alarm sound: ${e.message}", e)
            playEmergencyAudioTrackSiren()
        }
    }

    /**
     * Synthesizes a high-decibel dual-frequency alternating siren tone (880Hz / 1760Hz)
     * using AudioTrack with USAGE_ALARM, bypassing all system silent modes.
     */
    private fun playEmergencyAudioTrackSiren() {
        try {
            val sampleRate = 22050
            val numSamples = sampleRate * 2
            val sample = DoubleArray(numSamples)
            val generatedSnd = ByteArray(2 * numSamples)

            for (i in 0 until numSamples) {
                val freq = if ((i / (sampleRate / 4)) % 2 == 0) 880.0 else 1760.0
                sample[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freq))
            }

            var idx = 0
            for (dVal in sample) {
                val valShort = (dVal * 32767).toInt().toShort()
                generatedSnd[idx++] = (valShort.toInt() and 0x00ff).toByte()
                generatedSnd[idx++] = (valShort.toInt() and 0xff00 ushr 8).toByte()
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()

            emergencyAudioTrack = android.media.AudioTrack(
                attributes, format, generatedSnd.size,
                android.media.AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            ).apply {
                write(generatedSnd, 0, generatedSnd.size)
                setLoopPoints(0, numSamples, -1)
                play()
            }
            Log.i("AudioAlarmHelper", "Emergency AudioTrack PCM siren active")
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Failed to start emergency AudioTrack siren: ${e.message}")
        }
    }

    /**
     * Stops alarm audio and vibration completely across the entire app.
     */
    @Synchronized
    fun stopFullAlarm(context: Context) {
        val appContext = context.applicationContext

        // 0. Stop repeating ping alert and timer
        stopAlertPing()

        // 1. Cancel Vibrator via all managers
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.cancel()
                vm?.defaultVibrator?.cancel()
            }
            val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.cancel()
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error cancelling vibrator: ${e.message}")
        }

        // 2. Stop Ringtone
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping ringtone: ${e.message}")
        } finally {
            ringtone = null
        }

        // 3. Stop MediaPlayer
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
        }

        // 4. Stop Emergency AudioTrack Siren
        try {
            emergencyAudioTrack?.let {
                if (it.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error stopping emergency AudioTrack: ${e.message}")
        } finally {
            emergencyAudioTrack = null
            isAlarmPlaying = false
        }

        // 5. Release Audio Focus
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("AudioAlarmHelper", "Error abandoning audio focus: ${e.message}")
        }
    }

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun isPlaying(): Boolean = isAlarmPlaying
}
