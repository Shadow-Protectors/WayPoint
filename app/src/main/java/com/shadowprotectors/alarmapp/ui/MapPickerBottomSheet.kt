package com.shadowprotectors.alarmapp.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shadowprotectors.alarmapp.databinding.LayoutMapPickerBinding
import com.shadowprotectors.alarmapp.util.GeocodingHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import java.util.Locale

class MapPickerBottomSheet : BottomSheetDialogFragment() {


    private var _binding: LayoutMapPickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var geocodingHelper: GeocodingHelper
    private lateinit var searchAdapter: SearchResultAdapter

    private var currentCenterLat = 20.5937
    private var currentCenterLng = 78.9629
    private var currentPlaceName = "Choose a destination"

    private var locationCancellation: com.google.android.gms.tasks.CancellationTokenSource? = null
    private val locationSettings = com.shadowprotectors.alarmapp.util.LocationSettingsGate(
        this, { requireContext() }, lifecycle
    ) { if (_binding != null) fetchInitialLocation() }

    private fun requestCurrentLocation() {
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (granted) locationSettings.ensureEnabled()
        else locationPermissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) locationSettings.ensureEnabled()
        else context?.let { android.widget.Toast.makeText(it, "Location permission denied. Search or move the map to choose your stop.", android.widget.Toast.LENGTH_LONG).show() }
    }

    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var searchJob: Job? = null
    private var reverseGeocodeJob: Job? = null
    private lateinit var repository: com.shadowprotectors.alarmapp.data.SavedLocationRepository

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val prefs = context.getSharedPreferences("${context.packageName}_osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue = context.packageName
        geocodingHelper = GeocodingHelper(context)
        val db = com.shadowprotectors.alarmapp.data.AppDatabase.getInstance(context)
        repository = com.shadowprotectors.alarmapp.data.SavedLocationRepository(db.savedLocationDao())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = false
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutMapPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initLat = (savedInstanceState ?: arguments)?.getDouble(ARG_INIT_LAT, Double.NaN) ?: Double.NaN
        val initLng = (savedInstanceState ?: arguments)?.getDouble(ARG_INIT_LNG, Double.NaN) ?: Double.NaN

        val hasCustomInit = initLat.isFinite() && initLng.isFinite() && initLat in -90.0..90.0 && initLng in -180.0..180.0
        if (hasCustomInit) {
            currentCenterLat = initLat
            currentCenterLng = initLng
        }

        setupMap()
        setupSearch()
        setupListeners()

        if (hasCustomInit) {
            binding.mapView.controller.setCenter(GeoPoint(initLat, initLng))
            binding.mapView.controller.setZoom(18.0)
            binding.tvSelectedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", initLat, initLng)
            onMapMoved()
        } else {
            onMapMoved()
            requestCurrentLocation()
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // A regional overview until a real location or search result is available.
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(currentCenterLat, currentCenterLng))

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    onMapMoved()
                    return true
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    onMapMoved()
                    return true
                }
            })
        }
    }

    private fun onMapMoved() {
        val viewBinding = _binding ?: return
        val center = viewBinding.mapView.mapCenter
        currentCenterLat = center.latitude
        currentCenterLng = center.longitude

        currentPlaceName = String.format(Locale.US, "Location %.5f, %.5f", currentCenterLat, currentCenterLng)
        binding.tvSelectedPlaceName.text = currentPlaceName
        binding.tvSelectedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", currentCenterLat, currentCenterLng)

        // Debounce reverse geocoding
        reverseGeocodeJob?.cancel()
        reverseGeocodeJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            binding.pbGeocoding.isVisible = true
            val resolvedName = geocodingHelper.reverseGeocode(currentCenterLat, currentCenterLng)
            currentPlaceName = resolvedName
            binding.tvSelectedPlaceName.text = resolvedName
            binding.pbGeocoding.isVisible = false
        }
    }

    private fun setupSearch() {
        searchAdapter = SearchResultAdapter { result ->
            locationCancellation?.cancel()
            binding.fabMyLocation.isEnabled = true
            binding.cardSuggestions.isVisible = false
            binding.etSearchQuery.setText("")
            binding.btnClearSearch.isVisible = false

            currentCenterLat = result.latitude
            currentCenterLng = result.longitude
            currentPlaceName = result.title

            binding.tvSelectedPlaceName.text = result.title
            binding.tvSelectedCoords.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", result.latitude, result.longitude)

            binding.mapView.controller.animateTo(GeoPoint(result.latitude, result.longitude))
            binding.mapView.controller.setZoom(18.0)
        }

        binding.rvSearchSuggestions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.isVisible = query.isNotEmpty()

                searchJob?.cancel()
                if (query.length >= 2) {
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(350)
                        val results = geocodingHelper.searchPlaces(query, currentCenterLat, currentCenterLng)
                        if (results.isNotEmpty()) {
                            searchAdapter.submitList(results)
                            binding.cardSuggestions.isVisible = true
                        } else {
                            binding.cardSuggestions.isVisible = false
                        }
                    }
                } else {
                    binding.cardSuggestions.isVisible = false
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            binding.cardSuggestions.isVisible = false
        }
    }

    private fun setupListeners() {
        binding.btnBackMap.setOnClickListener { dismiss() }
        binding.fabMyLocation.setOnClickListener { requestCurrentLocation() }
        binding.btnZoomIn.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.mapView.controller.zoomOut() }
        binding.tvMapAttribution.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright")))
        }

        binding.btnConfirmDestination.setOnClickListener {
            showNameInputDialog()
        }
    }

    private fun showNameInputDialog() {
        if (activeDialog?.isShowing == true) return
        val selectedLat = currentCenterLat
        val selectedLng = currentCenterLng
        val selectedName = currentPlaceName
        val context = requireContext()
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
            hint = "e.g. Home, Office"
            setHintTextColor(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_secondary)))
            setBoxStrokeColorStateList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_accent)))
            boxBackgroundColor = androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.surface_elevated)
        }
        val input = com.google.android.material.textfield.TextInputEditText(textInputLayout.context).apply {
            setText(selectedName)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_muted))
            setSelection(text?.length ?: 0)
            setSingleLine(true)
        }
        textInputLayout.addView(input)

        val cbSave = com.google.android.material.checkbox.MaterialCheckBox(context).apply {
            text = "Save to My Locations"
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_primary))
            buttonTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_accent))
            isChecked = true
            val dp8 = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(dp8, dp8, dp8, dp8)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(textInputLayout)
            addView(cbSave)
        }

        activeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle("Name this location")
            .setView(container)
            .setPositiveButton("Confirm") { _, _ ->
                val customName = input.text.toString().trim()
                val finalName = customName.ifEmpty { selectedName }
                val shouldSave = cbSave.isChecked

                if (shouldSave) {
                    processSaveLocationAndConfirm(finalName, selectedLat, selectedLng)
                } else {
                    deliverDestination(finalName, selectedLat, selectedLng)
                    dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processSaveLocationAndConfirm(name: String, lat: Double, lng: Double) {
        launchSave {
            val existingNear = repository.findNear(lat, lng, 50.0)
            if (existingNear != null) {
                activeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Nearby Saved Location Found")
                    .setMessage("A location near this point already exists:\n'${existingNear.customName}'\n\nWould you like to use the existing saved location or save as a new location?")
                    .setPositiveButton("Use Existing") { _, _ ->
                        launchSave {
                            repository.recordUsage(existingNear.id)
                            deliverDestination(existingNear.customName, existingNear.latitude, existingNear.longitude)
                            dismiss()
                        }
                    }
                    .setNegativeButton("Save as New") { _, _ ->
                        launchSave {
                            val newLoc = com.shadowprotectors.alarmapp.data.SavedLocation(
                                customName = name,
                                latitude = lat,
                                longitude = lng
                            )
                            repository.insert(newLoc)
                            deliverDestination(name, lat, lng)
                            dismiss()
                        }
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            } else {
                val newLoc = com.shadowprotectors.alarmapp.data.SavedLocation(
                    customName = name,
                    latitude = lat,
                    longitude = lng
                )
                repository.insert(newLoc)
                deliverDestination(name, lat, lng)
                dismiss()
            }
        }
    }

    private fun launchSave(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnConfirmDestination.isEnabled = false
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Could not save destination", error)
                context?.let { android.widget.Toast.makeText(it, "Could not save location. Try again or uncheck Save to My Locations.", android.widget.Toast.LENGTH_LONG).show() }
            } finally {
                _binding?.btnConfirmDestination?.isEnabled = true
            }
        }
    }

    private fun deliverDestination(name: String, lat: Double, lng: Double) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
            putString("name", name)
            putDouble("latitude", lat)
            putDouble("longitude", lng)
        })
    }

    private fun fetchInitialLocation() {
        locationCancellation?.cancel()
        val cancellation = com.google.android.gms.tasks.CancellationTokenSource()
        locationCancellation = cancellation
        binding.fabMyLocation.isEnabled = false
        val request = com.google.android.gms.location.CurrentLocationRequest.Builder()
            .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(10_000L)
            .setDurationMillis(20_000L)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(requireActivity())
                .getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { location ->
                    val viewBinding = _binding ?: return@addOnSuccessListener
                    if (cancellation.token.isCancellationRequested) return@addOnSuccessListener
                    if (location != null) {
                        viewBinding.mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
                        viewBinding.mapView.controller.setZoom(18.0)
                        onMapMoved()
                    } else showLocationUnavailable()
                }
                .addOnFailureListener { if (_binding != null) showLocationUnavailable() }
                .addOnCompleteListener { if (!cancellation.token.isCancellationRequested) _binding?.fabMyLocation?.isEnabled = true }
        } catch (e: SecurityException) {
            binding.fabMyLocation.isEnabled = true
            showLocationUnavailable()
        }
    }

    private fun showLocationUnavailable() {
        context?.let { android.widget.Toast.makeText(it, "Could not get a current location. Retry outdoors, search, or move the map to choose your stop.", android.widget.Toast.LENGTH_LONG).show() }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble(ARG_INIT_LAT, currentCenterLat)
        outState.putDouble(ARG_INIT_LNG, currentCenterLng)
    }

    override fun onDestroyView() {
        locationCancellation?.cancel()
        activeDialog?.dismiss()
        activeDialog = null
        searchJob?.cancel()
        reverseGeocodeJob?.cancel()
        _binding?.mapView?.onDetach()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "destination_selection"
        const val TAG = "MapPickerBottomSheet"
        private const val ARG_INIT_LAT = "ARG_INIT_LAT"
        private const val ARG_INIT_LNG = "ARG_INIT_LNG"

        fun newInstance(initialLat: Double? = null, initialLng: Double? = null): MapPickerBottomSheet {
            val fragment = MapPickerBottomSheet()
            if (initialLat != null && initialLng != null) {
                val args = Bundle().apply {
                    putDouble(ARG_INIT_LAT, initialLat)
                    putDouble(ARG_INIT_LNG, initialLng)
                }
                fragment.arguments = args
            }
            return fragment
        }
    }
}
