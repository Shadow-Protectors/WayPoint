package com.shadowprotectors.alarmapp.alert

import android.content.Context
import android.util.Log
import com.shadowprotectors.alarmapp.engine.AlertProfile
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.engine.ThresholdType

enum class AlertLevel(val rank: Int, val description: String) {
    NONE(0, "En Route: Monitoring"),
    LEVEL_1_SILENT(1, "Urgency 1: Heads-Up (5.0 km)"),
    LEVEL_2_VIBRATE(2, "Urgency 2: Proximity Alert (2.0 km)"),
    LEVEL_3_VOICE(3, "Urgency 3: Approach Guidance (1.0 km)"),
    LEVEL_4_FULL_ALARM(4, "Urgency 4: Destination Arrival (0.5 km)")
}

interface AlertCallback {
    fun onAlertTriggered(level: AlertLevel, distanceKm: Double, destinationName: String)
}

class AlertManager(
    private val context: Context,
    private val voiceAlertHelper: VoiceAlertHelper,
    private val callback: AlertCallback? = null
) {
    var level1ThresholdKm: Double = 5.0
    var level2ThresholdKm: Double = 2.0
    var level3ThresholdKm: Double = 1.0
    var level4ThresholdKm: Double = 0.5

    private var highestTriggeredLevel = AlertLevel.NONE
    var isAlarmDismissedByUser: Boolean = false
        private set

    private var hasTriggeredRecedingWarning = false
    private var lastLevel4EnforceTimeMs = 0L
    private var lastLevel3FireTimeMs = 0L

    fun reset() {
        highestTriggeredLevel = AlertLevel.NONE
        isAlarmDismissedByUser = false
        hasTriggeredRecedingWarning = false
        lastLevel4EnforceTimeMs = 0L
        lastLevel3FireTimeMs = 0L
        AudioAlarmHelper.stopFullAlarm(context)
        voiceAlertHelper.stop()
    }

    /**
     * Evaluates current distance & direction state, progressing alert urgency levels
     * and managing multi-channel composed alerts based on real-time device context.
     */
    fun processState(
        distanceKm: Double,
        etaMinutes: Int?,
        speedKmh: Double,
        approachState: ApproachState,
        destinationName: String,
        profile: AlertProfile
    ): AlertLevel {
        val now = System.currentTimeMillis()

        // 1. Handle moving away (RECEDING) warning
        if (approachState == ApproachState.RECEDING && distanceKm > 1.0) {
            if (!hasTriggeredRecedingWarning) {
                hasTriggeredRecedingWarning = true
                voiceAlertHelper.speakRecedingAlert(destinationName)
                AudioAlarmHelper.playLevel2Alert(context)
            }
            return highestTriggeredLevel
        } else if (approachState == ApproachState.APPROACHING) {
            hasTriggeredRecedingWarning = false
        }

        // 2. Only freeze alert progression for detour loop if outside 1.0 km proximity
        if (approachState == ApproachState.CIRCLING_LOOP && distanceKm > 1.0) {
            return highestTriggeredLevel
        }

        // 3. Evaluate metric based on mode profile: ETA minutes for Train, Distance km for Bus/Car
        val metricValue = if (profile.thresholdType == ThresholdType.ETA) {
            etaMinutes?.toDouble() ?: Double.MAX_VALUE
        } else {
            distanceKm
        }

        // 4. Persistence & Re-fire logic for Urgency 4:
        // If user already dismissed the alarm, stay quiet.
        if (isAlarmDismissedByUser && metricValue <= profile.level4Value) {
            return AlertLevel.LEVEL_4_FULL_ALARM
        }

        // If Urgency 4 is active and not dismissed, periodically enforce max volume and continuous alert
        if (highestTriggeredLevel == AlertLevel.LEVEL_4_FULL_ALARM && !isAlarmDismissedByUser) {
            if (now - lastLevel4EnforceTimeMs > 8000L) { // Every 8 seconds
                lastLevel4EnforceTimeMs = now
                AudioAlarmHelper.startFullAlarm(context)
            }
            return AlertLevel.LEVEL_4_FULL_ALARM
        }

        val targetLevel = when {
            metricValue <= profile.level4Value -> AlertLevel.LEVEL_4_FULL_ALARM
            metricValue <= profile.level3Value -> AlertLevel.LEVEL_3_VOICE
            metricValue <= profile.level2Value -> AlertLevel.LEVEL_2_VIBRATE
            metricValue <= profile.level1Value -> AlertLevel.LEVEL_1_SILENT
            else -> AlertLevel.NONE
        }

        // 5. Fire alerts when escalating urgency level
        if (targetLevel.rank > highestTriggeredLevel.rank) {
            val previousLevel = highestTriggeredLevel
            highestTriggeredLevel = targetLevel

            // If we jumped straight to Level 4 without hitting Level 3 first (e.g. testing or high speed)
            if (targetLevel == AlertLevel.LEVEL_4_FULL_ALARM && previousLevel.rank < AlertLevel.LEVEL_3_VOICE.rank) {
                voiceAlertHelper.speakApproachAlert(destinationName, distanceKm)
            }

            if (targetLevel == AlertLevel.LEVEL_3_VOICE) {
                lastLevel3FireTimeMs = now
            } else if (targetLevel == AlertLevel.LEVEL_4_FULL_ALARM) {
                lastLevel4EnforceTimeMs = now
            }

            fireComposedAlert(targetLevel, distanceKm, destinationName)
        } else if (targetLevel == AlertLevel.LEVEL_3_VOICE && approachState == ApproachState.APPROACHING) {
            // Re-fire voice approach guidance if traveler has remained in approach zone for > 60 seconds
            if (now - lastLevel3FireTimeMs > 60000L) {
                lastLevel3FireTimeMs = now
                voiceAlertHelper.speakApproachAlert(destinationName, distanceKm)
            }
        }

        return highestTriggeredLevel
    }

    /**
     * Composes and dispatches parallel sensory channels based on real-time device state.
     */
    private fun fireComposedAlert(level: AlertLevel, distanceKm: Double, destinationName: String) {
        val deviceState = DeviceStateHelper.getDeviceAudioState(context)
        Log.i("AlertManager", "Firing ${level.name} | Context: ${deviceState.summary()}")

        when (level) {
            AlertLevel.LEVEL_1_SILENT -> {
                // Noticeable double haptic pulse + gentle chime tone
                AudioAlarmHelper.playLevel1Alert(context)
            }
            AlertLevel.LEVEL_2_VIBRATE -> {
                // Heavy repeating pulse train + dual sonar attention ping
                AudioAlarmHelper.playLevel2Alert(context)
            }
            AlertLevel.LEVEL_3_VOICE -> {
                // High-priority TTS announcement (with pre-chime and audio focus ducking) + companion haptic pulse
                voiceAlertHelper.speakApproachAlert(destinationName, distanceKm)
                AudioAlarmHelper.playLevel1Alert(context)
            }
            AlertLevel.LEVEL_4_FULL_ALARM -> {
                // Parallel multi-channel blast:
                // 1. Force STREAM_ALARM to max volume + relentless vibration loop + siren tone
                AudioAlarmHelper.startFullAlarm(context)
                // 2. Speak simultaneous arrival announcement
                voiceAlertHelper.speakArrivalAlert(destinationName)
            }
            AlertLevel.NONE -> {}
        }
        callback?.onAlertTriggered(level, distanceKm, destinationName)
    }

    /**
     * Triggers a comprehensive test of all sensory channels (haptics, audio focus, chime, and TTS).
     */
    fun triggerTestAlert() {
        val deviceState = DeviceStateHelper.getDeviceAudioState(context)
        Log.i("AlertManager", "Running Alert System Test | ${deviceState.summary()}")
        AudioAlarmHelper.playLevel2Alert(context)
        voiceAlertHelper.speakTestAlert()
    }

    fun stopAlarm() {
        isAlarmDismissedByUser = true
        AudioAlarmHelper.stopFullAlarm(context)
        voiceAlertHelper.stop()
    }
}
