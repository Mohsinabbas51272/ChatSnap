package com.example.chatsnap

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.AiChatAdapter
import com.example.chatsnap.databinding.ActivityAiChatBinding
import com.example.chatsnap.models.AiMessage
import com.example.chatsnap.utils.GeminiApiClient
import kotlinx.coroutines.launch

class AiChatActivity : BaseActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val messages = mutableListOf<AiMessage>()
    private lateinit var adapter: AiChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.toolbar.menu.add("Change API Key").setOnMenuItemClickListener {
            showApiKeySetupDialog()
            true
        }

        // Setup custom API key from local preferences if present
        val savedKey = getSharedPreferences("groq_prefs", Context.MODE_PRIVATE).getString("api_key", null)
        if (!savedKey.isNullOrEmpty()) {
            GeminiApiClient.apiKey = savedKey
        }

        setupRecyclerView()

        binding.btnSendPrompt.setOnClickListener {
            sendMessage()
        }

        val draftMsg = intent.getStringExtra("draft_text")
        if (!draftMsg.isNullOrEmpty()) {
            binding.etAiPrompt.setText(draftMsg)
        }
    }

    private fun showApiKeySetupDialog() {
        val input = EditText(this).apply {
            hint = "Enter Groq API Key"
            setText(GeminiApiClient.apiKey)
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Groq API Key")
            .setMessage("Enter your Groq API key. Get one free from console.groq.com")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    GeminiApiClient.apiKey = key
                    getSharedPreferences("groq_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("api_key", key)
                        .apply()
                    Toast.makeText(this, "Groq API Key saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Key cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = AiChatAdapter(messages) { responseText ->
            val options = arrayOf("Copy to Clipboard", "Use as draft")
            AlertDialog.Builder(this)
                .setTitle("Response Options")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("AI response", responseText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            intent.putExtra("response_draft", responseText)
                            setResult(RESULT_OK, intent)
                            finish()
                        }
                    }
                }
                .show()
        }
        binding.rvAiMessages.layoutManager = LinearLayoutManager(this)
        binding.rvAiMessages.adapter = adapter
    }

    private fun sendMessage() {
        val prompt = binding.etAiPrompt.text.toString().trim()
        if (prompt.isEmpty()) return

        messages.add(AiMessage(content = prompt, isUser = true))
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvAiMessages.smoothScrollToPosition(messages.size - 1)
        binding.etAiPrompt.setText("")

        binding.typingIndicator.visibility = View.VISIBLE

        val history = messages.dropLast(1).map { Pair(it.content, it.isUser) }

        lifecycleScope.launch {
            val response = GeminiApiClient.generateResponse(prompt, history)
            binding.typingIndicator.visibility = View.GONE
            messages.add(AiMessage(content = response, isUser = false))
            adapter.notifyItemInserted(messages.size - 1)
            binding.rvAiMessages.smoothScrollToPosition(messages.size - 1)
        }
    }
}
