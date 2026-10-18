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
