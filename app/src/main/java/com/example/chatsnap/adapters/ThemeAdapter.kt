package com.example.chatsnap.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chatsnap.databinding.ItemThemeBinding
import com.example.chatsnap.utils.ThemeManager

class ThemeAdapter(
    private val themes: List<ThemeManager.AppTheme>,
    private val onThemeSelected: (ThemeManager.AppTheme) -> Unit
) : RecyclerView.Adapter<ThemeAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemThemeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemThemeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val theme = themes[position]
        holder.binding.tvThemeName.text = theme.displayName
        
        val previewColor = when (theme) {
            ThemeManager.AppTheme.DEFAULT -> android.graphics.Color.parseColor("#5C6BC0")
            ThemeManager.AppTheme.FULL_BLACK -> android.graphics.Color.BLACK
            ThemeManager.AppTheme.FULL_WHITE -> android.graphics.Color.WHITE
            ThemeManager.AppTheme.EMERALD_GREEN -> android.graphics.Color.parseColor("#006064")
            ThemeManager.AppTheme.SAKURA_PINK -> android.graphics.Color.parseColor("#F06292")
            ThemeManager.AppTheme.NEON_PINK -> android.graphics.Color.parseColor("#FFB6C1")
            ThemeManager.AppTheme.MAROON -> android.graphics.Color.parseColor("#7F2020")
            ThemeManager.AppTheme.MAGENTA -> android.graphics.Color.parseColor("#00B4C5")
            ThemeManager.AppTheme.TEAL -> android.graphics.Color.parseColor("#2FA084")
            ThemeManager.AppTheme.CORAL -> android.graphics.Color.parseColor("#E36A6A")
        }
        holder.binding.viewColorPreview.setCardBackgroundColor(previewColor)
        
        holder.itemView.setOnClickListener { onThemeSelected(theme) }
    }

    override fun getItemCount(): Int = themes.size
}
