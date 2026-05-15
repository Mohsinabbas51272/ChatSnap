package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.chatsnap.databinding.ActivityMainBinding
import com.example.chatsnap.utils.SearchableFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import coil.load
import kotlinx.coroutines.*

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var currentFragment: Fragment? = null
    private var statusJob: Job? = null

    private data class StatusUpdate(
        val userId: String,
        val name: String,
        val status: String,
        val photoUrl: String?,
        val isMe: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        if (auth.currentUser == null) {
            val loginIntent = Intent(this, LoginActivity::class.java)
            loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(loginIntent)
            finish()
            return
        }

        setupBottomNavigation()
        loadHeaderProfile()
        syncSystemNavigationColor()
        startRollingStatus()
        checkAppLock()
        syncThemeFromFirestore()
        markMessagesAsDelivered()
        listenForIncomingCalls()
        setupHeaderSearch()
        requestNotificationPermission()
        initializeFcmToken()
        loadAnnouncement()
        checkMaintenanceMode()

        if (savedInstanceState == null) {
            loadFragment(ChatsFragment(), "Chats")
            updateNavUI(binding.navChats)
        }

        binding.btnSearch.setOnClickListener {
            toggleSearchHeader()
        }

        binding.btnMainFab.setOnClickListener {
            handleFabClick()
        }

        binding.ivHeaderProfile.setOnClickListener {
            loadFragment(SettingsFragment(), "Settings")
            updateNavUI(binding.navSettings)
        }

        binding.btnEditStatus.setOnClickListener {
            showSetStatusDialog()
        }

        setupBackNavigation()
    }

    private fun setupHeaderSearch() {
        binding.etHeaderSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                (currentFragment as? SearchableFragment)?.onSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun toggleSearchHeader() {
        if (binding.etHeaderSearch.visibility == View.VISIBLE) {
            binding.etHeaderSearch.visibility = View.GONE
            binding.tvHeaderTitle.visibility = View.VISIBLE
            binding.etHeaderSearch.setText("")
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etHeaderSearch.windowToken, 0)
        } else {
            binding.etHeaderSearch.visibility = View.VISIBLE
            binding.tvHeaderTitle.visibility = View.GONE
            binding.etHeaderSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etHeaderSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun handleFabClick() {
        when (val fragment = currentFragment) {
            is ChatsFragment -> showNewChatMenu(binding.btnMainFab)
            is StoriesFragment -> fragment.showMediaPickerOptions()
            is PeopleFragment -> toggleSearchHeader()
            is CallsFragment -> navigateTo(PeopleFragment(), "Contacts", binding.navPeople)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.etHeaderSearch.visibility == View.VISIBLE) {
                    toggleSearchHeader()
                    return
                }
                if (currentFragment !is ChatsFragment) {
                    loadFragment(ChatsFragment(), "Chats")
                    updateNavUI(binding.navChats)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startRollingStatus() {
        statusJob?.cancel()
        statusJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                firestore.collection("users")
                    .orderBy("lastStatusUpdate", Query.Direction.DESCENDING)
                    .limit(20)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val statusUpdates = mutableListOf<StatusUpdate>()
                        val myUid = auth.currentUser?.uid
                        
                        val fourHoursAgo = System.currentTimeMillis() - (4 * 60 * 60 * 1000)
                        
                        snapshot.documents.forEach { doc ->
                            val name = doc.getString("name") ?: "Someone"
                            val status = doc.getString("status") ?: ""
                            val photo = doc.getString("profileImageUrl")
                            val lastUpdate = doc.getLong("lastStatusUpdate") ?: 0L
                            
                            if (status.isNotEmpty() && lastUpdate >= fourHoursAgo) {
                                if (doc.id == myUid) {
                                    statusUpdates.add(0, StatusUpdate(doc.id, name, status, photo, true))
                                } else {
                                    statusUpdates.add(StatusUpdate(doc.id, name, status, photo, false))
                                }
                            }
                        }

                        if (statusUpdates.isEmpty()) {
                            binding.tvRollingStatus.text = "Tap to set your mode..."
                            binding.ivStatusProfile.setImageResource(R.drawable.ic_launcher_foreground)
                        } else {
                            statusJob?.cancel() 
                            statusJob = CoroutineScope(Dispatchers.Main).launch {
                                while (isActive) {
                                    for (update in statusUpdates) {
                                        val displayText = if (update.isMe) "You are: ${update.status}" else "${update.name} is: ${update.status}"
                                        binding.tvRollingStatus.text = displayText
                                        loadStatusImage(update.photoUrl)
                                        binding.tvRollingStatus.isSelected = true 
                                        delay(3500)
                                    }
                                }
                            }
                        }
                    }
                delay(30000) 
            }
        }
    }

    private fun loadStatusImage(photo: String?) {
        if (!photo.isNullOrEmpty()) {
            if (photo.startsWith("data:image") || photo.length > 1000) {
                try {
                    val cleanBase64 = if (photo.contains(",")) photo.substringAfter(",") else photo
                    val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    binding.ivStatusProfile.setImageBitmap(decodedByte)
                } catch (e: Exception) {
                    binding.ivStatusProfile.setImageResource(R.drawable.ic_launcher_foreground)
                }
            } else {
                binding.ivStatusProfile.load(photo) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                }
            }
        } else {
            binding.ivStatusProfile.setImageResource(R.drawable.ic_launcher_foreground)
        }
    }

    private fun showSetStatusDialog() {
        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        val margin = (20 * resources.displayMetrics.density).toInt()
        rootLayout.setPadding(margin, margin / 2, margin, margin)

        val input = EditText(this)
        input.hint = "What's your mode?"
        rootLayout.addView(input)

        val emojiScroll = HorizontalScrollView(this)
        val emojiContainer = LinearLayout(this)
        emojiContainer.orientation = LinearLayout.HORIZONTAL
        
        val emojis = listOf("😊", "💻", "🏠", "🚗", "🍱", "💤", "🏃", "🎮", "⚽", "🎵", "📚", "🔥", "💯", "✨")
        emojis.forEach { emoji ->
            val emojiView = TextView(this)
            emojiView.text = emoji
            emojiView.textSize = 24f
            emojiView.setPadding(16, 16, 16, 16)
            emojiView.setOnClickListener { input.append(emoji) }
            emojiContainer.addView(emojiView)
        }
        
        emojiScroll.addView(emojiContainer)
        rootLayout.addView(emojiScroll)
        
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener { 
            input.setText(it.getString("status") ?: "")
            input.setSelection(input.text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Your Mode")
            .setView(rootLayout)
            .setPositiveButton("Update") { _, _ ->
                val newStatus = input.text.toString().trim()
                firestore.collection("users").document(uid).update("status", newStatus, "lastStatusUpdate", System.currentTimeMillis())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Mode updated!", Toast.LENGTH_SHORT).show()
                        startRollingStatus()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncSystemNavigationColor() {
        val typedValue = TypedValue()
        
        // Match StatusBar with Header (Primary Color)
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        val primaryColor = if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId) else typedValue.data
        
        // Match NavigationBar with Background
        theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
        val bgColor = if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId) else typedValue.data

        @Suppress("DEPRECATION")
        window.statusBarColor = primaryColor
        
        @Suppress("DEPRECATION")
        window.navigationBarColor = bgColor
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                controller.setSystemBarsAppearance(
                    if (isColorLight(bgColor)) android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
                controller.setSystemBarsAppearance(
                    if (isColorLight(primaryColor)) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val darkness = 1 - (0.299 * android.graphics.Color.red(color) + 0.587 * android.graphics.Color.green(color) + 0.114 * android.graphics.Color.blue(color)) / 255
        return darkness < 0.5
    }

    private fun checkAppLock() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            val code = doc.getString("appLockCode")
            if (!code.isNullOrEmpty()) {
                val intent = Intent(this, AppLockActivity::class.java)
                intent.putExtra("EXPECTED_CODE", code)
                startActivity(intent)
            }
        }
    }

    private fun showNewChatMenu(view: View) {
        val options = arrayOf("New Private Chat", "New Group")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Start Chat")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        loadFragment(PeopleFragment(), "Contacts")
                        updateNavUI(binding.navPeople)
                    }
                    1 -> startActivity(Intent(this, CreateGroupActivity::class.java))
                }
            }
            .show()
    }

    private fun loadHeaderProfile() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                val photo = doc.getString("profileImageUrl")
                if (!photo.isNullOrEmpty()) {
                    if (photo.startsWith("data:image")) {
                        try {
                            val cleanBase64 = photo.substringAfter(",")
                            val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                            binding.ivHeaderProfile.setImageBitmap(decodedByte)
                        } catch (e: Exception) {}
                    } else {
                        binding.ivHeaderProfile.load(photo)
                    }
                }
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.navChats.setOnClickListener { navigateTo(ChatsFragment(), "Chats", it as ImageButton) }
        binding.navStories.setOnClickListener { navigateTo(StoriesFragment(), "Stories", it as ImageButton) }
        binding.navCalls.setOnClickListener { navigateTo(CallsFragment(), "Calls", it as ImageButton) }
        binding.navPeople.setOnClickListener { navigateTo(PeopleFragment(), "Contacts", it as ImageButton) }
        binding.navEarn.setOnClickListener { navigateTo(EarnFragment(), "Earn", it as ImageButton) }
        binding.navSettings.setOnClickListener { navigateTo(SettingsFragment(), "Settings", it as ImageButton) }
    }

    private fun navigateTo(fragment: Fragment, title: String, button: ImageButton) {
        if (currentFragment != null && fragment::class == currentFragment!!::class) return
        loadFragment(fragment, title)
        updateNavUI(button)
    }

    private fun updateNavUI(activeButton: ImageButton) {
        val allButtons = listOf(binding.navChats, binding.navStories, binding.navCalls, binding.navPeople, binding.navEarn, binding.navSettings)
        val typedValue = TypedValue()
        
        // Use R.attr.colorOnPrimary to resolve the theme attribute correctly
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)
        val onPrimaryColor = if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId) else typedValue.data

        allButtons.forEach { btn ->
            btn.imageTintList = android.content.res.ColorStateList.valueOf(onPrimaryColor)
            if (btn == activeButton) {
                btn.alpha = 1.0f
                btn.animate().scaleX(1.2f).scaleY(1.2f).setDuration(250).start()
            } else {
                btn.alpha = 0.5f
                btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start()
            }
        }
    }

    private fun loadFragment(fragment: Fragment, title: String) {
        binding.tvHeaderTitle.text = title
        currentFragment = fragment
        
        binding.btnSearch.visibility = if (fragment is EarnFragment || fragment is SettingsFragment) View.GONE else View.VISIBLE

        when (fragment) {
            is ChatsFragment -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(R.drawable.ic_chat)
                binding.cvStatusBanner.visibility = View.GONE
            }
            is StoriesFragment -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_menu_camera)
                binding.cvStatusBanner.visibility = View.VISIBLE
            }
            is CallsFragment -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_menu_call)
                binding.cvStatusBanner.visibility = View.GONE
            }
            is PeopleFragment -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_input_add)
                binding.cvStatusBanner.visibility = View.GONE
            }
            else -> {
                binding.btnMainFab.visibility = View.GONE
                binding.cvStatusBanner.visibility = View.GONE
            }
        }

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun syncThemeFromFirestore() {
        com.example.chatsnap.utils.ThemeManager.syncThemeFromFirestore(this) {
            finish()
            startActivity(intent)
        }
    }

    private fun listenForIncomingCalls() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("calls").whereEqualTo("receiverId", uid).whereEqualTo("status", "pending").limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || snapshot.isEmpty) return@addSnapshotListener
                val doc = snapshot.documents[0]
                AlertDialog.Builder(this)
                    .setTitle("Incoming Call")
                    .setMessage("${doc.getString("callerName")} is calling...")
                    .setPositiveButton("Answer") { _, _ ->
                        doc.reference.update("status", "completed")
                        val intent = Intent(this, CallActivity::class.java).apply {
                            putExtra("callType", doc.getString("type"))
                            putExtra("receiverName", doc.getString("callerName"))
                            putExtra("channelName", doc.getString("channelName"))
                            putExtra("isCaller", false)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Decline") { _, _ -> doc.reference.update("status", "rejected") }
                    .show()
            }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 104)
            }
        }
    }

    private fun initializeFcmToken() {
        val uid = auth.currentUser?.uid ?: return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            android.util.Log.d("FCM_TEST", "Token: $token")
            firestore.collection("users").document(uid).update("fcmToken", token)
                .addOnSuccessListener {
                    android.util.Log.d("FCM_TEST", "Token updated in Firestore")
                }
                .addOnFailureListener {
                    android.util.Log.e("FCM_TEST", "Failed to update token: ${it.message}")
                }
        }
    }

    private fun markMessagesAsDelivered() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("messages").whereEqualTo("receiverId", uid).whereEqualTo("status", "SENT").get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) return@addOnSuccessListener
                val batch = firestore.batch()
                for (doc in snapshot.documents) batch.update(doc.reference, "status", "DELIVERED")
                batch.commit()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        statusJob?.cancel()
    }

    private fun loadAnnouncement() {
        firestore.collection("config").document("announcement")
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists() && doc.getBoolean("active") == true) {
                    val text = doc.getString("text") ?: ""
                    if (text.isNotEmpty()) {
                        binding.tvAnnouncementBanner.text = "📢 $text"
                        binding.tvAnnouncementBanner.visibility = View.VISIBLE
                    } else {
                        binding.tvAnnouncementBanner.visibility = View.GONE
                    }
                } else {
                    binding.tvAnnouncementBanner.visibility = View.GONE
                }
            }
    }

    private fun checkMaintenanceMode() {
        firestore.collection("config").document("admin")
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.getBoolean("maintenanceMode") == true) {
                    val uid = auth.currentUser?.uid ?: ""
                    
                    // 1. Quick check: Is this the UID that turned maintenance ON?
                    val masterAdminUid = doc.getString("adminUid")
                    if (uid == masterAdminUid) return@addSnapshotListener
                    
                    // 2. Profile check: Does this user have Admin rights?
                    firestore.collection("users").document(uid).get().addOnSuccessListener { userDoc ->
                        val isAdmin = userDoc.getBoolean("isAdmin") ?: false
                        if (isAdmin) {
                            // User is admin, do nothing (bypass)
                        } else {
                            // User is not admin, show maintenance screen
                            showMaintenanceDialog()
                        }
                    }.addOnFailureListener {
                        showMaintenanceDialog()
                    }
                }
            }
    }

    private fun showMaintenanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔧 Under Maintenance")
            .setMessage("ChatSnap is currently under maintenance. Please try again later.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }
}
