package com.example.chatsnap.utils

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.example.chatsnap.databinding.DialogCustomBinding

object UIUtils {

    fun showCustomDialog(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "Confirm",
        negativeText: String = "Cancel",
        customView: View? = null,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ): AlertDialog {
        val binding = DialogCustomBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        binding.dialogTitle.text = title
        binding.dialogMessage.text = message
        binding.btnPositive.text = positiveText
        binding.btnNegative.text = negativeText

        if (customView != null) {
            binding.customViewContainer.addView(customView)
            binding.customViewContainer.visibility = View.VISIBLE
        } else {
            binding.customViewContainer.visibility = View.GONE
        }

        binding.btnPositive.setOnClickListener {
            onPositive()
            dialog.dismiss()
        }

        binding.btnNegative.setOnClickListener {
            onNegative?.invoke()
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }
}
