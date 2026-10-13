package com.example.chatsnap.restrictor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemRestrictedAppBinding

class RestrictedAppAdapter(
    private var appList: List<RestrictedAppModel>,
    private val onToggleRestriction: (RestrictedAppModel, Boolean) -> Unit
) : RecyclerView.Adapter<RestrictedAppAdapter.AppViewHolder>() {

    private var filteredList: List<RestrictedAppModel> = appList

    fun updateData(newList: List<RestrictedAppModel>) {
        appList = newList
        filteredList = newList
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            appList
        } else {
            appList.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemRestrictedAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(filteredList[position])
    }

    override fun getItemCount(): Int = filteredList.size

    inner class AppViewHolder(private val binding: ItemRestrictedAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RestrictedAppModel) {
            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName
            binding.ivAppIcon.setImageDrawable(item.icon)

            // Detach listener before setting checked state to avoid triggers
            binding.switchRestrict.setOnCheckedChangeListener(null)
            binding.switchRestrict.isChecked = item.isRestricted

            binding.switchRestrict.setOnCheckedChangeListener { _, isChecked ->
                item.isRestricted = isChecked
                onToggleRestriction(item, isChecked)
            }

            binding.root.setOnClickListener {
                binding.switchRestrict.isChecked = !binding.switchRestrict.isChecked
            }
        }
    }
}
