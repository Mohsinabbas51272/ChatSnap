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

        // Apply staggered animations
        val viewsToAnimate = listOf(
            binding.itemNotifications.root,
            binding.itemGhostMode.root,
            binding.itemVault.root,
            binding.itemAppLock.root,
            binding.itemTheme.root,
            binding.itemWallpaper.root,
            binding.itemHelp.root,
            binding.itemPrivacyPolicy.root,
            binding.btnLogout
        )
        com.example.chatsnap.utils.AnimUtils.animateStaggered(viewsToAnimate, 200)

        binding.fabEditAvatar.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileSetupActivity::class.java))
        }

        binding.btnPrivacyScore.setOnClickListener { handleAdminTap() }
        // Also attach to children just in case they consume the click
        binding.btnPrivacyScore.getChildAt(0).setOnClickListener { handleAdminTap() }

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
        // Notifications
        binding.itemNotifications.apply {
            ivItemIcon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
            tvItemTitle.text = "Notifications"
            tvItemValue.text = "Alerts & Sounds enabled"
            switchItem.visibility = View.VISIBLE
            switchItem.isChecked = true
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
                if (_binding != null) {
                    binding.tvStatFriends.text = snapshot?.size()?.toString() ?: "0"
                }
            }

        snapsListener = firestore.collection("messages").whereEqualTo("senderId", uid).whereEqualTo("type", "IMAGE")
            .addSnapshotListener { snapshot, _ ->
                if (_binding != null) {
                    binding.tvStatSnaps.text = snapshot?.size()?.toString() ?: "0"
                }
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

                    // Admin UI hidden - access only via secret tap on Privacy Score
                }
            }
    }

    private fun showThemeStoreDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_theme_store, null)
        val rvThemes = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvThemes)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setTitle("Theme Store (All Free)")
            .create()

        val themes = com.example.chatsnap.utils.ThemeManager.AppTheme.values().toList()
        rvThemes.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvThemes.adapter = com.example.chatsnap.adapters.ThemeAdapter(themes) { selectedTheme ->
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

    private fun sendTestNotification() {
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
