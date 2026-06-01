package com.example.chatsnap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.adapters.FriendRequestAdapter
import com.example.chatsnap.adapters.UserAdapter
import com.example.chatsnap.databinding.FragmentPeopleBinding
import com.example.chatsnap.models.FriendRequest
import com.example.chatsnap.models.User
import com.example.chatsnap.utils.SearchableFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class PeopleFragment : Fragment(), SearchableFragment {
    private var _binding: FragmentPeopleBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var userAdapter: UserAdapter
    private lateinit var requestAdapter: FriendRequestAdapter
    private var currentUserName: String = "User"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) syncContacts()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPeopleBinding.inflate(inflater, container, false)
        return binding.root
    }

    private lateinit var friendsAdapter: UserAdapter
    private lateinit var allFriendsAdapter: UserAdapter
    private lateinit var squadsAdapter: UserAdapter
    private var friendsList: List<String> = emptyList()
    private var phoneContactsSet: Set<String> = emptySet()
    private var sentRequests: Set<String> = emptySet()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupRecyclerViews()
        setupQuickActions()
        loadCurrentUserName()
        listenForFriends()
        listenForGroups()
        listenForSentRequests()
        listenForFriendRequests()
        listenForAcceptedRequests()

        checkContactsPermission()
    }

    override fun onSearch(query: String) {
        if (query.length >= 2) searchUsers(query) else syncContacts()
    }

    private fun listenForSentRequests() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("friendRequests")
            .whereEqualTo("fromId", uid)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    sentRequests = snapshot.documents.mapNotNull { it.getString("toId") }.toSet()
                    updateStatusMap()
                }
            }
    }

    private fun loadCurrentUserName() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener {
            currentUserName = it.getString("name") ?: "User"
        }
    }

    private fun setupRecyclerViews() {
        friendsAdapter = UserAdapter(emptyList(), mapOf(), horizontal = true,
            onAddClick = { },
            onChatClick = { friend -> navigateToChat(friend.uid, friend.name) }
        )
        binding.rvTopFriends.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvTopFriends.adapter = friendsAdapter

        allFriendsAdapter = UserAdapter(emptyList(), mapOf(), horizontal = false,
            onAddClick = { },
            onChatClick = { friend -> navigateToChat(friend.uid, friend.name) }
        )
        binding.rvFriends.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFriends.adapter = allFriendsAdapter

        squadsAdapter = UserAdapter(emptyList(), mapOf(), horizontal = true,
            onAddClick = { },
            onChatClick = { squad -> navigateToGroupChat(squad.uid, squad.name) }
        )
        binding.rvSquads.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvSquads.adapter = squadsAdapter

        userAdapter = UserAdapter(emptyList(), mapOf(), 
            onAddClick = { sendFriendRequest(it) },
            onChatClick = { friend -> navigateToChat(friend.uid, friend.name) }
        )
        // Fixed: Renamed rvPeople to rvSuggestions to match fragment_people.xml
        binding.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSuggestions.adapter = userAdapter

        requestAdapter = FriendRequestAdapter(emptyList(), 
            onAccept = { acceptFriendRequest(it) },
            onReject = { rejectFriendRequest(it) }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = requestAdapter
    }
    
    private fun listenForGroups() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("groups")
            .whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, _ ->
                if (_binding != null && snapshot != null) {
                    val groups = snapshot.documents.mapNotNull { doc ->
                        User(uid = doc.id, name = doc.getString("name") ?: "Squad", profileImageUrl = doc.getString("groupImageUrl") ?: "")
                    }
                    squadsAdapter.updateData(groups)
                    binding.layoutSquads.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
                }
            }
    }

    private fun setupQuickActions() {
        binding.btnSyncContacts.setOnClickListener { checkContactsPermission() }
        binding.btnMyQr.setOnClickListener { 
            startActivity(android.content.Intent(requireContext(), QRProfileActivity::class.java))
        }
        binding.btnScanQr.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), QRScannerActivity::class.java))
        }

        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTabFriends -> {
                        binding.layoutFriendsView.visibility = View.VISIBLE
                        binding.layoutAddView.visibility = View.GONE
                    }
                    R.id.btnTabAdd -> {
                        binding.layoutFriendsView.visibility = View.GONE
                        binding.layoutAddView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun navigateToChat(id: String, name: String) {
        val intent = android.content.Intent(requireContext(), ChatActivity::class.java)
        intent.putExtra("receiverId", id)
        intent.putExtra("receiverName", name)
        startActivity(intent)
    }

    private fun navigateToGroupChat(groupId: String, groupName: String) {
        val intent = android.content.Intent(requireContext(), ChatActivity::class.java)
        intent.putExtra("groupId", groupId)
        intent.putExtra("groupName", groupName)
        startActivity(intent)
    }

    private fun listenForFriends() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, _ ->
                if (_binding != null && snapshot != null) {
                    @Suppress("UNCHECKED_CAST")
                    val friends = snapshot.get("friends") as? List<String> ?: emptyList()
                    friendsList = friends
                    updateStatusMap()
                    loadFriendsData(friends)
                }
            }
    }

    private fun updateStatusMap() {
        val map = mutableMapOf<String, String>()
        friendsList.forEach { map[it] = "FRIEND" }
        sentRequests.forEach { map[it] = "SENT" }
        userAdapter.updateStatus(map)
        friendsAdapter.updateStatus(map)
        allFriendsAdapter.updateStatus(map)
    }

    private fun loadFriendsData(ids: List<String>) {
        if (ids.isEmpty()) {
            friendsAdapter.updateData(emptyList(), getStatusMap())
            allFriendsAdapter.updateData(emptyList(), getStatusMap())
            binding.layoutTopFriends.visibility = View.GONE
            return
        }
        firestore.collection("users").whereIn("uid", ids.take(30)).get().addOnSuccessListener { snapshot ->
            if (_binding != null) {
                val users = snapshot.toObjects(User::class.java)
                friendsAdapter.updateData(users, getStatusMap(), friendsList)
                
                // Sort all friends alphabetically ascending by name
                val sortedUsers = users.sortedBy { it.name.lowercase() }
                allFriendsAdapter.updateData(sortedUsers, getStatusMap(), friendsList)
                
                binding.layoutTopFriends.visibility = View.VISIBLE
            }
        }
    }

    private fun getStatusMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        friendsList.forEach { map[it] = "FRIEND" }
        sentRequests.forEach { map[it] = "SENT" }
        return map
    }

    private fun listenForFriendRequests() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("friendRequests")
            .whereEqualTo("toId", uid)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                if (_binding != null && snapshot != null) {
                    val requests = snapshot.toObjects(FriendRequest::class.java)
                    requestAdapter.updateData(requests)
                    binding.layoutRequests.visibility = if (requests.isEmpty()) View.GONE else View.VISIBLE
                }
            }
    }

    private fun sendFriendRequest(user: User) {
        val currentUid = auth.currentUser?.uid ?: return
        if (currentUid == user.uid) return
        val requestId = "${currentUid}_${user.uid}"
        val request = FriendRequest(requestId = requestId, fromId = currentUid, fromName = currentUserName, toId = user.uid, status = "PENDING")
        firestore.collection("friendRequests").document(requestId).set(request)
            .addOnSuccessListener { Toast.makeText(context, "Request sent!", Toast.LENGTH_SHORT).show() }
    }

    private fun acceptFriendRequest(request: FriendRequest) {
        val currentUid = auth.currentUser?.uid ?: return
        val batch = firestore.batch()
        batch.update(firestore.collection("friendRequests").document(request.requestId), "status", "ACCEPTED")
        batch.update(firestore.collection("users").document(currentUid), "friends", FieldValue.arrayUnion(request.fromId))
        batch.commit().addOnSuccessListener {
            Toast.makeText(context, "Friend added!", Toast.LENGTH_SHORT).show()
            navigateToChat(request.fromId, request.fromName)
        }
    }

    private fun listenForAcceptedRequests() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("friendRequests")
            .whereEqualTo("fromId", uid)
            .whereEqualTo("status", "ACCEPTED")
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documents?.forEach { doc ->
                    val toId = doc.getString("toId") ?: return@forEach
                    firestore.collection("users").document(uid).update("friends", FieldValue.arrayUnion(toId))
                        .addOnSuccessListener { doc.reference.update("status", "COMPLETED") }
                }
            }
    }

    private fun rejectFriendRequest(request: FriendRequest) {
        firestore.collection("friendRequests").document(request.requestId).update("status", "REJECTED")
    }

    private fun checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            syncContacts()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun syncContacts() {
        if (_binding == null) return
        binding.progressBar.visibility = View.VISIBLE
        val contactNumbers = mutableSetOf<String>()
        val cursor = requireContext().contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val num = it.getString(idx).replace("\\D".toRegex(), "")
                if (num.isNotEmpty()) contactNumbers.add(num)
            }
        }
        phoneContactsSet = contactNumbers
        firestore.collection("users").get().addOnSuccessListener { snapshot ->
            if (_binding == null) return@addOnSuccessListener
            binding.progressBar.visibility = View.GONE
            val matched = snapshot.toObjects(User::class.java).filter { 
                contactNumbers.contains(it.phone.replace("\\D".toRegex(), "")) && it.uid != auth.currentUser?.uid
            }
            userAdapter.updateData(matched, getStatusMap(), friendsList)
        }
    }

    private fun searchUsers(query: String) {
        firestore.collection("users").whereGreaterThanOrEqualTo("name", query).whereLessThanOrEqualTo("name", query + "\uf8ff")
            .get().addOnSuccessListener { snapshot ->
                if (_binding != null) {
                    val users = snapshot.toObjects(User::class.java).filter { it.uid != auth.currentUser?.uid }
                    userAdapter.updateData(users, getStatusMap(), friendsList)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
