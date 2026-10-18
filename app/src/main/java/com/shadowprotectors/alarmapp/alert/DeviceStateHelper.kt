package com.shadowprotectors.alarmapp.alert

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class DeviceAudioState(
    val isHeadsetConnected: Boolean,
    val isDndActive: Boolean,
    val isSilentOrVibrate: Boolean,
    val ringerMode: Int,
    val interruptionFilter: Int
) {
    fun summary(): String {
        val headset = if (isHeadsetConnected) "Headphones Connected" else "Device Speaker"
        val ringer = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Normal"
        }
        val dnd = if (isDndActive) "DND Active" else "DND Inactive"
        return "$headset | Ringer: $ringer | $dnd"
    }
}

object DeviceStateHelper {

    fun getDeviceAudioState(context: Context): DeviceAudioState {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. Check for wired or Bluetooth headset/earphones
        val isHeadset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        }

        // 2. Check Do Not Disturb (DND) status
        val interruptionFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        val isDndActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }

        // 3. Check Ringer mode (Silent, Vibrate, Normal)
        val ringerMode = audioManager.ringerMode
        val isSilentOrVibrate = ringerMode != AudioManager.RINGER_MODE_NORMAL

        return DeviceAudioState(
            isHeadsetConnected = isHeadset,
            isDndActive = isDndActive,
            isSilentOrVibrate = isSilentOrVibrate,
            ringerMode = ringerMode,
            interruptionFilter = interruptionFilter
        )
    }
}
