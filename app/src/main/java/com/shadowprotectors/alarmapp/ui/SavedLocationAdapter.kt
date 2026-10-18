package com.shadowprotectors.alarmapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shadowprotectors.alarmapp.data.SavedLocation
import com.shadowprotectors.alarmapp.databinding.ItemSavedLocationBinding
import java.util.Locale

class SavedLocationAdapter(
    private val showOptions: Boolean = false,
    private val onItemClick: (SavedLocation) -> Unit,
    private val onOptionsClick: ((SavedLocation, View) -> Unit)? = null
) : ListAdapter<SavedLocation, SavedLocationAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSavedLocationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedLocation) {
            binding.tvLocationName.text = item.customName
            binding.tvLocationCoords.text = String.format(Locale.US, "Lat: %.4f, Lng: %.4f", item.latitude, item.longitude)
            binding.tvUseBadge.text = "${item.useCount}x"

            binding.ivOptions.isVisible = showOptions
            binding.ivOptions.setOnClickListener { v ->
                onOptionsClick?.invoke(item, v)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SavedLocation>() {
        override fun areItemsTheSame(oldItem: SavedLocation, newItem: SavedLocation): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SavedLocation, newItem: SavedLocation): Boolean =
            oldItem == newItem
    }
}
