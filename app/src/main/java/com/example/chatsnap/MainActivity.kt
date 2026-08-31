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
import androidx.lifecycle.lifecycleScope
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

    private var currentSnapFilePath: String? = null
    private var snapPhotoUri: android.net.Uri? = null

    private val takeSnapLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = snapPhotoUri
            if (uri != null) {
                processAndSendSnap(uri)
            } else {
                Toast.makeText(this, "Snap capture failed, try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickSnapLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { processAndSendSnap(it) }
    }

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

        setupViewPager()
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
        listenForActiveCall()

        binding.btnSearch.setOnClickListener {
            toggleSearchHeader()
        }

        binding.btnClearCallsHeader.setOnClickListener {
            (getCurrentFragment() as? CallsFragment)?.showClearCallsDialog()
        }

        binding.btnMainFab.setOnClickListener {
            handleFabClick()
        }

        binding.btnSnapPlusFab.setOnClickListener {
            showSnapCreationDialog()
        }

        binding.btnAiAssistantFab.setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }

        binding.ivHeaderProfile.setOnClickListener {
            binding.viewPagerMain.setCurrentItem(6, true)
        }

        binding.btnEditStatus.setOnClickListener {
            showSetStatusDialog()
        }

        setupBackNavigation()
    }

    inner class MainPagerAdapter(activity: androidx.fragment.app.FragmentActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 7
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ChatsFragment()
                1 -> StoriesFragment()
                2 -> CallsFragment()
                3 -> PeopleFragment()
                4 -> EarnFragment()
                5 -> AuraFeedFragment()
                6 -> SettingsFragment()
                else -> ChatsFragment()
            }
        }
    }

    fun isAuraFeedActive(): Boolean {
        return ::binding.isInitialized && binding.viewPagerMain.currentItem == 5
    }

    private fun getCurrentFragment(): Fragment? {
        return supportFragmentManager.findFragmentByTag("f${binding.viewPagerMain.currentItem}")
    }

    private fun setupViewPager() {
        val pagerAdapter = MainPagerAdapter(this)
        binding.viewPagerMain.adapter = pagerAdapter
        binding.viewPagerMain.offscreenPageLimit = 3

        binding.viewPagerMain.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                onPageChanged(position)
            }
        })
    }

    private fun setupHeaderSearch() {
        binding.etHeaderSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                (getCurrentFragment() as? SearchableFragment)?.onSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun toggleSearchHeader() {
        if (binding.etHeaderSearch.visibility == View.VISIBLE) {
            binding.etHeaderSearch.visibility = View.GONE
            binding.tvHeaderTitle.visibility = View.VISIBLE
            binding.etHeaderSearch.setText("")
            binding.btnSearch.setImageResource(android.R.drawable.ic_menu_search)
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etHeaderSearch.windowToken, 0)
        } else {
            binding.etHeaderSearch.visibility = View.VISIBLE
            binding.tvHeaderTitle.visibility = View.GONE
            binding.btnSearch.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            binding.etHeaderSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etHeaderSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun handleFabClick() {
        val fragment = getCurrentFragment()
        when (binding.viewPagerMain.currentItem) {
            0 -> showNewChatMenu(binding.btnMainFab)
            1 -> (fragment as? StoriesFragment)?.showMediaPickerOptions()
            3 -> toggleSearchHeader()
            2 -> binding.viewPagerMain.setCurrentItem(3, true)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.etHeaderSearch.visibility == View.VISIBLE) {
                    toggleSearchHeader()
                    return
                }
                if (binding.viewPagerMain.currentItem != 0) {
                    binding.viewPagerMain.setCurrentItem(0, true)
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
                                        binding.tvRollingStatus.animate()
                                            .alpha(0f)
                                            .translationY(-6f)
                                            .setDuration(120)
                                            .withEndAction {
                                                binding.tvRollingStatus.text = displayText
                                                loadStatusImage(update.photoUrl)
                                                binding.tvRollingStatus.translationY = 6f
                                                binding.tvRollingStatus.animate()
                                                    .alpha(1f)
                                                    .translationY(0f)
                                                    .setDuration(150)
                                                    .start()
                                                binding.tvRollingStatus.isSelected = true 
                                            }
                                            .start()
                                        delay(3000)
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_mode, null)
        val etModeInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etModeInput)
        val llEmojiContainer = dialogView.findViewById<LinearLayout>(R.id.llEmojiContainer)
        val llPresetsContainer = dialogView.findViewById<LinearLayout>(R.id.llPresetsContainer)
        val btnModeClear = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeClear)
        val btnModeCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeCancel)
        val btnModeUpdate = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeUpdate)

        val emojis = listOf("✨", "🔥", "💻", "🎮", "💤", "🏃", "☕", "🚗", "🎵", "📚", "🍔", "🏖️", "🚀", "😴", "🎧", "🍕", "⚡", "❤️")
        val density = resources.displayMetrics.density

        // Resolve theme colors dynamically
        val surfaceVariantVal = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, surfaceVariantVal, true)
        val surfaceVariantColor = if (surfaceVariantVal.resourceId != 0) ContextCompat.getColor(this, surfaceVariantVal.resourceId) else surfaceVariantVal.data

        val outlineVariantVal = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, outlineVariantVal, true)
        val outlineVariantColor = if (outlineVariantVal.resourceId != 0) ContextCompat.getColor(this, outlineVariantVal.resourceId) else outlineVariantVal.data

        val textColorVal = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, textColorVal, true)
        val textColorPrimary = if (textColorVal.resourceId != 0) ContextCompat.getColor(this, textColorVal.resourceId) else textColorVal.data

        val accentVal = TypedValue()
        theme.resolveAttribute(R.attr.themeAccentColor, accentVal, true)
        val accentColor = if (accentVal.resourceId != 0) ContextCompat.getColor(this, accentVal.resourceId) else accentVal.data

        btnModeUpdate.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        btnModeUpdate.setTextColor(android.graphics.Color.WHITE)

        emojis.forEach { emoji ->
            val emojiCard = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
                radius = 22 * density
                cardElevation = 0f
                strokeWidth = (1 * density).toInt()
                setStrokeColor(outlineVariantColor)
                setCardBackgroundColor(surfaceVariantColor)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cur = etModeInput.text?.toString() ?: ""
                    val separator = if (cur.isNotEmpty() && !cur.endsWith(" ")) " " else ""
                    etModeInput.append("$separator$emoji")
                    etModeInput.setSelection(etModeInput.text?.length ?: 0)
                }
            }
            val tv = TextView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
                text = emoji
                textSize = 19f
            }
            emojiCard.addView(tv)
            llEmojiContainer.addView(emojiCard)
        }

        val presets = listOf("Available ✨", "At Work 💻", "Gaming 🎮", "Sleeping 💤", "Busy 🔥", "At Gym 🏃", "Listening to Music 🎧", "On the Way 🚗", "Reading 📚", "Chilling 🏖️")
        presets.forEach { preset ->
            val presetButton = com.google.android.material.button.MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
                text = preset
                textSize = 12.5f
                cornerRadius = (20 * density).toInt()
                strokeWidth = (1 * density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(outlineVariantColor)
                backgroundTintList = android.content.res.ColorStateList.valueOf(surfaceVariantColor)
                setTextColor(textColorPrimary)
                elevation = 0f
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener {
                    etModeInput.setText(preset)
                    etModeInput.setSelection(preset.length)
                }
            }
            llPresetsContainer.addView(presetButton)
        }

        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
            val currentStatus = doc.getString("status") ?: ""
            etModeInput.setText(currentStatus)
            etModeInput.setSelection(currentStatus.length)
        }

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnModeClear.setOnClickListener {
            etModeInput.setText("")
        }

        btnModeCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnModeUpdate.setOnClickListener {
            val newStatus = etModeInput.text?.toString()?.trim() ?: ""
            btnModeUpdate.isEnabled = false
            firestore.collection("users").document(uid)
                .update(
                    "status", newStatus,
                    "lastStatusUpdate", System.currentTimeMillis()
                )
                .addOnSuccessListener {
                    Toast.makeText(this, if (newStatus.isEmpty()) "Mode cleared!" else "Mode updated: $newStatus", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    startRollingStatus()
                }
                .addOnFailureListener { e ->
                    btnModeUpdate.isEnabled = true
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        dialog.show()
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
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
        if (AppLockActivity.isUnlocked) {
            return
        }
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
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_start_chat_picker, null)
        dialog.setContentView(sheetView)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)

        sheetView.findViewById<View>(R.id.btnNewPrivateChat)?.setOnClickListener {
            binding.viewPagerMain.setCurrentItem(3, true)
            dialog.dismiss()
        }

        sheetView.findViewById<View>(R.id.btnNewGroup)?.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
            dialog.dismiss()
        }

        dialog.show()
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
        binding.navChats.setOnClickListener { binding.viewPagerMain.setCurrentItem(0, true) }
        binding.navStories.setOnClickListener { binding.viewPagerMain.setCurrentItem(1, true) }
        binding.navCalls.setOnClickListener { binding.viewPagerMain.setCurrentItem(2, true) }
        binding.navPeople.setOnClickListener { binding.viewPagerMain.setCurrentItem(3, true) }
        binding.navEarn.setOnClickListener { binding.viewPagerMain.setCurrentItem(4, true) }
        binding.navAuraFeed.setOnClickListener { binding.viewPagerMain.setCurrentItem(5, true) }
        binding.navSettings.setOnClickListener { binding.viewPagerMain.setCurrentItem(6, true) }
    }

    private fun updateNavUI(activeButton: ImageButton) {
        val allButtons = listOf(binding.navChats, binding.navStories, binding.navCalls, binding.navPeople, binding.navEarn, binding.navSettings, binding.navAuraFeed)
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

    private fun onPageChanged(position: Int) {
        val title = when (position) {
            0 -> "Chats"
            1 -> "Stories"
            2 -> "Calls"
            3 -> "Contacts"
            4 -> "Earn"
            5 -> "AuraFeed"
            6 -> "Settings"
            else -> "Chats"
        }

        val activeButton = when (position) {
            0 -> binding.navChats
            1 -> binding.navStories
            2 -> binding.navCalls
            3 -> binding.navPeople
            4 -> binding.navEarn
            5 -> binding.navAuraFeed
            6 -> binding.navSettings
            else -> binding.navChats
        }

        binding.tvHeaderTitle.text = title
        updateNavUI(activeButton)

        val isAura = position == 5
        val isEarn = position == 4
        val isSettings = position == 6
        val isCalls = position == 2

        // Pause/resume AuraFeed video playback depending on active tab
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is AuraFeedFragment) {
                if (isAura) {
                    fragment.resumePlayback()
                } else {
                    fragment.pausePlayback()
                }
            }
        }

        // Disable swipe gestures on ViewPager2 when on Aura Feed to prevent horizontal swipe conflicts
        binding.viewPagerMain.isUserInputEnabled = !isAura

        // Header: hide completely for AuraFeed
        binding.appBarLayout.visibility = if (isAura) View.GONE else View.VISIBLE

        binding.btnSearch.visibility = if (isEarn || isSettings || isAura) View.GONE else View.VISIBLE
        binding.btnClearCallsHeader.visibility = if (isCalls) View.VISIBLE else View.GONE

        // Manage CoordinatorLayout behavior and system windows fitting for immersive mode
        val params = binding.mainContentLayout.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        if (isAura) {
            params?.behavior = null
            binding.main.fitsSystemWindows = false
        } else {
            params?.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
            binding.main.fitsSystemWindows = true
        }
        binding.mainContentLayout.layoutParams = params
        binding.main.requestApplyInsets()
        binding.mainContentLayout.requestLayout()

        // Footer bottom nav: slide out/in with animation
        if (isAura) {
            binding.cvFloatingMenu.animate()
                .translationY(binding.cvFloatingMenu.height.toFloat() + 48f)
                .alpha(0f)
                .setDuration(280)
                .withEndAction { binding.cvFloatingMenu.visibility = View.GONE }
                .start()
        } else {
            binding.cvFloatingMenu.visibility = View.VISIBLE
            binding.cvFloatingMenu.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280)
                .start()
        }

        when (position) {
            0 -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(R.drawable.ic_chat)
                binding.btnSnapPlusFab.visibility = View.VISIBLE
                binding.btnAiAssistantFab.visibility = View.VISIBLE
                binding.cvStatusBanner.visibility = View.GONE
            }
            1 -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_menu_camera)
                binding.btnSnapPlusFab.visibility = View.GONE
                binding.btnAiAssistantFab.visibility = View.GONE
                binding.cvStatusBanner.visibility = View.VISIBLE
            }
            2 -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_menu_call)
                binding.btnSnapPlusFab.visibility = View.GONE
                binding.btnAiAssistantFab.visibility = View.GONE
                binding.cvStatusBanner.visibility = View.GONE
            }
            3 -> {
                binding.btnMainFab.visibility = View.VISIBLE
                binding.btnMainFab.setImageResource(android.R.drawable.ic_input_add)
                binding.btnSnapPlusFab.visibility = View.GONE
                binding.btnAiAssistantFab.visibility = View.GONE
                binding.cvStatusBanner.visibility = View.GONE
            }
            else -> {
                binding.btnMainFab.visibility = View.GONE
                binding.btnSnapPlusFab.visibility = View.GONE
                binding.btnAiAssistantFab.visibility = View.GONE
                binding.cvStatusBanner.visibility = View.GONE
            }
        }
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
                val callerId = doc.getString("callerId") ?: ""
                val callerName = doc.getString("callerName") ?: "Unknown"
                val callType = doc.getString("type") ?: "Voice"
                val channelName = doc.getString("channelName") ?: ""

                // Fetch caller's profile photo, then launch IncomingCallActivity
                firestore.collection("users").document(callerId).get()
                    .addOnSuccessListener { userDoc ->
                        val callerPhoto = userDoc.getString("profileImageUrl")
                        launchIncomingCallScreen(doc.id, callerName, callType, channelName, callerPhoto)
                    }
                    .addOnFailureListener {
                        launchIncomingCallScreen(doc.id, callerName, callType, channelName, null)
                    }
            }
    }

    private fun launchIncomingCallScreen(callId: String, callerName: String, callType: String, channelName: String, callerPhoto: String?) {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("channelName", channelName)
            putExtra("callerPhoto", callerPhoto)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun listenForActiveCall() {
        val uid = auth.currentUser?.uid ?: return
        val pill = binding.cardActiveCallPill
        val dot = binding.viewCallDot

        // Pulsing alpha animation on the green dot
        val pulseAnim = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
            duration = 800
            repeatMode = android.animation.ObjectAnimator.REVERSE
            repeatCount = android.animation.ObjectAnimator.INFINITE
        }

        firestore.collection("calls")
            .whereIn("status", listOf("pending", "active"))
            .whereEqualTo("callerId", uid)
            .limit(1)
            .addSnapshotListener { snapshot, _ ->
                val hasActive = snapshot != null && !snapshot.isEmpty
                if (hasActive) {
                    pill.visibility = android.view.View.VISIBLE
                    if (!pulseAnim.isRunning) pulseAnim.start()
                    val doc = snapshot!!.documents[0]
                    pill.setOnClickListener {
                        val intent = Intent(this, CallActivity::class.java).apply {
                            putExtra("receiverId", doc.getString("receiverId"))
                            putExtra("receiverName", doc.getString("receiverName"))
                            putExtra("callerName", doc.getString("callerName"))
                            putExtra("callType", doc.getString("type") ?: "Voice")
                            putExtra("channelName", doc.getString("channelName"))
                            putExtra("isCaller", true)
                        }
                        startActivity(intent)
                    }
                } else {
                    pill.visibility = android.view.View.GONE
                    pulseAnim.cancel()
                }
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
        val sharedPrefs = getSharedPreferences("chatsnap_prefs", android.content.Context.MODE_PRIVATE)
        if (!sharedPrefs.contains("notifications_enabled")) {
            sharedPrefs.edit().putBoolean("notifications_enabled", true).apply()
        }
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            android.util.Log.d("FCM_TEST", "Token: $token")
            val data = mutableMapOf<String, Any>("fcmToken" to token)
            firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
                if (!doc.contains("notificationsEnabled")) {
                    data["notificationsEnabled"] = true
                }
                firestore.collection("users").document(uid).set(data, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        android.util.Log.d("FCM_TEST", "Token & notification status updated in Firestore")
                    }
                    .addOnFailureListener {
                        android.util.Log.e("FCM_TEST", "Failed to update token: ${it.message}")
                    }
            }.addOnFailureListener {
                firestore.collection("users").document(uid).set(data, com.google.firebase.firestore.SetOptions.merge())
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

    private fun showSnapCreationDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_send_snap_picker, null)
        dialog.setContentView(sheetView)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)

        sheetView.findViewById<View>(R.id.btnCameraSnap)?.setOnClickListener {
            openCameraForSnap()
            dialog.dismiss()
        }
        sheetView.findViewById<View>(R.id.btnGallerySnap)?.setOnClickListener {
            pickSnapLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openCameraForSnap() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 108)
            return
        }
        try {
            val file = java.io.File.createTempFile("SNAP_${System.currentTimeMillis()}", ".jpg", getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES))
            currentSnapFilePath = file.absolutePath
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            snapPhotoUri = uri
            takeSnapLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 108 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openCameraForSnap()
        }
    }

    private fun processAndSendSnap(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Photo capture failed, please try again", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                // Compress image before encoding to reduce size
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val compressed = java.io.ByteArrayOutputStream()
                bitmap?.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, compressed)
                val finalBytes = if (compressed.size() > 0) compressed.toByteArray() else bytes

                val base64 = android.util.Base64.encodeToString(finalBytes, android.util.Base64.DEFAULT)
                val mimeType = "image/jpeg"
                val finalData = "data:$mimeType;base64,$base64"

                // Write to temp file to avoid TransactionTooLargeException (Binder 1MB limit)
                val tempFile = java.io.File(cacheDir, "snap_temp_${System.currentTimeMillis()}.txt")
                tempFile.writeText(finalData)

                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, ForwardActivity::class.java).apply {
                        putExtra("msg_content", "📸 Snap")
                        putExtra("msg_type", "SNAP")
                        putExtra("msg_media_file", tempFile.absolutePath)
                        putExtra("msg_is_snap", true)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Snap error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

