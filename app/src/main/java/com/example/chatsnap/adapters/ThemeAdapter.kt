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
            ThemeManager.AppTheme.DEFAULT -> android.graphics.Color.parseColor("#5908f7")
            ThemeManager.AppTheme.FULL_BLACK -> android.graphics.Color.BLACK
            ThemeManager.AppTheme.FULL_WHITE -> android.graphics.Color.WHITE
            ThemeManager.AppTheme.EMERALD_GREEN -> android.graphics.Color.parseColor("#008B5B")
            ThemeManager.AppTheme.SAKURA_PINK -> android.graphics.Color.parseColor("#E05275")
            ThemeManager.AppTheme.NEON_PINK -> android.graphics.Color.parseColor("#FF1493")
            ThemeManager.AppTheme.MAROON -> android.graphics.Color.parseColor("#B8324C")
            ThemeManager.AppTheme.MAGENTA -> android.graphics.Color.parseColor("#0086D6")
            ThemeManager.AppTheme.TEAL -> android.graphics.Color.parseColor("#008E8E")
            ThemeManager.AppTheme.CORAL -> android.graphics.Color.parseColor("#FF0000")
        }
        holder.binding.viewColorPreview.setBackgroundColor(previewColor)
        
        holder.itemView.setOnClickListener { onThemeSelected(theme) }
    }

    override fun getItemCount(): Int = themes.size
}
