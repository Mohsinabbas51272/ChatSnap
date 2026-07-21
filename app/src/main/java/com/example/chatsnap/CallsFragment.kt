package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.CallsAdapter
import com.example.chatsnap.databinding.FragmentCallsBinding
import com.example.chatsnap.models.Call
import com.example.chatsnap.utils.SearchableFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CallsFragment : Fragment(), SearchableFragment {
    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: CallsAdapter
    private val callsList = mutableListOf<Call>()
    private var filterMode = "ALL"
    private val followedFriends = mutableListOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupTabs()
        setupClearButton()
        loadCallHistory()
        listenToFollowedFriends()
    }

    private fun setupClearButton() {
        // Clear button is now in the main header, invoked via showClearCallsDialog()
    }

    fun showClearCallsDialog() {
        val uid = auth.currentUser?.uid ?: return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Call History")
            .setMessage("Are you sure you want to clear your call history? This will remove call entries where you are caller or receiver.")
            .setPositiveButton("Clear") { _, _ -> clearCallHistory(uid) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearCallHistory(uid: String) {
        // Query for calls where callerId == uid or receiverId == uid
        val callsRef = firestore.collection("calls")

        // Fetch both sets then delete in batches
        val toDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()

        callsRef.whereEqualTo("callerId", uid).get()
            .addOnSuccessListener { snap ->
                snap.documents.forEach { toDelete.add(it.reference) }
                callsRef.whereEqualTo("receiverId", uid).get()
                    .addOnSuccessListener { snap2 ->
                        snap2.documents.forEach { ref ->
                            // avoid duplicates
                            if (!toDelete.contains(ref.reference)) toDelete.add(ref.reference)
                        }
                        if (toDelete.isEmpty()) {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setMessage("No call history to clear.")
                                .setPositiveButton("OK", null)
                                .show()
                            return@addOnSuccessListener
                        }

                        val chunks = toDelete.chunked(500)
                        var completed = 0
                        for (chunk in chunks) {
                            val batch = firestore.batch()
                            chunk.forEach { batch.delete(it) }
                            batch.commit().addOnSuccessListener {
                                completed++
                                if (completed == chunks.size) {
                                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                        .setMessage("Call history cleared.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                    // refresh local list
                                    callsList.clear()
                                    updateUI()
                                }
                            }.addOnFailureListener { e ->
                                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setMessage("Failed to clear: ${e.message}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("CALLS_CLEAR", "Failed to query receiver calls: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("CALLS_CLEAR", "Failed to query caller calls: ${e.message}")
            }
    }

    private fun listenToFollowedFriends() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val friends = doc.get("friends") as? List<String> ?: emptyList()
                    followedFriends.clear()
                    followedFriends.addAll(friends)
                    updateUI()
                }
            }
    }

    private fun setupTabs() {
        binding.toggleGroup.check(R.id.btnTabAll)
        binding.toggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                filterMode = if (checkedId == R.id.btnTabMissed) "MISSED" else "ALL"
                updateUI()
            }
        }
    }

    private fun triggerCall(call: Call) {
        val uid = auth.currentUser?.uid ?: return
        val partnerId = if (call.callerId == uid) call.receiverId else call.callerId
        val partnerName = if (call.callerId == uid) call.receiverName else call.callerName
        
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { userDoc ->
                val callerName = userDoc.getString("name") ?: "A Friend"
                val intent = Intent(requireContext(), CallActivity::class.java).apply {
                    putExtra("receiverId", partnerId)
                    putExtra("receiverName", partnerName)
                    putExtra("callerName", callerName)
                    putExtra("callType", call.type)
                    putExtra("isCaller", true)
                    val ids = listOf(uid, partnerId).sorted()
                    putExtra("channelName", "${ids[0]}_${ids[1]}")
                }
                startActivity(intent)
                updateUI() // reset swipe visual
            }
            .addOnFailureListener {
                val intent = Intent(requireContext(), CallActivity::class.java).apply {
                    putExtra("receiverId", partnerId)
                    putExtra("receiverName", partnerName)
                    putExtra("callerName", "A Friend")
                    putExtra("callType", call.type)
                    putExtra("isCaller", true)
                    val ids = listOf(uid, partnerId).sorted()
                    putExtra("channelName", "${ids[0]}_${ids[1]}")
                }
                startActivity(intent)
                updateUI() // reset swipe visual
            }
    }

    private fun setupRecyclerView() {
        val uid = auth.currentUser?.uid ?: ""
        adapter = CallsAdapter(emptyList(), uid, followedFriends) { call ->
            triggerCall(call)
        }
        binding.rvCalls.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCalls.adapter = adapter

        val swipeHandler = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val list = adapter.getCallsList()
                if (position in list.indices) {
                    val call = list[position]
                    triggerCall(call)
                }
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvCalls)
    }

    private fun loadCallHistory() {
        val uid = auth.currentUser?.uid ?: return
        
        firestore.collection("calls")
            .whereEqualTo("callerId", uid)
            .addSnapshotListener { snapshot, _ -> processSnapshot(snapshot) }
            
        firestore.collection("calls")
            .whereEqualTo("receiverId", uid)
            .addSnapshotListener { snapshot, _ -> processSnapshot(snapshot) }
    }

    private fun processSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot?) {
        snapshot?.documents?.forEach { doc ->
            doc.toObject(Call::class.java)?.let { call ->
                val index = callsList.indexOfFirst { it.timestamp == call.timestamp && it.callerId == call.callerId }
                if (index != -1) callsList[index] = call else callsList.add(call)
            }
        }
        updateUI()
    }

    private fun updateUI() {
        if (_binding == null) return
        val uid = auth.currentUser?.uid ?: ""
        val filtered = callsList.filter { 
            if (filterMode == "MISSED") {
                it.receiverId == uid && it.status == "missed"
            } else true
        }.sortedByDescending { it.timestamp }
        
        adapter.updateData(filtered, followedFriends)
        binding.tvNoCalls.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSearch(query: String) {
        if (_binding == null) return
        val uid = auth.currentUser?.uid ?: ""
        val filtered = callsList.filter { 
            (it.callerName.contains(query, true) || it.receiverName.contains(query, true)) &&
            (if (filterMode == "MISSED") it.receiverId == uid && it.status == "missed" else true)
        }.sortedByDescending { it.timestamp }
        adapter.updateData(filtered, followedFriends)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
