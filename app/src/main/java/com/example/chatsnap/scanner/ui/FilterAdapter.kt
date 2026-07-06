package com.example.chatsnap.scanner.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemFilterBinding

class FilterAdapter(
    private val items: List<ImageFilterType>,
    private val onFilterSelected: (ImageFilterType) -> Unit
) : RecyclerView.Adapter<FilterAdapter.FilterViewHolder>() {

    private var selectedFilter: ImageFilterType = ImageFilterType.ORIGINAL

    fun selectFilter(filter: ImageFilterType) {
        val oldIndex = items.indexOf(selectedFilter)
        selectedFilter = filter
        val newIndex = items.indexOf(filter)
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val binding = ItemFilterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FilterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item == selectedFilter)
    }

    override fun getItemCount(): Int = items.size

    inner class FilterViewHolder(private val binding: ItemFilterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(filter: ImageFilterType, isSelected: Boolean) {
            binding.tvFilterName.text = when (filter) {
                ImageFilterType.ORIGINAL -> "Original"
                ImageFilterType.COLOR -> "Color"
                ImageFilterType.GRAYSCALE -> "Grayscale"
                ImageFilterType.BLACK_WHITE -> "B&W"
            }

            // Set icon placeholder
            val context = binding.root.context
            val iconRes = when (filter) {
                ImageFilterType.ORIGINAL -> android.R.drawable.ic_menu_gallery
                ImageFilterType.COLOR -> android.R.drawable.ic_menu_camera
                ImageFilterType.GRAYSCALE -> android.R.drawable.ic_menu_slideshow
                ImageFilterType.BLACK_WHITE -> android.R.drawable.ic_menu_crop
            }
            binding.ivFilterIcon.setImageResource(iconRes)

            // Select highlighting colors
            val accentColor = Color.parseColor("#005DFF")
            val outlineColor = Color.parseColor("#E0E0E0")
            
            binding.cardFilter.strokeColor = if (isSelected) accentColor else outlineColor
            binding.cardFilter.strokeWidth = if (isSelected) 6 else 2
            
            binding.root.setOnClickListener {
                if (filter != selectedFilter) {
                    onFilterSelected(filter)
                }
            }
        }
    }
}
