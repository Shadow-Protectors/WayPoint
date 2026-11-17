package com.shadowprotectors.alarmapp

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.databinding.ActivityMainBinding
import com.shadowprotectors.alarmapp.databinding.DialogImportLinkBinding
import com.shadowprotectors.alarmapp.engine.ApproachState
import com.shadowprotectors.alarmapp.service.LocationTrackingService
import com.shadowprotectors.alarmapp.service.ServiceEventBus
import com.shadowprotectors.alarmapp.service.TrackingState
import com.shadowprotectors.alarmapp.ui.MapPickerBottomSheet
import com.shadowprotectors.alarmapp.util.GeocodingHelper
import com.shadowprotectors.alarmapp.util.LocationLinkParser
import com.shadowprotectors.alarmapp.util.ParsedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var geocodingHelper: GeocodingHelper
    private var selectedLanguageCode = "en"
    private var selectedTripMode: com.shadowprotectors.alarmapp.engine.TripMode = com.shadowprotectors.alarmapp.engine.TripMode.BUS_CAR
    private var isCurrentlyTracking = false

    private val locationSettings = com.shadowprotectors.alarmapp.util.LocationSettingsGate(
        this, { this }, lifecycle
    ) { if (!isCurrentlyTracking) startTrackingService() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            locationSettings.ensureEnabled()
        } else {
            Toast.makeText(this, "Location permission is required for travel alarm", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var savedLocationRepository: com.shadowprotectors.alarmapp.data.SavedLocationRepository
    private lateinit var savedLocationAdapter: com.shadowprotectors.alarmapp.ui.SavedLocationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        geocodingHelper = GeocodingHelper(this)

        val db = com.shadowprotectors.alarmapp.data.AppDatabase.getInstance(this)
        savedLocationRepository = com.shadowprotectors.alarmapp.data.SavedLocationRepository(db.savedLocationDao())

        // Handle edge-to-edge status bar insets properly
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.appBarLayout.setPadding(0, statusBarHeight, 0, 0)
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.coordinatorLayout.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        setupSavedLocationsRecyclerView()
        observeSavedLocations()
        setupLanguageButtons()
        supportFragmentManager.setFragmentResultListener(MapPickerBottomSheet.RESULT_KEY, this) { _, result ->
            setDestination(result.getString("name").orEmpty(), result.getDouble("latitude"), result.getDouble("longitude"))
        }
        setupListeners()
        observeTrackingState()

        // Handle shared location links from WhatsApp or Maps
        handleIncomingIntent(intent)

        // Restore last selected destination if available (no hardcoded placeholders)
        restoreSavedDestination()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    importSharedLocation(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.dataString
                if (!dataUri.isNullOrBlank()) {
                    importSharedLocation(dataUri)
                }
            }
        }
    }

    private var importJob: Job? = null

    private fun importSharedLocation(rawInput: String) {
        importJob?.cancel()
        importJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Parsing shared destination...", Toast.LENGTH_SHORT).show()
            val parsed = LocationLinkParser.parse(rawInput)
            if (parsed != null) {
                val resolvedName = geocodingHelper.reverseGeocode(parsed.latitude, parsed.longitude)
                setDestination(resolvedName, parsed.latitude, parsed.longitude)
                Toast.makeText(this@MainActivity, "Destination set to: $resolvedName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Could not extract coordinates from link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setDestination(name: String, lat: Double, lng: Double) {
        if (isCurrentlyTracking || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        binding.etDestName.setText(name)
        binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
        binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))

        // Save to SharedPreferences for app restart persistence
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("PREF_DEST_NAME", name)
            .putString("PREF_DEST_LAT_EXACT", lat.toString())
            .putString("PREF_DEST_LNG_EXACT", lng.toString())
            .apply()

        // Update landmark confirmation
        updateLandmarkConfirmation(lat, lng)
        updateUiFromTrackingState(ServiceEventBus.trackingState.value)

        // Calculate and show distance from current GPS if available
        try {
            val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !isCurrentlyTracking) {
                    val dist = com.shadowprotectors.alarmapp.engine.DistanceEngine.calculateDistanceKm(loc.latitude, loc.longitude, lat, lng)
                    binding.tvDistance.text = String.format(Locale.US, "%.2f km", dist)
                }
            }
        } catch (e: SecurityException) {}
    }

    private fun restoreSavedDestination() {
        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("PREF_DEST_NAME", null)
        val savedLat = prefs.getString("PREF_DEST_LAT_EXACT", null)?.toDoubleOrNull() ?: prefs.getFloat("PREF_DEST_LAT", -999f).toDouble()
        val savedLng = prefs.getString("PREF_DEST_LNG_EXACT", null)?.toDoubleOrNull() ?: prefs.getFloat("PREF_DEST_LNG", -999f).toDouble()

        if (!savedName.isNullOrBlank() && savedLat in -90.0..90.0 && savedLng in -180.0..180.0) {
            val lat = savedLat.toDouble()
            val lng = savedLng.toDouble()
            binding.etDestName.setText(savedName)
            binding.etLatitude.setText(String.format(Locale.US, "%.5f", lat))
            binding.etLongitude.setText(String.format(Locale.US, "%.5f", lng))
            updateLandmarkConfirmation(lat, lng)
        } else {
            // No destination saved yet: keep fields clean without dummy placeholders
            binding.etDestName.setText("")
            binding.etLatitude.setText("")
            binding.etLongitude.setText("")
            binding.cardLandmarkConfirmation.isVisible = false
        }
    }

    private var landmarkJob: Job? = null

    private fun updateLandmarkConfirmation(lat: Double, lng: Double) {
        landmarkJob?.cancel()
        landmarkJob = lifecycleScope.launch {
            val landmarkText = geocodingHelper.getLandmarkConfirmation(lat, lng)
            binding.tvLandmarkConfirmation.text = landmarkText
            binding.cardLandmarkConfirmation.isVisible = true
        }
    }

    private fun setupSavedLocationsRecyclerView() {
        savedLocationAdapter = com.shadowprotectors.alarmapp.ui.SavedLocationAdapter(
            showOptions = false,
            onItemClick = { savedLoc ->
                lifecycleScope.launch {
                    savedLocationRepository.recordUsage(savedLoc.id)
                }
                val request = com.shadowprotectors.alarmapp.data.TripStartRequest(
                    destinationName = savedLoc.customName,
                    latitude = savedLoc.latitude,
                    longitude = savedLoc.longitude,
                    tripMode = selectedTripMode,
                    languageCode = selectedLanguageCode,
                    savedLocationId = savedLoc.id
                )
                startTrip(request)
            }
        )
        binding.rvSavedLocations.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = savedLocationAdapter
        }

        binding.btnManageSavedLocations.setOnClickListener {
            val intent = Intent(this, com.shadowprotectors.alarmapp.ui.ManageSavedLocationsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun observeSavedLocations() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                savedLocationRepository.getAllLocationsFlow().collect { list ->
                    if (list.isEmpty()) {
                        binding.layoutEmptySavedLocations.isVisible = true
                        binding.rvSavedLocations.isVisible = false
                    } else {
                        binding.layoutEmptySavedLocations.isVisible = false
                        binding.rvSavedLocations.isVisible = true
                        savedLocationAdapter.submitList(list)
                    }
                }
            }
        }
    }

    private fun clearDestination() {
        importJob?.cancel()
        landmarkJob?.cancel()
        binding.etDestName.setText("")
        binding.etLatitude.setText("")
        binding.etLongitude.setText("")
        binding.cardLandmarkConfirmation.isVisible = false
        binding.tvLandmarkConfirmation.text = ""
        binding.tvDistance.text = "-- km"
        binding.tvEta.text = "--:--"

        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("PREF_DEST_NAME")
            .remove("PREF_DEST_LAT_EXACT")
            .remove("PREF_DEST_LNG_EXACT")
            .remove("PREF_DEST_LAT")
            .remove("PREF_DEST_LNG")
            .apply()

        updateUiFromTrackingState(ServiceEventBus.trackingState.value)
        Toast.makeText(this, "Destination cleared", Toast.LENGTH_SHORT).show()
    }



    private fun setupLanguageButtons() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val mutedColor = ContextCompat.getColor(this, R.color.card_stroke)
        val whiteColor = ContextCompat.getColor(this, R.color.white)
        val darkTextColor = ContextCompat.getColor(this, R.color.text_primary)

        val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
        selectedLanguageCode = prefs.getString("PREF_LANG_CODE", "en") ?: "en"

        fun updateUi(selected: String, save: Boolean = true) {
            selectedLanguageCode = selected

            if (save) {
                prefs.edit().putString("PREF_LANG_CODE", selected).apply()
                if (isCurrentlyTracking) {
                    LocationTrackingService.updateLanguage(this, selected)
                }
            }

            binding.btnLangEnglish.backgroundTintList = ColorStateList.valueOf(if (selected == "en") primaryColor else mutedColor)
            binding.btnLangEnglish.setTextColor(if (selected == "en") whiteColor else darkTextColor)

            binding.btnLangTamil.backgroundTintList = ColorStateList.valueOf(if (selected == "ta") primaryColor else mutedColor)
            binding.btnLangTamil.setTextColor(if (selected == "ta") whiteColor else darkTextColor)

            binding.btnLangHindi.backgroundTintList = ColorStateList.valueOf(if (selected == "hi") primaryColor else mutedColor)
            binding.btnLangHindi.setTextColor(if (selected == "hi") whiteColor else darkTextColor)
        }

        updateUi(selectedLanguageCode, save = false)

        binding.btnLangEnglish.setOnClickListener { updateUi("en") }
        binding.btnLangTamil.setOnClickListener { updateUi("ta") }
        binding.btnLangHindi.setOnClickListener { updateUi("hi") }
    }

    private fun setupListeners() {
        // Handle Custom Destination Name changes in real-time
        binding.etDestName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val customName = s?.toString()?.trim() ?: ""
                if (customName.isNotEmpty()) {
                    val prefs = getSharedPreferences("travel_alarm_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("PREF_DEST_NAME", customName).apply()
                    if (isCurrentlyTracking) {
                        LocationTrackingService.updateDestinationName(this@MainActivity, customName)
                    }
                }
            }
        })

        // 0. Drag-down Swipe to Refresh GPS & Location Status
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary, R.color.primary_light, R.color.accent)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshGpsAndLocationState(showToast = true)
        }

        // Clear destination fields & reset stored preferences
        binding.btnClearDestination.setOnClickListener {
            clearDestination()
        }

        // 1. In-App Map Picker
        binding.btnOpenMapPicker.setOnClickListener {
            val currentLat = binding.etLatitude.text.toString().toDoubleOrNull()
            val currentLng = binding.etLongitude.text.toString().toDoubleOrNull()
            val mapPicker = MapPickerBottomSheet.newInstance(currentLat, currentLng)
            mapPicker.show(supportFragmentManager, MapPickerBottomSheet.TAG)
        }

        // 2. Paste / Import Maps Link Dialog
        binding.btnImportLink.setOnClickListener {
            showImportLinkDialog()
        }

        // 3. Toggle Tracking Button (Start)
        binding.btnToggleTracking.setOnClickListener {
            if (isCurrentlyTracking) {
                stopTrackingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // 4. Explicit Stop Tracking Button
        binding.btnSilenceAlerts.setOnClickListener {
            LocationTrackingService.stopAlarm(this)
        }
        binding.btnStopTracking.setOnClickListener {
            stopTrackingService()
        }

        binding.btnHelp.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_content)
                .setPositiveButton(R.string.help_close, null)
                .show()
        }
    }

    private fun refreshGpsAndLocationState(showToast: Boolean = false) {
        val currentState = ServiceEventBus.trackingState.value
        updateUiFromTrackingState(currentState)

        val latStr = binding.etLatitude.text.toString()
        val lngStr = binding.etLongitude.text.toString()
        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()

        if (lat != null && lng != null && !isCurrentlyTracking) {
            try {
                val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        val dist = com.shadowprotectors.alarmapp.engine.DistanceEngine.calculateDistanceKm(loc.latitude, loc.longitude, lat, lng)
                        binding.tvDistance.text = String.format(Locale.US, "%.2f km", dist)
                    }
                }
            } catch (e: SecurityException) {}
        }

        binding.swipeRefreshLayout.isRefreshing = false
        if (showToast) {
            Toast.makeText(this, "GPS status & distance refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGpsAndLocationState(showToast = false)
    }

    private fun showImportLinkDialog() {
        val dialogBinding = DialogImportLinkBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        var parseJob: Job? = null
        var resolvedLocation: ParsedLocation? = null
        var resolvedName: String? = null

        fun triggerParse(input: String) {
            parseJob?.cancel()
            resolvedLocation = null
            resolvedName = null
            dialogBinding.btnConfirmImport.isEnabled = false
            val text = input.trim()
            if (text.isEmpty()) {
                dialogBinding.layoutParsingStatus.isVisible = false
                dialogBinding.cardResolvedPreview.isVisible = false
                return
            }

            dialogBinding.layoutParsingStatus.isVisible = true
            dialogBinding.tvParsingStatus.text = "Resolving link coordinates..."
            dialogBinding.cardResolvedPreview.isVisible = false

            parseJob = lifecycleScope.launch {
                delay(300)
                val parsed = LocationLinkParser.parse(text)
                if (parsed != null) {
                    resolvedLocation = parsed
                    val name = geocodingHelper.reverseGeocode(parsed.latitude, parsed.longitude)
                    resolvedName = name
                    dialogBinding.btnConfirmImport.isEnabled = true

                    dialogBinding.layoutParsingStatus.isVisible = false
                    dialogBinding.tvResolvedTitle.text = name
                    dialogBinding.tvResolvedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", parsed.latitude, parsed.longitude)
                    dialogBinding.cardResolvedPreview.isVisible = true
                } else {
                    dialogBinding.layoutParsingStatus.isVisible = true
                    dialogBinding.tvParsingStatus.text = "No coordinates found in link"
                }
            }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        dialogBinding.btnPasteClipboard.setOnClickListener {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                dialogBinding.etLinkInput.setText(clipText)
            }
        }

        dialogBinding.etLinkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                triggerParse(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialogBinding.btnCancelImport.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmImport.setOnClickListener {
            val location = resolvedLocation
            if (location != null) {
                val name = resolvedName ?: "Imported Stop"
                setDestination(name, location.latitude, location.longitude)
                Toast.makeText(this, "Destination set: $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please paste a valid Google Maps link or coordinates", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.setOnDismissListener { parseJob?.cancel() }
        dialogBinding.btnConfirmImport.isEnabled = false
        dialog.show()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableList()
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing && Manifest.permission.ACCESS_COARSE_LOCATION !in missing) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (missing.isEmpty()) {
            locationSettings.ensureEnabled()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun startTrip(request: com.shadowprotectors.alarmapp.data.TripStartRequest) {
        if (isCurrentlyTracking) {
            Toast.makeText(this, "Stop the current trip before choosing another destination", Toast.LENGTH_SHORT).show()
            return
        }
        setDestination(request.destinationName, request.latitude, request.longitude)
        selectedTripMode = request.tripMode
        selectedLanguageCode = request.languageCode

        checkPermissionsAndStart()
    }

    private fun startTrackingService() {
        val name = binding.etDestName.text.toString().trim().ifEmpty { "Destination" }
        val latStr = binding.etLatitude.text.toString()
        val lngStr = binding.etLongitude.text.toString()

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()

        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Toast.makeText(this, "Please enter valid Latitude & Longitude", Toast.LENGTH_SHORT).show()
            return
        }

        val request = com.shadowprotectors.alarmapp.data.TripStartRequest(
            destinationName = name,
            latitude = lat,
            longitude = lng,
            tripMode = selectedTripMode,
            languageCode = selectedLanguageCode
        )

        LocationTrackingService.startService(this, request)
        Toast.makeText(this, "Trip started", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackingService() {
        LocationTrackingService.stopService(this)
        com.shadowprotectors.alarmapp.alert.AudioAlarmHelper.stopFullAlarm(this)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
        ServiceEventBus.resetState()
        updateUiFromTrackingState(TrackingState())
        Toast.makeText(this, "Trip stopped", Toast.LENGTH_SHORT).show()
    }

    private fun observeTrackingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceEventBus.trackingState.collect { state ->
                    updateUiFromTrackingState(state)
                }
            }
        }
    }

    private fun updateUiFromTrackingState(state: TrackingState) {
        isCurrentlyTracking = state.isTracking
        binding.btnOpenMapPicker.isEnabled = !state.isTracking
        binding.btnImportLink.isEnabled = !state.isTracking
        binding.btnClearDestination.isEnabled = !state.isTracking
        val uiState = resolveBusUiState(state)
        renderBusUiState(uiState)
    }

    private fun resolveBusUiState(trackingState: TrackingState): com.shadowprotectors.alarmapp.engine.BusUiState {
        val destName = binding.etDestName.text.toString().trim()
        val latStr = binding.etLatitude.text.toString().trim()
        val lngStr = binding.etLongitude.text.toString().trim()

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Disabled
        }

        if (trackingState.isTracking) {
            return if (trackingState.alertLevel == AlertLevel.LEVEL_4_FULL_ALARM && !trackingState.alertsSilenced) {
                com.shadowprotectors.alarmapp.engine.BusUiState.LimitReached(trackingState.destinationName)
            } else if (trackingState.distanceKm == Double.MAX_VALUE || trackingState.distanceKm < 0.0) {
                com.shadowprotectors.alarmapp.engine.BusUiState.Partial("Acquiring GPS location fix…")
            } else {
                com.shadowprotectors.alarmapp.engine.BusUiState.Offline(
                    destinationName = trackingState.destinationName,
                    distanceKm = trackingState.distanceKm,
                    alertLevel = trackingState.alertLevel,
                    approachState = trackingState.approachState
                )
            }
        }

        if (destName.isEmpty() && latStr.isEmpty() && lngStr.isEmpty()) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Empty
        }

        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()
        if (latStr.isNotEmpty() && (lat == null || !lat.isFinite() || lat < -90.0 || lat > 90.0)) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Error("Invalid Latitude (-90 to +90)")
        }
        if (lngStr.isNotEmpty() && (lng == null || !lng.isFinite() || lng < -180.0 || lng > 180.0)) {
            return com.shadowprotectors.alarmapp.engine.BusUiState.Error("Invalid Longitude (-180 to +180)")
        }

        return com.shadowprotectors.alarmapp.engine.BusUiState.Idle
    }

    private fun renderBusUiState(state: com.shadowprotectors.alarmapp.engine.BusUiState) {
        val tracking = ServiceEventBus.trackingState.value
        binding.btnSilenceAlerts.isVisible = tracking.isTracking
        binding.btnSilenceAlerts.isEnabled = !tracking.alertsSilenced
        binding.btnSilenceAlerts.text = if (tracking.alertsSilenced) "Alerts off for this trip" else "I'm awake - stop alerts"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvAlertLevelBadge.isVisible = tracking.isTracking
        binding.tvAlertLevelBadge.text = if (tracking.alertsSilenced) "Alerts off" else "Monitoring"
        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_elevated))
        when (state) {
            is com.shadowprotectors.alarmapp.engine.BusUiState.Idle -> {
                binding.btnToggleTracking.isVisible = true
                binding.btnToggleTracking.text = "Start alarm"
                binding.btnToggleTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                binding.btnStopTracking.isVisible = false

                binding.tvStatus.text = "Ready to start"
                if (binding.tvDistance.text.isNullOrBlank()) {
                    binding.tvDistance.text = "-- km"
                }
                if (!tracking.isTracking) {
                    binding.tvEta.text = "--:--"
                }
                binding.tvApproachBadge.text = "Idle"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Empty -> {
                renderBusUiState(com.shadowprotectors.alarmapp.engine.BusUiState.Idle)
                binding.tvStatus.text = "Choose a destination"
                binding.tvDistance.text = "-- km"
                binding.tvEta.text = "--:--"
                binding.tvApproachBadge.text = "Empty"
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Loading -> {
                binding.tvStatus.text = state.message
                binding.tvApproachBadge.text = "Loading"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_accent))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_elevated))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Error -> {
                binding.tvStatus.text = state.message
                binding.tvApproachBadge.text = "Error"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Partial -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.btnStopTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatus.text = state.message
                binding.tvDistance.text = "Acquiring…"
                binding.tvEta.text = "Acquiring GPS fix…"
                binding.tvApproachBadge.text = "Locating GPS"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Offline -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.btnStopTracking.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatus.text = "Tracking to ${state.destinationName}"
                binding.tvDistance.text = String.format(Locale.US, "%.2f km", state.distanceKm)

                val currentState = ServiceEventBus.trackingState.value
                binding.tvEta.text = formatEta(currentState.etaMinutes)

                when (state.approachState) {
                    ApproachState.APPROACHING -> {
                        binding.tvApproachBadge.text = "Approaching"
                        binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                        binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                    }
                    ApproachState.CIRCLING_LOOP -> {
                        binding.tvApproachBadge.text = "Detour"
                        binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                        binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                    }
                    ApproachState.RECEDING -> {
                        binding.tvApproachBadge.text = "Moving away"
                        binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                        binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                    }
                    ApproachState.UNCERTAIN -> {
                        binding.tvApproachBadge.text = "Locating..."
                        binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
                    }
                }

                when (if (currentState.alertsSilenced) AlertLevel.NONE else state.alertLevel) {
                    AlertLevel.LEVEL_4_FULL_ALARM -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                        binding.tvAlertLevelBadge.text = "Wake-up alarm"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                    }
                    AlertLevel.LEVEL_3_VOICE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_violet_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_violet_text))
                        binding.tvAlertLevelBadge.text = "Voice reminder"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_violet_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_violet_bg))
                    }
                    AlertLevel.LEVEL_2_VIBRATE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                        binding.tvAlertLevelBadge.text = "Proximity alert"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
                    }
                    AlertLevel.LEVEL_1_SILENT -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                        binding.tvAlertLevelBadge.text = "Heads-up"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green_text))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
                    }
                    AlertLevel.NONE -> {
                        binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                        binding.tvAlertLevelBadge.text = if (currentState.alertsSilenced) "Alerts off" else "Monitoring"
                        binding.tvAlertLevelBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        binding.tvAlertLevelBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_elevated))
                    }
                }
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.Disabled -> {
                binding.btnToggleTracking.isVisible = !isCurrentlyTracking
                binding.btnStopTracking.isVisible = isCurrentlyTracking
                binding.tvStatus.text = "Location is off"
                binding.tvDistance.text = "-- km"
                binding.tvEta.text = "--:--"
                binding.tvApproachBadge.text = "GPS Off"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }

            is com.shadowprotectors.alarmapp.engine.BusUiState.LimitReached -> {
                binding.btnToggleTracking.isVisible = false
                binding.btnStopTracking.isVisible = true
                binding.tvStatus.text = "Near ${state.destinationName}"
                binding.tvDistance.text = String.format(Locale.US, "%.2f km", tracking.distanceKm)
                binding.tvEta.text = formatEta(tracking.etaMinutes)
                binding.tvAlertLevelBadge.text = "Wake-up alarm"
                binding.tvApproachBadge.text = "ALARM ACTIVE"
                binding.tvApproachBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red_text))
                binding.tvApproachBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
                binding.cardTrackingStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            }
        }
    }

    /** Formats measured motion without substituting a fixed road speed. */
    private fun formatEta(etaMinutes: Int?): String {
        val effectiveMinutes = etaMinutes ?: return "Waiting for movement"
        return when {
            effectiveMinutes < 1  -> "Now"
            effectiveMinutes < 60 -> "${effectiveMinutes} min"
            else -> {
                val hrs  = effectiveMinutes / 60
                val mins = effectiveMinutes % 60
                String.format(Locale.US, "%dh %02dm", hrs, mins)
            }
        }
    }
}
