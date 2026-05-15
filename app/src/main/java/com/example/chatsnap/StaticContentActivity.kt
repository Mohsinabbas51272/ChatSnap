package com.example.chatsnap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.chatsnap.databinding.ActivityStaticContentBinding

class StaticContentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStaticContentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaticContentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("title") ?: "Policy"
        val content = intent.getStringExtra("content") ?: "Content goes here..."

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvContent.text = content
    }
}
