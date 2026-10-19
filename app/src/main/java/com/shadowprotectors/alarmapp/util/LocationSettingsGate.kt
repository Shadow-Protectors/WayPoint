package com.shadowprotectors.alarmapp.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/** Permission and device location settings are separate gates. Register before STARTED. */
class LocationSettingsGate(
    caller: ActivityResultCaller,
    private val context: () -> Context,
    private val lifecycle: Lifecycle,
    private val checkSettings: (Context, LocationSettingsRequest) -> com.google.android.gms.tasks.Task<com.google.android.gms.location.LocationSettingsResponse> = { ctx, request ->
        LocationServices.getSettingsClient(ctx).checkLocationSettings(request)
    },
    private val onReady: () -> Unit
) {
    private var checking = false
    private val resolution = caller.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        checking = false
        if (result.resultCode == Activity.RESULT_OK) ensureEnabled(allowResolution = false)
        else unavailable()
    }
    private val settings = caller.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checking = false
        if (isEnabled(context())) deliverReady() else unavailable()
    }

    fun ensureEnabled(allowResolution: Boolean = true) {
        if (checking || lifecycle.currentState == Lifecycle.State.DESTROYED) return
        checking = true
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build())
            .setAlwaysShow(true)
            .build()
        checkSettings(context(), request)
            .addOnSuccessListener {
                checking = false
                if (lifecycle.currentState != Lifecycle.State.DESTROYED) deliverReady()
            }
            .addOnFailureListener { error ->
                checking = false
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@addOnFailureListener
                if (allowResolution && error is ResolvableApiException) {
                    checking = true
                    resolution.launch(IntentSenderRequest.Builder(error.resolution).build())
                } else if (isEnabled(context())) {
                    deliverReady()
                } else if (allowResolution) {
                    // Devices without a resolvable Play Services dialog still resume on return.
                    checking = true
                    settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else unavailable()
            }
    }

    private fun deliverReady() {
        // A service with location access must start while its activity is in the foreground.
        if (lifecycle.currentState == Lifecycle.State.RESUMED) onReady()
        else lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                lifecycle.removeObserver(this)
                onReady()
            }
            override fun onDestroy(owner: LifecycleOwner) { lifecycle.removeObserver(this) }
        })
    }

    private fun unavailable() {
        Toast.makeText(context(), "Location is off. Enable it to use your current location or start an alarm.", Toast.LENGTH_LONG).show()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
