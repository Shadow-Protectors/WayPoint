package com.shadowprotectors.alarmapp.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.shadowprotectors.alarmapp.data.AppDatabase
import com.shadowprotectors.alarmapp.data.SavedLocation
import com.shadowprotectors.alarmapp.data.SavedLocationRepository
import com.shadowprotectors.alarmapp.databinding.ActivityManageSavedLocationsBinding
import kotlinx.coroutines.launch

class ManageSavedLocationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageSavedLocationsBinding
    private lateinit var repository: SavedLocationRepository
    private lateinit var adapter: SavedLocationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageSavedLocationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.appBarLayout.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val db = AppDatabase.getInstance(this)
        repository = SavedLocationRepository(db.savedLocationDao())

        setupRecyclerView()
        observeLocations()
    }

    private fun setupRecyclerView() {
        adapter = SavedLocationAdapter(
            showOptions = true,
            onItemClick = { location ->
                showRenameDialog(location)
            },
            onOptionsClick = { location, anchorView ->
                showPopupMenu(location, anchorView)
            }
        )

        binding.rvManageSavedLocations.apply {
            layoutManager = LinearLayoutManager(this@ManageSavedLocationsActivity)
            adapter = this@ManageSavedLocationsActivity.adapter
        }
    }

    private fun observeLocations() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getAllLocationsFlow().collect { list ->
                    if (list.isEmpty()) {
                        binding.layoutEmptyState.isVisible = true
                        binding.rvManageSavedLocations.isVisible = false
                    } else {
                        binding.layoutEmptyState.isVisible = false
                        binding.rvManageSavedLocations.isVisible = true
                        adapter.submitList(list)
                    }
                }
            }
        }
    }

    private fun showPopupMenu(location: SavedLocation, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menu.add("Rename")
        popup.menu.add("Delete")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.title) {
                "Rename" -> {
                    showRenameDialog(location)
                    true
                }
                "Delete" -> {
                    showDeleteConfirmationDialog(location)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(location: SavedLocation) {
        val textInputLayout = TextInputLayout(this).apply {
            hint = "Custom Location Name"
            setHintTextColor(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_secondary)))
            setBoxStrokeColorStateList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_accent)))
            boxBackgroundColor = androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.surface_elevated)
        }
        val input = TextInputEditText(textInputLayout.context).apply {
            setText(location.customName)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, com.shadowprotectors.alarmapp.R.color.text_muted))
            setSelection(text?.length ?: 0)
            setSingleLine(true)
        }
        textInputLayout.addView(input)

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(textInputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Location")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        repository.update(location.copy(customName = newName))
                        Toast.makeText(this@ManageSavedLocationsActivity, "Renamed to '$newName'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(location: SavedLocation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Location")
            .setMessage("Are you sure you want to delete '${location.customName}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.delete(location)
                    Toast.makeText(this@ManageSavedLocationsActivity, "Location deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
