package com.example.chatsnap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import coil.load
import com.example.chatsnap.databinding.FragmentSettingsBinding
import com.example.chatsnap.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var currentUser: User? = null

    private var userListener: ListenerRegistration? = null
    private var friendsListener: ListenerRegistration? = null
    private var snapsListener: ListenerRegistration? = null
    private var walletListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        setupSettingItems()
        listenToUserData()
        loadStats()
        setupSettingsSearch()

        // Apply staggered animations
        val viewsToAnimate = listOf(
            binding.itemNotifications.root,
            binding.itemGhostMode.root,
            binding.itemMediaHub.root,
            binding.itemVault.root,
            binding.itemDownloader.root,
            binding.itemScanner.root,
            binding.itemNotes.root,
            binding.itemWebDownloader.root,
            binding.itemAppLock.root,
            binding.itemTheme.root,
            binding.itemWallpaper.root,
            binding.itemHelp.root,
            binding.itemPrivacyPolicy.root,
            binding.itemContacts.root,
            binding.itemMultiAccount.root,
            binding.btnLogout
        )
        com.example.chatsnap.utils.AnimUtils.animateStaggered(viewsToAnimate, 200)

        binding.fabEditAvatar.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileSetupActivity::class.java))
        }

        binding.btnQrCode.setOnClickListener {
            startActivity(Intent(requireContext(), QRProfileActivity::class.java))
        }

        binding.ivProfile.setOnClickListener { handleAdminTap() }

        binding.btnLogout.setOnClickListener {
            performLogout()
        }
    }

    private fun performLogout() {
        try {
            // 1. Detach all listeners first to avoid "Permission Denied" crashes
            removeListeners()

            // 2. Sign out from Firebase
            auth.signOut()
            
            // 3. Navigate to Login and clear backstack
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            
            // 4. Finish the current activity
            activity?.finish()
            
        } catch (e: Exception) {
            Toast.makeText(context, "Error logging out: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingItems() {
        // Local Media Hub
        binding.itemMediaHub.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_media_play)
            tvItemTitle.text = "Local Media Hub"
            tvItemValue.text = "Browse, search & play videos & audio"
            root.setOnClickListener {
                startActivity(Intent(requireContext(), com.example.chatsnap.media.ui.MediaHubActivity::class.java))
            }
        }

        // Notifications
        val sharedPrefs = requireContext().getSharedPreferences("chatsnap_prefs", android.content.Context.MODE_PRIVATE)
        val initialNotifEnabled = sharedPrefs.getBoolean("notifications_enabled", true)

        binding.itemNotifications.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
            tvItemTitle.text = "Notifications"
            switchItem.visibility = View.VISIBLE
            switchItem.setOnCheckedChangeListener(null)
            switchItem.isChecked = initialNotifEnabled
            tvItemValue.text = if (initialNotifEnabled) "Alerts & Sounds enabled" else "Notifications disabled"

            switchItem.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit().putBoolean("notifications_enabled", isChecked).apply()
                tvItemValue.text = if (isChecked) "Alerts & Sounds enabled" else "Notifications disabled"

                val uid = auth.currentUser?.uid
                if (uid != null) {
                    firestore.collection("users").document(uid).update("notificationsEnabled", isChecked)
                }

                if (isChecked) {
                    checkAndRequestNotificationPermission()
                    Toast.makeText(context, "Notifications Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Notifications Muted", Toast.LENGTH_SHORT).show()
                }
            }

            root.setOnClickListener { switchItem.toggle() }
            root.setOnLongClickListener {
                sendTestNotification()
                true
            }
        }

        // Ghost Mode
        binding.itemGhostMode.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_view)
            tvItemTitle.text = "Ghost Mode"
            tvItemValue.text = "Online status visible"
            switchItem.visibility = View.VISIBLE
            root.setOnClickListener { 
                switchItem.toggle()
                toggleGhostMode(switchItem.isChecked)
            }
        }

        // Media Vault
        binding.itemVault.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_gallery)
            tvItemTitle.text = "Media Memories (Vault)"
            tvItemValue.text = "Private locked media"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), VaultActivity::class.java))
            }
        }

        // Media Downloader
        binding.itemDownloader.apply {
            ivItemIcon.setImageResource(android.R.drawable.stat_sys_download)
            tvItemTitle.text = "Media Downloader"
            tvItemValue.text = "Download videos & audio via yt-dlp"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), DownloaderActivity::class.java))
            }
        }

        // Document Scanner
        binding.itemScanner.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_crop)
            tvItemTitle.text = "Document Scanner"
            tvItemValue.text = "Scan, warp & export to PDF offline"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), com.example.chatsnap.scanner.ui.DocumentScannerActivity::class.java))
            }
        }

        // Notes Module
        binding.itemNotes.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_edit)
            tvItemTitle.text = "My Notes"
            tvItemValue.text = "Manage offline notes, checklists & scans"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), com.example.chatsnap.notes.ui.NotesActivity::class.java))
            }
        }

        // Web Video Downloader
        binding.itemWebDownloader.apply {
            ivItemIcon.setImageResource(android.R.drawable.stat_sys_download)
            tvItemTitle.text = "Web Video Downloader"
            tvItemValue.text = "Download videos from TikTok, Instagram, X & WhatsApp"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), WebVideoDownloaderActivity::class.java))
            }
        }

        // App Lock
        binding.itemAppLock.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_lock_idle_lock)
            tvItemTitle.text = "App Lock"
            tvItemValue.text = "Secured with 4-digit code"
            root.setOnClickListener { showAppLockSetupDialog() }
        }

        // Theme
        binding.itemTheme.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_gallery)
            tvItemTitle.text = "App Theme"
            tvItemValue.text = "Customize look & feel"
            root.setOnClickListener { showThemeStoreDialog() }
        }

        // Wallpaper
        binding.itemWallpaper.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_gallery)
            tvItemTitle.text = "Chat Wallpaper"
            tvItemValue.text = "Personalize background"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), WallpaperSettingsActivity::class.java))
            }
        }

        // Help
        binding.itemHelp.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_help)
            tvItemTitle.text = "Help & Support"
            tvItemValue.text = "Complaint box & replies"
            root.setOnClickListener { 
                startActivity(Intent(requireContext(), SupportRequestActivity::class.java))
            }
        }

        // Privacy Policy
        binding.itemPrivacyPolicy.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_lock_lock)
            tvItemTitle.text = "Privacy Policy"
            tvItemValue.text = "How we protect you"
            root.setOnClickListener { 
                val intent = Intent(requireContext(), StaticContentActivity::class.java)
                intent.putExtra("title", "Privacy Policy")
                intent.putExtra("content", "Your privacy is our priority. All your chats are end-to-end encrypted. We do not sell your data.")
                startActivity(intent)
            }
        }

        // Contacts Manager
        binding.itemContacts.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_my_calendar)
            tvItemTitle.text = "Contacts Manager"
            tvItemValue.text = "Import & Export device contacts as VCF"
            root.setOnClickListener {
                startActivity(Intent(requireContext(), ContactsActivity::class.java))
            }
        }

        // Multi-Account Switcher
        binding.itemMultiAccount.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_menu_share)
            tvItemTitle.text = "Switch Account"
            tvItemValue.text = "Login to another account"
            root.setOnClickListener {
                startActivity(Intent(requireContext(), MultiAccountActivity::class.java))
            }
        }


        // Admin Panel removed from visible UI - access only via secret tap
    }

    private fun handleAdminTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 600) {
            tapCount++
            if (tapCount >= 5) {
                try {
                    startActivity(Intent(requireContext(), AdminActivity::class.java))
                } catch (_: Exception) { }
                tapCount = 0
            }
        } else {
            tapCount = 1
        }
        lastTapTime = now
    }

    private fun toggleGhostMode(enabled: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).update("ghostMode", enabled)
        binding.itemGhostMode.tvItemValue.text = if (enabled) "Stealth browsing active" else "Online status visible"
    }

    private fun loadStats() {
        val uid = auth.currentUser?.uid ?: return
        
        friendsListener = firestore.collection("users").document(uid).collection("friends")
            .addSnapshotListener { snapshot, _ ->
                // Friends list change listener
            }

        snapsListener = firestore.collection("messages").whereEqualTo("senderId", uid).whereEqualTo("type", "IMAGE")
            .addSnapshotListener { snapshot, _ ->
                // Snaps change listener
            }
    }

    private fun listenToUserData() {
        val uid = auth.currentUser?.uid ?: return
        userListener = firestore.collection("users").document(uid)
            .addSnapshotListener { doc, e ->
                if (e != null || doc == null || !doc.exists()) return@addSnapshotListener
                
                currentUser = doc.toObject(User::class.java)

                if (_binding != null) {
                    binding.tvUserName.text = doc.getString("name") ?: "User"
                    binding.tvUserPhone.text = doc.getString("phone") ?: "No Phone"
                    binding.tvUserAbout.text = doc.getString("status") ?: "Status: Available"
                    
                    val imageUrl = doc.getString("profileImageUrl")
                    if (!imageUrl.isNullOrEmpty()) {
                        if (imageUrl.startsWith("data:image") || imageUrl.length > 1000) {
                            try {
                                val cleanBase64 = imageUrl.substringAfter(",")
                                val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                                val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                                binding.ivProfile.setImageBitmap(decodedByte)
                            } catch (e: Exception) {}
                        } else {
                            binding.ivProfile.load(imageUrl) {
                                crossfade(true)
                                placeholder(android.R.drawable.progress_indeterminate_horizontal)
                            }
                        }
                    }

                    binding.itemGhostMode.switchItem.isChecked = doc.getBoolean("ghostMode") ?: false
                    binding.itemGhostMode.tvItemValue.text = if (binding.itemGhostMode.switchItem.isChecked) "Stealth browsing active" else "Online status visible"

                    // Admin UI check - dynamically show if user is admin, else keep hidden
                    val isAdmin = doc.getBoolean("isAdmin") ?: false
                    if (isAdmin) {
                        binding.tvAdminHeader.visibility = View.VISIBLE
                        binding.cardAdmin.visibility = View.VISIBLE
                        
                        binding.itemAdmin.apply {
                            ivItemIcon.setImageResource(android.R.drawable.ic_menu_manage)
                            tvItemTitle.text = "Admin Panel"
                            tvItemValue.text = "Configure system & rewards"
                            root.setOnClickListener {
                                startActivity(Intent(requireContext(), AdminActivity::class.java))
                            }
                        }
                    } else {
                        binding.tvAdminHeader.visibility = View.GONE
                        binding.cardAdmin.visibility = View.GONE
                    }
                }
            }
    }

    private fun showThemeStoreDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_theme_store, null)
        val rvThemes = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvThemes)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseDialog)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        btnClose?.setOnClickListener { dialog.dismiss() }

        val currentTheme = com.example.chatsnap.utils.ThemeManager.getTheme(requireContext())
        val themes = com.example.chatsnap.utils.ThemeManager.AppTheme.values().toList()

        rvThemes.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvThemes.adapter = com.example.chatsnap.adapters.ThemeAdapter(themes, currentTheme) { selectedTheme ->
            com.example.chatsnap.utils.ThemeManager.setTheme(requireContext(), selectedTheme)
            com.example.chatsnap.utils.ThemeManager.syncThemeToFirestore(requireContext(), selectedTheme)
            dialog.dismiss()
            activity?.recreate()
        }

        dialog.show()
    }

    private fun showAppLockSetupDialog() {
        val uid = auth.currentUser?.uid ?: return
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Enter 4 digits"
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        
        val currentCode = currentUser?.appLockCode
        val title = if (currentCode.isNullOrEmpty()) "Set App Lock" else "Change/Disable App Lock"
        val message = if (currentCode.isNullOrEmpty()) "Enter a 4-digit code to protect your app." 
                     else "Current code is active. Enter new code or leave blank to disable."

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newCode = input.text.toString()
                if (newCode.isEmpty() || newCode.length == 4) {
                    firestore.collection("users").document(uid).update("appLockCode", if (newCode.isEmpty()) null else newCode)
                        .addOnSuccessListener {
                            Toast.makeText(context, "App Lock Updated", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(context, "Code must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAdminPasswordAndOpen() {
        // Obsolete: AdminActivity now handles its own password lock from Firestore
        startActivity(Intent(requireContext(), AdminActivity::class.java))
    }

    private fun removeListeners() {
        userListener?.remove()
        friendsListener?.remove()
        snapsListener?.remove()
        userListener = null
        friendsListener = null
        snapsListener = null
    }

    override fun onStop() {
        super.onStop()
        removeListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removeListeners()
        _binding = null
    }

    private fun setupSettingsSearch() {
        binding.etSettingsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSettings(s?.toString()?.trim()?.lowercase() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun filterSettings(query: String) {
        if (_binding == null) return
        val itemsMap = mapOf(
            "theme dark light color" to binding.itemTheme.root,
            "wallpaper background" to binding.itemWallpaper.root,
            "vault lock secret" to binding.itemVault.root,
            "downloader media video" to binding.itemDownloader.root,
            "scanner qr code" to binding.itemScanner.root,
            "notes memo checklist" to binding.itemNotes.root,
            "web downloader site" to binding.itemWebDownloader.root,
            "contacts sync phone" to binding.itemContacts.root,
            "multi account switch" to binding.itemMultiAccount.root,
            "notifications alert sound" to binding.itemNotifications.root,
            "ghost mode stealth online" to binding.itemGhostMode.root,
            "app lock passcode pin" to binding.itemAppLock.root,
            "privacy policy terms" to binding.itemPrivacyPolicy.root,
            "help support faq" to binding.itemHelp.root
        )

        itemsMap.forEach { (keywords, view) ->
            if (query.isEmpty() || keywords.contains(query)) {
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!granted) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    104
                )
            }
        }
    }

    private fun sendTestNotification() {
        val sharedPrefs = requireContext().getSharedPreferences("chatsnap_prefs", android.content.Context.MODE_PRIVATE)
        val isNotifEnabled = sharedPrefs.getBoolean("notifications_enabled", true)

        if (!isNotifEnabled) {
            Toast.makeText(context, "Notifications are turned off in settings", Toast.LENGTH_SHORT).show()
            return
        }

        checkAndRequestNotificationPermission()

        val channelId = "chat_notifications"
        val notificationManager = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Chat Notifications",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = androidx.core.app.NotificationCompat.Builder(requireContext(), channelId)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("ChatSnap Test")
            .setContentText("If you see this, notifications are working!")
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)

        notificationManager.notify(101, notificationBuilder.build())
        Toast.makeText(context, "Test notification triggered!", Toast.LENGTH_SHORT).show()
    }
}
