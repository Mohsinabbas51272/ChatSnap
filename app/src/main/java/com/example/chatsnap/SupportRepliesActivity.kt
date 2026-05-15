package com.example.chatsnap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.SupportAdapter
import com.example.chatsnap.databinding.ActivitySupportRepliesBinding
import com.example.chatsnap.models.SupportRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class SupportRepliesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySupportRepliesBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: SupportAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportRepliesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecycler()
        listenForReplies()
    }

    private fun setupRecycler() {
        adapter = SupportAdapter(emptyList())
        binding.rvSupportRequests.layoutManager = LinearLayoutManager(this)
        binding.rvSupportRequests.adapter = adapter
    }

    private fun listenForReplies() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("supportRequests")
            .whereEqualTo("uid", uid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val requests = snapshot.toObjects(SupportRequest::class.java)
                    adapter.updateData(requests)
                }
            }
    }
}
