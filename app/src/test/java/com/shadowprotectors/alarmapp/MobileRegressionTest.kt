package com.shadowprotectors.alarmapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.location.LocationSettingsResponse
import com.shadowprotectors.alarmapp.util.LocationSettingsGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileRegressionTest {
    @Test fun selectedPlaceIncludesStreetAndLocalityInsteadOfOnlyBuildingNumber() {
        val address = android.location.Address(java.util.Locale.ENGLISH).apply {
            featureName = "198"
            thoroughfare = "Ismail Street"
            subLocality = "Town Hall"
            locality = "Coimbatore"
        }
        val helper = com.shadowprotectors.alarmapp.util.GeocodingHelper(RuntimeEnvironment.getApplication())
        assertEquals("Ismail Street, Town Hall, Coimbatore", helper.formatSelectedAddress(address))
    }

    @Test @Config(qualifiers = "night")
    fun mapSearchAndConfirmationUseDarkSurfacesWithReadableText() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val picker = com.shadowprotectors.alarmapp.ui.MapPickerBottomSheet.newInstance(9.92, 78.11)
        picker.show(activity.supportFragmentManager, "theme-test")
        activity.supportFragmentManager.executePendingTransactions()
        val root = picker.requireView()
        val search = root.findViewById<android.widget.EditText>(R.id.etSearchQuery)
        val searchCard = search.parent.parent as com.google.android.material.card.MaterialCardView
        assertTrue(ColorUtils.calculateContrast(search.currentTextColor, searchCard.cardBackgroundColor.defaultColor) >= 4.5)
        assertTrue(ColorUtils.calculateLuminance(searchCard.cardBackgroundColor.defaultColor) < 0.1)
        val map = root.findViewById<org.osmdroid.views.MapView>(R.id.mapView)
        assertEquals(18.0, map.zoomLevelDouble, 0.01)
        assertTrue(root.findViewById<android.widget.TextView>(R.id.tvMapAttribution).text.contains("OpenStreetMap"))
        picker.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "night")
    fun darkSurfacesAndStatusTextRemainReadable() {
        val context = RuntimeEnvironment.getApplication()
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val surfaces = listOf(R.color.surface_card, R.color.background_light, R.color.surface_elevated)
        for (surface in surfaces) {
            for (text in listOf(R.color.text_primary, R.color.text_secondary, R.color.text_accent)) {
                assertTrue("Text $text on $surface", ColorUtils.calculateContrast(color(text), color(surface)) >= 4.5)
            }
        }
        assertTrue(ColorUtils.calculateLuminance(color(R.color.surface_card)) < 0.1)
        for ((foreground, background) in listOf(
            R.color.status_green_text to R.color.status_green_bg,
            R.color.status_amber_text to R.color.status_amber_bg,
            R.color.status_red_text to R.color.status_red_bg,
            R.color.status_violet_text to R.color.status_violet_bg
        )) assertTrue(ColorUtils.calculateContrast(color(foreground), color(background)) >= 4.5)
    }

    @Test fun pendingSettingsCheckDoesNotStartTripAndSuccessResumesOnce() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val task = TaskCompletionSource<LocationSettingsResponse>()
        var starts = 0
        var checks = 0
        val gate = LocationSettingsGate(activity, { activity }, activity.lifecycle,
            checkSettings = { _, _ -> checks++; task.task }) { starts++ }
        controller.start().resume()
        gate.ensureEnabled()
        gate.ensureEnabled()
        assertEquals(1, checks)
        assertEquals(0, starts)
        task.setResult(LocationSettingsResponse(com.google.android.gms.location.LocationSettingsResult(com.google.android.gms.common.api.Status.RESULT_SUCCESS, null)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, starts)
        controller.pause().stop().destroy()
    }

    @Test fun settingsFailureWithLocationOffDoesNotStartTripAndCancelStaysStopped() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val task = TaskCompletionSource<LocationSettingsResponse>()
        var starts = 0
        val gate = LocationSettingsGate(activity, { activity }, activity.lifecycle,
            checkSettings = { _, _ -> task.task }) { starts++ }
        controller.start().resume()
        gate.ensureEnabled()
        task.setException(IllegalStateException("No resolvable settings dialog"))
        shadowOf(Looper.getMainLooper()).idle()
        val launched = shadowOf(activity).nextStartedActivityForResult
        assertEquals(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, launched.intent.action)
        activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, Intent())
        assertEquals(0, starts)
        controller.pause().stop().destroy()
    }
}
