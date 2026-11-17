package com.shadowprotectors.alarmapp

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.EditText
import com.shadowprotectors.alarmapp.alert.*
import com.shadowprotectors.alarmapp.engine.*
import com.shadowprotectors.alarmapp.ui.MapPickerBottomSheet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkflowRegressionTest {
    @org.junit.After fun closeDatabase() {
        val field = com.shadowprotectors.alarmapp.data.AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? com.shadowprotectors.alarmapp.data.AppDatabase)?.close()
        field.set(null, null)
        com.shadowprotectors.alarmapp.service.ServiceEventBus.resetState()
    }

    @Test fun progressiveAlertsEscalateAndDismissalStaysSilent() {
        val context = RuntimeEnvironment.getApplication()
        val voice = VoiceAlertHelper(context)
        val fired = mutableListOf<AlertLevel>()
        val alerts = AlertManager(context, voice, object : AlertCallback {
            override fun onAlertTriggered(level: AlertLevel, distanceKm: Double, destinationName: String) { fired.add(level) }
        })
        val profile = AlertProfileResolver.resolve(TripMode.BUS_CAR)
        fun update(distance: Double, direction: ApproachState = ApproachState.APPROACHING) =
            alerts.processState(distance, null, 30.0, direction, "Home", profile)
        assertEquals(AlertLevel.NONE, update(6.0))
        assertEquals(AlertLevel.LEVEL_1_SILENT, update(5.0))
        assertEquals(AlertLevel.LEVEL_2_VIBRATE, update(2.0))
        assertEquals(AlertLevel.LEVEL_3_VOICE, update(1.0))
        update(1.0)
        assertEquals(3, fired.size)
        assertEquals(AlertLevel.LEVEL_4_FULL_ALARM, update(0.5))
        alerts.stopAlarm()
        update(3.0, ApproachState.RECEDING)
        update(0.4)
        assertEquals(4, fired.size)
        assertTrue(alerts.isAlarmDismissedByUser)
        alerts.reset()
        assertEquals(AlertLevel.NONE, update(Double.NaN))
        voice.shutdown()
    }

    @Test fun awakeAtEitherEarlyLevelSuppressesAllLaterAlertsUntilNewTrip() {
        val context = RuntimeEnvironment.getApplication()
        for (distance in listOf(5.0, 2.0)) {
            val voice = VoiceAlertHelper(context)
            val fired = mutableListOf<AlertLevel>()
            val alerts = AlertManager(context, voice, object : AlertCallback {
                override fun onAlertTriggered(level: AlertLevel, distanceKm: Double, destinationName: String) {
                    fired.add(level)
                }
            })
            val profile = AlertProfileResolver.resolve(TripMode.BUS_CAR)
            fun update(km: Double, direction: ApproachState = ApproachState.APPROACHING) =
                alerts.processState(km, null, 40.0, direction, "Home", profile)
            update(distance)
            alerts.stopAlarm()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            update(3.0, ApproachState.RECEDING)
            update(1.0)
            update(0.4)
            assertEquals(1, fired.size)
            assertFalse(AudioAlarmHelper.isPlaying())
            assertTrue(alerts.isAlarmDismissedByUser)
            alerts.reset()
            update(5.0)
            assertEquals(2, fired.size)
            assertFalse(alerts.isAlarmDismissedByUser)
            alerts.stopAlarm()
            voice.shutdown()
        }
    }

    @Test fun trackingNotificationOffersAwakeActionUntilAcknowledged() {
        val context = RuntimeEnvironment.getApplication()
        val intent = android.app.PendingIntent.getService(context, 99,
            android.content.Intent(context, com.shadowprotectors.alarmapp.service.LocationTrackingService::class.java)
                .setAction(com.shadowprotectors.alarmapp.service.LocationTrackingService.ACTION_STOP_ALARM),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val active = com.shadowprotectors.alarmapp.util.NotificationHelper.buildTrackingNotification(
            context, "Home", 5.0, 10, intent, intent, false)
        assertTrue(active.actions.any { it.title.toString().contains("awake") })
        val silenced = com.shadowprotectors.alarmapp.util.NotificationHelper.buildTrackingNotification(
            context, "Home", 5.0, 10, intent, intent, true)
        assertFalse(silenced.actions.any { it.title.toString().contains("awake") })
        assertTrue(silenced.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("Alerts off"))
    }

    @Test fun stoppingVoiceCancelsDelayedSpeech() {
        val voice = VoiceAlertHelper(RuntimeEnvironment.getApplication())
        voice.onInit(TextToSpeech.SUCCESS)
        val field = VoiceAlertHelper::class.java.getDeclaredField("tts").apply { isAccessible = true }
        val tts = field.get(voice) as TextToSpeech
        voice.speakApproachAlert("Home", 1.0)
        voice.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(tts).lastSpokenText)
        voice.shutdown()
    }

    @Test fun voiceSpeaksDestinationWhenNotCancelled() {
        val voice = VoiceAlertHelper(RuntimeEnvironment.getApplication())
        voice.onInit(TextToSpeech.SUCCESS)
        val field = VoiceAlertHelper::class.java.getDeclaredField("tts").apply { isAccessible = true }
        voice.speakApproachAlert("Home", 1.0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(shadowOf(field.get(voice) as TextToSpeech).lastSpokenText.contains("Home"))
        voice.shutdown()
    }

    @Test fun pickerBackReturnsWithoutChangingDestination() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val before = activity.findViewById<EditText>(R.id.etDestName).text.toString()
        activity.findViewById<android.view.View>(R.id.btnOpenMapPicker).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        val picker = activity.supportFragmentManager.findFragmentByTag(MapPickerBottomSheet.TAG)!!
        picker.requireView().findViewById<android.view.View>(R.id.btnBackMap).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        assertNull(activity.supportFragmentManager.findFragmentByTag(MapPickerBottomSheet.TAG))
        assertEquals(before, activity.findViewById<EditText>(R.id.etDestName).text.toString())
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun confirmingDestinationDoesNotOpenLegacyDatabase() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        kotlinx.coroutines.runBlocking {
            com.shadowprotectors.alarmapp.data.AppDatabase.getInstance(activity).savedLocationDao().getAllLocations()
        }
        activity.supportFragmentManager.setFragmentResult(MapPickerBottomSheet.RESULT_KEY, android.os.Bundle().apply {
            putString("name", "Home")
            putDouble("latitude", 9.92)
            putDouble("longitude", 78.11)
        })
        assertEquals("Home", activity.findViewById<EditText>(R.id.etDestName).text.toString())
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
