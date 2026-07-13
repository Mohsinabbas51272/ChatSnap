package com.example.chatsnap

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.load
import com.example.chatsnap.adapters.ChatAdapter
import com.example.chatsnap.databinding.ActivityChatBinding
import com.example.chatsnap.databinding.BottomSheetAttachBinding
import com.example.chatsnap.databinding.DialogCreatePollBinding
import com.example.chatsnap.models.Message
import com.example.chatsnap.services.ScheduledMessageWorker
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import id.zelory.compressor.Compressor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<Message>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var receiverId: String? = null
    private var groupId: String? = null
    private var chatId: String = ""
    private var receiverName: String? = null
    private var isGroup: Boolean = false
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioPath: String? = null
    private var latestPhotoUri: Uri? = null

    // Recording UI & state
    private var isRecordingActive: Boolean = false
    private var recordingHandler: Handler? = null
    private var amplitudeRunnable: Runnable? = null
    private var micDownX: Float = 0f
    private var isCancelSlide: Boolean = false

    private var pendingMediaUri: Uri? = null
    private var pendingMediaType: String? = null

    private var scheduledAttachedUri: Uri? = null
    private var scheduledAttachedType: String? = null
    private var scheduledAttachedMime: String? = null
    private var scheduledAttachedName: String? = null
    private var scheduledDialogText: String = ""
    
    private var groupAdminId: String? = null
    private var groupMemberIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Capture keyboard height for responsive emoji picker
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                if (imeHeight > 0) {
                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putInt("keyboard_height", imeHeight).apply()
                }
            }
            insets
        }

        if (!setupData()) return
        setupUI()
        setupListeners()
        startScheduledMessageWorker()
    }

    private fun startScheduledMessageWorker() {
        val workRequest = PeriodicWorkRequestBuilder<ScheduledMessageWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ScheduledMessages",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun setupData(): Boolean {
        val intent = intent
        receiverId = intent.getStringExtra("receiverId")
        groupId = intent.getStringExtra("groupId")
        isGroup = groupId != null
        receiverName = intent.getStringExtra("receiverName") ?: intent.getStringExtra("groupName") ?: "Chat"
        
        val currentUid = auth.uid ?: run { 
            Log.e("ChatActivity", "Auth UID is null")
            finish()
            return false 
        }

        if (!isGroup && receiverId == null) {
            Log.e("ChatActivity", "ReceiverId is null for 1v1 chat")
            Toast.makeText(this, "Error: Chat target missing", Toast.LENGTH_SHORT).show()
            finish()
            return false
        }

        chatId = if (isGroup) groupId!! else generateChatId(currentUid, receiverId!!)
        
        if (isGroup) {
            db.collection("groups").document(groupId!!).get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    groupAdminId = doc.getString("adminId")
                    @Suppress("UNCHECKED_CAST")
                    groupMemberIds = doc.get("memberIds") as? List<String> ?: emptyList()
                }
            }
        }
        return true
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.tvReceiverName.text = receiverName
        
        setupRecyclerView()
        listenForMessages()
        fetchPartnerInfo()

        binding.tvReceiverName.setOnClickListener {
            val intent = Intent(this, MediaGalleryActivity::class.java)
            intent.putExtra("chatId", chatId)
            intent.putExtra("partnerName", receiverName)
            intent.putExtra("isGroup", isGroup)
            startActivity(intent)
        }
        markMessagesAsRead()
        com.example.chatsnap.utils.WallpaperManager.applyWallpaper(binding.ivChatWallpaper)

        // Handle shared content from ShareReceiverActivity
        handleIncomingSharedContent()
    }

    private fun handleIncomingSharedContent() {
        val sharedText = intent.getStringExtra("shared_text")
        val sharedUriStr = intent.getStringExtra("shared_uri")
        val sharedMime = intent.getStringExtra("shared_mime")

        if (sharedText != null) {
            binding.etMessage.setText(sharedText)
            binding.etMessage.setSelection(sharedText.length)
        }

        if (sharedUriStr != null) {
            val uri = Uri.parse(sharedUriStr)
            val mime = sharedMime ?: contentResolver.getType(uri) ?: ""
            val type = when {
                mime.startsWith("image/") -> "IMAGE"
                mime.startsWith("video/") -> "VIDEO"
                mime.startsWith("audio/") -> "AUDIO"
                else -> "DOCUMENT"
            }
            if (type == "IMAGE" || type == "VIDEO") {
                showMediaPreview(uri, type)
            } else {
                uploadFile(uri, type)
            }
        }
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            handleSendAction()
        }

        binding.btnEmoji.setOnClickListener {
            showEmojiPickerDialog()
        }

        binding.btnAttach.setOnClickListener { showAttachBottomSheet() }
        setupVoiceRecording()
        setupTextChangeListener()
        
        binding.btnBack.setOnClickListener { finish() }
        binding.btnOptions.setOnClickListener { showChatOptions(it) }

        binding.btnVoiceCall.setOnClickListener { startCall("Voice") }
        binding.btnVideoCall.setOnClickListener { startCall("Video") }
        
        binding.btnCancelMedia.setOnClickListener {
            clearPendingMedia()
        }

        binding.btnAiAssistant.setOnClickListener {
            val draft = binding.etMessage.text.toString().trim()
            val intent = Intent(this, AiChatActivity::class.java).apply {
                putExtra("draft_text", draft)
            }
            aiChatLauncher.launch(intent)
        }
    }

    private fun showEmojiPickerDialog() {
        // Hide soft keyboard first
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_emoji_picker, null)
        dialog.setContentView(sheetView)

        // Make it expanded immediately
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        val storedHeight = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("keyboard_height", 0)
        if (storedHeight > 0) {
            sheetView.post {
                val params = sheetView.layoutParams
                params.height = storedHeight
                sheetView.layoutParams = params
            }
        }

        val gridView = sheetView.findViewById<android.widget.GridView>(R.id.emojiGridView)
        val emojis = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "👍", "👎", "👌", "✊", "👊", "🤛", "🤜", "🤞", "✌️", "🤟", "🤘", "👈", "👉", "👆", "👇", "☝️", "👋", "🤚", "🖐️", "🖖", "✍️", "👏", "🙌", "👐", "🤲", "🙏", "💪", "🔥", "✨", "🎉", "💯", "⭐", "🌟", "💫", "💥", "🎈", "🎁", "🎂", "👑", "💎", "🌈", "☀️", "☁️", "🌧️", "❄️", "⚡"
        )

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = emojis.size
            override fun getItem(position: Int): Any = emojis[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val tv = (convertView as? android.widget.TextView) ?: android.widget.TextView(this@ChatActivity).apply {
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                    setPadding(8, 8, 8, 8)
                    val outValue = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }
                tv.text = emojis[position]
                return tv
            }
        }
        
        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val emoji = emojis[position]
            binding.etMessage.append(emoji)
            sheetView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }

        dialog.show()
    }

    private fun handleSendAction() {
        val content = binding.etMessage.text.toString().trim()
        val mediaUri = pendingMediaUri
        val mediaType = pendingMediaType

        if (mediaUri != null && mediaType != null) {
            uploadFile(mediaUri, mediaType)
            clearPendingMedia()
        } else if (content.isNotEmpty()) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendMessage(content, "TEXT")
            binding.etMessage.setText("")
        }
    }

    private fun clearPendingMedia() {
        pendingMediaUri = null
        pendingMediaType = null
        binding.mediaPreviewLayout.visibility = View.GONE
        updateSendButtonUI()
    }

    private fun startCall(type: String) {
        if (isGroup) {
            Toast.makeText(this, "Group calls coming soon", Toast.LENGTH_SHORT).show()
            return
        }
        val currentUid = auth.uid ?: return
        db.collection("users").document(currentUid).get()
            .addOnSuccessListener { userDoc ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                val callerName = userDoc.getString("name") ?: "A Friend"
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra("callType", type)
                    putExtra("receiverId", receiverId)
                    putExtra("receiverName", receiverName)
                    putExtra("callerName", callerName)
                    putExtra("channelName", chatId)
                    putExtra("isCaller", true)
                }
                startActivity(intent)
            }
            .addOnFailureListener {
                if (isFinishing || isDestroyed) return@addOnFailureListener
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra("callType", type)
                    putExtra("receiverId", receiverId)
                    putExtra("receiverName", receiverName)
                    putExtra("callerName", "A Friend")
                    putExtra("channelName", chatId)
                    putExtra("isCaller", true)
                }
                startActivity(intent)
            }
    }

    private fun showChatOptions(view: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, view)
        popup.menu.add("Clear History")
        popup.menu.add("Schedule Message")
        if (isGroup) {
            popup.menu.add("Group Info")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Clear History" -> clearChat()
                    "Schedule Message" -> showScheduleMessageDialog()
                    "Group Info" -> showGroupInfo()
                }
                true
            }
            popup.show()
        } else {
            val currentUid = auth.uid ?: return
            db.collection("secretConversations").document("${currentUid}_${receiverId}").get()
                .addOnSuccessListener { doc ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener
                    val isHidden = doc.exists()
                    if (isHidden) {
                        popup.menu.add("Unhide Chat")
                    } else {
                        popup.menu.add("Hide Chat (Secret)")
                    }
                    popup.menu.add("Block User")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.title) {
                            "Clear History" -> clearChat()
                            "Schedule Message" -> showScheduleMessageDialog()
                            "Hide Chat (Secret)" -> hideChat()
                            "Unhide Chat" -> unhideChat()
                            "Block User" -> blockUser()
                        }
                        true
                    }
                    popup.show()
                }
                .addOnFailureListener {
                    if (isFinishing || isDestroyed) return@addOnFailureListener
                    popup.menu.add("Hide Chat (Secret)")
                    popup.menu.add("Block User")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.title) {
                            "Clear History" -> clearChat()
                            "Schedule Message" -> showScheduleMessageDialog()
                            "Hide Chat (Secret)" -> hideChat()
                            "Block User" -> blockUser()
                        }
                        true
                    }
                    popup.show()
                }
        }
    }

    private fun showScheduleMessageDialog(prefillText: String = "") {
        val primaryColor = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val textPrimaryColor = resolveThemeColor(android.R.attr.textColorPrimary)
        val textSecondaryColor = resolveThemeColor(android.R.attr.textColorSecondary)

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val input = EditText(this).apply {
            hint = "Type your message or link..."
            setPadding(32, 32, 32, 32)
            minLines = 2
            setText(prefillText)
            setSelection(text.length)
            setTextColor(textPrimaryColor)
            setHintTextColor(textSecondaryColor)
            setBackgroundResource(R.drawable.edit_text_bg)
        }
        rootLayout.addView(input)

        // Attachment status
        val attachLabel = android.widget.TextView(this).apply {
            setPadding(8, 16, 8, 8)
            textSize = 13f
            if (scheduledAttachedUri != null) {
                text = "📎 Attached: ${scheduledAttachedName ?: "File"}"
                setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                text = "No attachment"
                setTextColor(textSecondaryColor)
            }
        }
        rootLayout.addView(attachLabel)

        // Buttons row
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        val btnImage = android.widget.Button(this).apply {
            text = "🖼️ Image"
            textSize = 12f
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = 24f
            }
            background = gd
            setOnClickListener {
                scheduledDialogText = input.text.toString()
                pickScheduledAttachmentMedia.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        val btnDoc = android.widget.Button(this).apply {
            text = "📄 File/PDF"
            textSize = 12f
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = 24f
            }
            background = gd
            setOnClickListener {
                scheduledDialogText = input.text.toString()
                pickScheduledAttachmentDoc.launch(arrayOf("*/*"))
            }
        }
        val btnRemove = android.widget.Button(this).apply {
            text = "❌ Remove"
            textSize = 12f
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.RED)
                cornerRadius = 24f
            }
            background = gd
            visibility = if (scheduledAttachedUri != null) View.VISIBLE else View.GONE
            setOnClickListener {
                scheduledAttachedUri = null
                scheduledAttachedType = null
                scheduledAttachedMime = null
                scheduledAttachedName = null
                showScheduleMessageDialog(input.text.toString())
            }
        }

        val btnParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 0, 8, 0)
        }
        btnRow.addView(btnImage, btnParams)
        btnRow.addView(btnDoc, btnParams)
        if (scheduledAttachedUri != null) {
            btnRow.addView(btnRemove, btnParams)
        }
        rootLayout.addView(btnRow)

        AlertDialog.Builder(this)
            .setTitle("📅 Schedule Message")
            .setView(rootLayout)
            .setPositiveButton("Pick Date & Time") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty() && scheduledAttachedUri == null) {
                    Toast.makeText(this, "Please enter a message or attach a file", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pickScheduleDateTime(text)
            }
            .setNegativeButton("Cancel") { _, _ ->
                scheduledAttachedUri = null
                scheduledAttachedType = null
                scheduledAttachedMime = null
                scheduledAttachedName = null
                scheduledDialogText = ""
            }
            .show()
    }

    private fun pickScheduleDateTime(messageText: String) {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val scheduledCalendar = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                }
                val scheduledTime = scheduledCalendar.timeInMillis
                if (scheduledTime <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Please pick a future time", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                saveScheduledMessage(messageText, scheduledTime)
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveScheduledMessage(text: String, scheduledFor: Long) {
        val currentUid = auth.uid ?: return

        lifecycleScope.launch {
            try {
                var mediaUrlData: String? = null
                var msgType = "TEXT"
                var msgContent = text
                var fileName: String? = null

                val attachUri = scheduledAttachedUri
                val attachType = scheduledAttachedType
                val attachMime = scheduledAttachedMime

                if (attachUri != null && attachType != null) {
                    Toast.makeText(this@ChatActivity, "Processing attachment...", Toast.LENGTH_SHORT).show()

                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val inputStream = contentResolver.openInputStream(attachUri)
                        val b = inputStream?.readBytes() ?: throw Exception("Failed to read file")
                        inputStream.close()
                        b
                    }

                    if (attachType == "IMAGE") {
                        var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val maxDim = 800
                        val w = bitmap.width; val h = bitmap.height
                        val newBitmap = if (w > maxDim || h > maxDim) {
                            val ratio = w.toFloat() / h.toFloat()
                            val nw = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
                            val nh = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
                            android.graphics.Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                        } else bitmap
                        val out = java.io.ByteArrayOutputStream()
                        newBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 30, out)
                        val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT)
                        mediaUrlData = "data:image/jpeg;base64,$base64"
                        msgType = "IMAGE"
                        if (msgContent.isEmpty()) msgContent = "Scheduled Image"
                    } else {
                        if (bytes.size > 800000) {
                            Toast.makeText(this@ChatActivity, "File too large (max ~800KB for free plan)", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                        val mime = attachMime ?: "application/octet-stream"
                        mediaUrlData = "data:$mime;base64,$base64"
                        msgType = attachType
                        fileName = scheduledAttachedName
                        if (msgContent.isEmpty()) msgContent = "File: ${fileName ?: "document"}"
                    }
                }

                val msgId = db.collection("scheduledMessages").document().id
                val data = hashMapOf(
                    "messageId" to msgId,
                    "senderId" to currentUid,
                    "receiverId" to (groupId ?: receiverId ?: ""),
                    "conversationId" to chatId,
                    "content" to msgContent,
                    "type" to msgType,
                    "mediaUrl" to mediaUrlData,
                    "latitude" to null,
                    "longitude" to null,
                    "fileName" to fileName,
                    "isGroup" to isGroup,
                    "isSnap" to false,
                    "pollQuestion" to "",
                    "pollOptions" to emptyList<String>(),
                    "effect" to "NONE",
                    "scheduledFor" to scheduledFor,
                    "sent" to false
                )
                db.collection("scheduledMessages").document(msgId).set(data).await()
                val time = java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(scheduledFor))
                Toast.makeText(this@ChatActivity, "Message scheduled for $time", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Schedule failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                // Clear attachment state
                scheduledAttachedUri = null
                scheduledAttachedType = null
                scheduledAttachedMime = null
                scheduledAttachedName = null
                scheduledDialogText = ""
            }
        }
    }

    private fun unhideChat() {
        val uid = auth.currentUser?.uid ?: return
        val partnerId = receiverId ?: return
        db.collection("secretConversations").document("${uid}_${partnerId}").delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Chat unhidden from Secret tab", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to unhide: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showGroupInfo() {
        val currentUid = auth.uid ?: return
        val isAdmin = currentUid == groupAdminId
        
        db.collection("users").whereIn("uid", groupMemberIds).get().addOnSuccessListener { snapshot ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            val users = snapshot.toObjects(com.example.chatsnap.models.User::class.java)
            val names = users.map { 
                var n = it.name
                if (it.uid == groupAdminId) n += " (Admin)"
                n
            }.toTypedArray()
            
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Group Members")
            builder.setItems(names) { _, which ->
                val selectedUser = users[which]
                if (isAdmin && selectedUser.uid != groupAdminId) {
                    showRemoveUserDialog(selectedUser)
                }
            }
            builder.setPositiveButton("Close", null)
            builder.show()
        }
    }

    private fun showRemoveUserDialog(user: com.example.chatsnap.models.User) {
        AlertDialog.Builder(this)
            .setTitle("Remove User")
            .setMessage("Are you sure you want to remove ${user.name} from the group?")
            .setPositiveButton("Remove") { _, _ ->
                val newMembers = groupMemberIds.toMutableList()
                newMembers.remove(user.uid)
                db.collection("groups").document(groupId!!).update("memberIds", newMembers)
                    .addOnSuccessListener {
                        groupMemberIds = newMembers
                        Toast.makeText(this, "${user.name} removed", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearChat() {
        db.collection("messages").whereEqualTo("conversationId", chatId).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) return@addOnSuccessListener
                
                val documents = snapshot.documents
                val chunks = documents.chunked(500)
                
                var completed = 0
                for (chunk in chunks) {
                    val batch = db.batch()
                    for (doc in chunk) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().addOnSuccessListener {
                        completed++
                        if (completed == chunks.size) {
                            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Clear failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showAttachBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val bottomSheetBinding = BottomSheetAttachBinding.inflate(layoutInflater)
        dialog.setContentView(bottomSheetBinding.root)

        bottomSheetBinding.btnImage.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            dialog.dismiss()
        }
        bottomSheetBinding.btnSnap.setOnClickListener { openCameraForSnap(); dialog.dismiss() }
        bottomSheetBinding.btnCamera.setOnClickListener { openCamera(); dialog.dismiss() }
        bottomSheetBinding.btnLocation.setOnClickListener { sendLocation(); dialog.dismiss() }
        bottomSheetBinding.btnDocument.setOnClickListener { pickDocument.launch(arrayOf("*/*")); dialog.dismiss() }
        bottomSheetBinding.btnPoll.setOnClickListener { showPollDialog(); dialog.dismiss() }
        bottomSheetBinding.btnScanDocument.setOnClickListener {
            val intent = Intent(this, com.example.chatsnap.scanner.ui.DocumentScannerActivity::class.java).apply {
                putExtra("launched_from_chat", true)
            }
            scanDocumentLauncher.launch(intent)
            dialog.dismiss()
        }
        bottomSheetBinding.btnSchedule.setOnClickListener {
            showScheduleMessageDialog()
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private val aiChatLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val draft = result.data?.getStringExtra("response_draft")
            if (!draft.isNullOrEmpty()) {
                binding.etMessage.setText(draft)
            }
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 103)
            return
        }
        val photoFile = File.createTempFile("IMG_${System.currentTimeMillis()}", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES))
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        latestPhotoUri = uri
        takePhoto.launch(uri)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && latestPhotoUri != null) {
            showMediaPreview(latestPhotoUri!!, if (isSnapPending) "SNAP" else "IMAGE")
            isSnapPending = false
        }
    }

    private var isSnapPending = false
    private fun openCameraForSnap() {
        isSnapPending = true
        openCamera()
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { 
            val mimeType = contentResolver.getType(it)
            val type = if (mimeType?.startsWith("video") == true) "VIDEO" else "IMAGE"
            showMediaPreview(it, type) 
        }
    }

    private fun showMediaPreview(uri: Uri, type: String) {
        pendingMediaUri = uri
        pendingMediaType = type
        binding.mediaPreviewLayout.visibility = View.VISIBLE
        binding.ivSelectedMedia.setImageURI(uri)
        updateSendButtonUI()
    }

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val mimeType = contentResolver.getType(it) ?: ""
            val type = if (mimeType.startsWith("audio/")) {
                "AUDIO"
            } else if (mimeType.startsWith("image/")) {
                "IMAGE"
            } else if (mimeType.startsWith("video/")) {
                "VIDEO"
            } else {
                "DOCUMENT"
            }
            uploadFile(it, type)
        }
    }

    private val pickScheduledAttachmentMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            scheduledAttachedUri = it
            scheduledAttachedType = "IMAGE"
            scheduledAttachedMime = contentResolver.getType(it) ?: "image/jpeg"
            scheduledAttachedName = "Image"
            showScheduleMessageDialog(scheduledDialogText)
        }
    }

    private val pickScheduledAttachmentDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val mime = contentResolver.getType(it) ?: "application/octet-stream"
            scheduledAttachedUri = it
            scheduledAttachedType = if (mime.startsWith("audio/")) "AUDIO" else "DOCUMENT"
            scheduledAttachedMime = mime
            scheduledAttachedName = getFileName(it)
            showScheduleMessageDialog(scheduledDialogText)
        }
    }

    private val scanDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uriString = result.data?.getStringExtra("scanned_file_uri")
            val type = result.data?.getStringExtra("scanned_file_type") ?: "DOCUMENT"
            if (uriString != null) {
                uploadFile(Uri.parse(uriString), type)
            }
        }
    }

    private fun sendLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }
        
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                sendMessage("Shared a location", "LOCATION", lat = loc.latitude, lon = loc.longitude)
            } else {
                // If last location is null, request a fresh one
                Toast.makeText(this, "Fetching fresh location...", Toast.LENGTH_SHORT).show()
                client.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { freshLoc ->
                        freshLoc?.let { sendMessage("Shared a location", "LOCATION", lat = it.latitude, lon = it.longitude) }
                    }
            }
        }
    }

    private fun showPollDialog() {
        val pollBinding = DialogCreatePollBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(pollBinding.root).create()
        
        val textPrimaryColor = resolveThemeColor(android.R.attr.textColorPrimary)
        val textSecondaryColor = resolveThemeColor(android.R.attr.textColorSecondary)

        pollBinding.btnAddOption.setOnClickListener {
            val et = EditText(this).apply {
                hint = "Option ${pollBinding.optionsContainer.childCount + 1}"
                setPadding(32, 32, 32, 32)
                setTextColor(textPrimaryColor)
                setHintTextColor(textSecondaryColor)
                setBackgroundResource(R.drawable.edit_text_bg)
                val params = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
            }
            pollBinding.optionsContainer.addView(et)
        }

        pollBinding.btnCreatePoll.setOnClickListener {
            val question = pollBinding.etQuestion.text.toString().trim()
            val options = mutableListOf<String>()
            for (i in 0 until pollBinding.optionsContainer.childCount) {
                val et = pollBinding.optionsContainer.getChildAt(i) as? EditText
                val opt = et?.text?.toString()?.trim() ?: ""
                if (opt.isNotEmpty()) options.add(opt)
            }
            
            if (question.isNotEmpty() && options.size >= 2) {
                sendMessage("Poll: $question", "POLL", pollQuestion = question, pollOptions = options)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please enter a question and at least 2 options", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun uploadFile(uri: Uri, type: String) {
        if (type == "VIDEO") {
            Toast.makeText(this, "Videos require Firebase Storage (Paid Plan). Use Photos/Snaps for free.", Toast.LENGTH_LONG).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        
        lifecycleScope.launch {
            try {
                val bytes = if (uri.scheme == "content") {
                    val inputStream = contentResolver.openInputStream(uri)
                    val b = inputStream?.readBytes() ?: throw Exception("Failed to read file")
                    inputStream.close()
                    b
                } else {
                    val path = uri.path ?: throw Exception("Invalid file path")
                    java.io.File(path).readBytes()
                }
                
                if (type == "IMAGE" || type == "SNAP") {
                    var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    
                    // Resize to avoid OOM and keep Firestore doc small
                    val maxDimension = 1000
                    val width = bitmap.width
                    val height = bitmap.height
                    val newBitmap = if (width > maxDimension || height > maxDimension) {
                        val ratio = width.toFloat() / height.toFloat()
                        val newWidth = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
                        val newHeight = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
                        android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    } else {
                        bitmap
                    }

                    val out = java.io.ByteArrayOutputStream()
                    // Higher compression for stability
                    newBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, out)
                    val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT)
                    val finalData = "data:image/jpeg;base64,$base64"
                    
                    // Check size limit (1MB for Firestore)
                    if (finalData.length > 900000) {
                        Toast.makeText(this@ChatActivity, "Image too large for free plan. Try a smaller photo.", Toast.LENGTH_LONG).show()
                        binding.progressBar.visibility = View.GONE
                        return@launch
                    }

                    sendMessage(
                        content = if (type == "SNAP") "Snap" else "Image",
                        type = type,
                        mediaUrl = finalData,
                        isSnap = (type == "SNAP")
                    )
                } else if (type == "AUDIO") {
                    if (bytes.size > 800000) {
                        Toast.makeText(this@ChatActivity, "Audio too long for free plan.", Toast.LENGTH_LONG).show()
                        binding.progressBar.visibility = View.GONE
                        return@launch
                    }
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    val mimeType = if (uri.scheme == "content") {
                        contentResolver.getType(uri) ?: "audio/m4a"
                    } else {
                        val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/m4a"
                    }
                    val finalData = "data:$mimeType;base64,$base64"
                    sendMessage("Audio Message", "AUDIO", finalData)
                } else {
                    if (bytes.size > 800000) {
                        Toast.makeText(this@ChatActivity, "File too large for free plan.", Toast.LENGTH_LONG).show()
                        binding.progressBar.visibility = View.GONE
                        return@launch
                    }
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    val mimeType = if (uri.scheme == "content") {
                        contentResolver.getType(uri) ?: "application/octet-stream"
                    } else {
                        val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    }
                    sendMessage("File: ${getFileName(uri)}", type, "data:$mimeType;base64,$base64")
                }
                
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ChatActivity, "Sent successfully", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Log.e("ChatActivity", "Bypass error: ${e.message}", e)
                Toast.makeText(this@ChatActivity, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        return result ?: "file"
    }

    private fun sendMessage(
        content: String, 
        type: String, 
        mediaUrl: String? = null, 
        lat: Double? = null, 
        lon: Double? = null, 
        fileName: String? = null,
        pollQuestion: String = "",
        pollOptions: List<String> = emptyList(),
        isSnap: Boolean = false,
        effect: String = "NONE"
    ) {
        val currentUid = auth.uid ?: return
        
        // Check if we are blocked by this user (only for private chats)
        if (!isGroup && receiverId != null) {
            db.collection("blocks").document("${receiverId}_${currentUid}").get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        Toast.makeText(this, "You are blocked by this user", Toast.LENGTH_SHORT).show()
                    } else {
                        performSendMessage(content, type, mediaUrl, lat, lon, fileName, pollQuestion, pollOptions, isSnap, effect)
                    }
                }
                .addOnFailureListener {
                    performSendMessage(content, type, mediaUrl, lat, lon, fileName, pollQuestion, pollOptions, isSnap, effect)
                }
        } else {
            performSendMessage(content, type, mediaUrl, lat, lon, fileName, pollQuestion, pollOptions, isSnap, effect)
        }
    }

    private fun performSendMessage(
        content: String, 
        type: String, 
        mediaUrl: String? = null, 
        lat: Double? = null, 
        lon: Double? = null, 
        fileName: String? = null,
        pollQuestion: String = "",
        pollOptions: List<String> = emptyList(),
        isSnap: Boolean = false,
        effect: String = "NONE"
    ) {
        val currentUid = auth.uid ?: return
        val collectionName = if (isGroup) "groupMessages" else "messages"
        val messageId = db.collection(collectionName).document().id
        val messageData = hashMapOf(
            "messageId" to messageId,
            "senderId" to currentUid,
            "receiverId" to (groupId ?: receiverId!!),
            "conversationId" to chatId,
            "content" to content,
            "type" to type,
            "mediaUrl" to mediaUrl,
            "latitude" to lat,
            "longitude" to lon,
            "fileName" to fileName,
            "timestamp" to System.currentTimeMillis(),
            "status" to "SENT",
            "viewed" to false,
            "isGroup" to isGroup,
            "isDeleted" to false,
            "isSnap" to (isSnap || type == "SNAP"),
            "pollQuestion" to pollQuestion,
            "pollOptions" to pollOptions,
            "pollVotes" to hashMapOf<String, Int>(),
            "effect" to effect
        )
        db.collection(collectionName).document(messageId).set(messageData)
            .addOnSuccessListener {
                if (isGroup && groupId != null) {
                    val groupUpdate = hashMapOf<String, Any>(
                        "lastMessage" to content,
                        "lastMessageTimestamp" to System.currentTimeMillis()
                    )
                    db.collection("groups").document(groupId!!).update(groupUpdate)
                } else if (!isGroup && receiverId != null) {
                    // Only Snaps contribute to streaks as per requirements
                    if (type == "SNAP") {
                        updateStreak(receiverId!!)
                    }
                    if (receiverId != currentUid) {
                        // Dispatch Real-time FCM Notification
                        db.collection("users").document(currentUid).get().addOnSuccessListener { userDoc ->
                            val senderName = userDoc.getString("name") ?: "A Friend"
                            val msgText = if (type == "SNAP" || isSnap) "send you snap" else "send you chat"
                            com.example.chatsnap.utils.FcmNotificationSender.sendNotification(
                                receiverId = receiverId!!,
                                senderName = senderName,
                                messageContent = msgText,
                                chatId = chatId,
                                type = "SINGLE"
                            )
                        }
                    }
                }
                com.example.chatsnap.utils.TaskUtils.markTaskAsDone("TASK_MESSAGE")
            }
            .addOnFailureListener {
                Toast.makeText(this, "Send failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateStreak(partnerId: String) {
        val currentUid = auth.uid ?: return
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        
        val myStreakRef = db.collection("users").document(currentUid).collection("streaks").document(partnerId)
        val partnerStreakRef = db.collection("users").document(partnerId).collection("streaks").document(currentUid)
        
        myStreakRef.get().addOnSuccessListener { doc ->
            var count = doc.getLong("count")?.toInt() ?: 0
            val lastSentByMe = doc.getLong("lastSentByMe") ?: 0L
            val lastSentByPartner = doc.getLong("lastSentByPartner") ?: 0L
            
            // 1. Check for expiration: If either hasn't sent a snap in 24h, reset
            if (count > 0) {
                val timeSinceMe = now - lastSentByMe
                val timeSincePartner = now - lastSentByPartner
                if (timeSinceMe > twentyFourHours || timeSincePartner > twentyFourHours) {
                    count = 0 
                }
            }
            
            // 2. Increment logic: If starting fresh or continuing
            // We increment if this is the first message from me today AND partner has sent one recently
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            val todayStart = calendar.timeInMillis
            
            val alreadySentToday = lastSentByMe >= todayStart
            
            if (!alreadySentToday) {
                // Streak only increments/starts if the partner has sent a snap within the last 24h
                if (now - lastSentByPartner < twentyFourHours) {
                    count++
                }
            }
            
            val myData = hashMapOf(
                "count" to count,
                "lastSentByMe" to now,
                "lastSentByPartner" to lastSentByPartner,
                "lastTimestamp" to now,
                "partnerId" to partnerId
            )
            
            val partnerData = hashMapOf(
                "count" to count,
                "lastSentByMe" to lastSentByPartner,
                "lastSentByPartner" to now,
                "lastTimestamp" to now,
                "partnerId" to currentUid
            )
            
            myStreakRef.set(myData)
            partnerStreakRef.set(partnerData)
        }
    }

    private fun deleteMessage(message: Message) {
        val collectionName = if (isGroup) "groupMessages" else "messages"
        db.collection(collectionName).document(message.messageId).update("isDeleted", true)
    }

    private fun hideChat() {
        val uid = auth.currentUser?.uid ?: return
        val partnerId = receiverId ?: return
        
        val secretData = hashMapOf(
            "userId" to uid,
            "partnerId" to partnerId,
            "timestamp" to com.google.firebase.Timestamp.now()
        )
        
        db.collection("secretConversations").document("${uid}_${partnerId}").set(secretData)
            .addOnSuccessListener {
                Toast.makeText(this, "Chat hidden in Secret tab", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun blockUser() {
        val uid = auth.currentUser?.uid ?: return
        val partnerId = receiverId ?: return

        com.example.chatsnap.utils.UIUtils.showCustomDialog(
            this,
            "Block User",
            "Are you sure you want to block this user? You will no longer receive messages from them.",
            "Block",
            "Cancel",
            onPositive = {
                val blockData = hashMapOf(
                    "blockerId" to uid,
                    "blockedId" to partnerId,
                    "timestamp" to com.google.firebase.Timestamp.now()
                )
                db.collection("blocks").document("${uid}_${partnerId}").set(blockData)
                    .addOnSuccessListener {
                        Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show()
                        finish()
                    }
            }
        )
    }

    private fun forwardMessage(message: Message) {
        val intent = Intent(this, ForwardActivity::class.java).apply {
            putExtra("msg_content", message.content)
            putExtra("msg_type", message.type)
            putExtra("msg_media_url", message.mediaUrl)
            message.latitude?.let { putExtra("msg_latitude", it) }
            message.longitude?.let { putExtra("msg_longitude", it) }
            putExtra("msg_file_name", message.fileName)
            putExtra("msg_is_snap", message.isSnap)
            putExtra("msg_poll_question", message.pollQuestion)
            if (message.pollOptions.isNotEmpty()) {
                putStringArrayListExtra("msg_poll_options", ArrayList(message.pollOptions))
            }
            putExtra("msg_effect", message.effect)
        }
        startActivity(intent)
    }

    private fun viewMedia(message: Message) {
        if (message.mediaUrl == null) return
        
        if (message.type == "SNAP" || message.isSnap) {
            if (message.senderId == auth.uid) {
                Toast.makeText(this, "You cannot open your own Snaps", Toast.LENGTH_SHORT).show()
                return
            }
            if (message.viewed && message.receiverId == auth.uid) {
                Toast.makeText(this, "Snap already opened", Toast.LENGTH_SHORT).show()
                return
            }
        }

        showFullScreenMedia(message)

        if (!message.viewed && message.receiverId == auth.uid) {
            db.collection("messages").document(message.messageId).update("viewed", true)
        }
    }

    private fun handleVote(message: Message, optionIndex: Int) {
        val uid = auth.uid ?: return
        db.collection("messages").document(message.messageId)
            .update("pollVotes.$uid", optionIndex)
    }

    private fun showFullScreenMedia(message: Message) {
        val intent = Intent(this, MediaViewerActivity::class.java)
        intent.putExtra("mediaUrl", message.mediaUrl)
        intent.putExtra("mediaType", message.type)
        startActivity(intent)
    }

    private fun setupReply(message: Message) {
        binding.replyPreview.visibility = View.VISIBLE
        binding.tvReplyName.text = if (message.senderId == auth.uid) "You" else receiverName
        binding.tvReplyMessage.text = if (message.isDeleted) "Deleted Message" else message.content
    }

    private fun addReaction(message: Message, emoji: String) {
        val reactions = message.reactions.toMutableMap()
        reactions[auth.uid!!] = emoji
        db.collection("messages").document(message.messageId).update("reactions", reactions)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceRecording() {
        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (checkAudioPermissions()) {
                        micDownX = event.rawX
                        isCancelSlide = false
                        startRecording()
                        showRecordingOverlay()
                    } else requestAudioPermissions()
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - micDownX
                    // if user slides left by more than 150px, mark as cancel
                    if (deltaX < -150 && !isCancelSlide) {
                        isCancelSlide = true
                        binding.tvRecordingHint.text = "Release to cancel"
                        binding.ivRecordingMic.setColorFilter(android.graphics.Color.RED)
                    } else if (deltaX > -100 && isCancelSlide) {
                        isCancelSlide = false
                        binding.tvRecordingHint.text = "Slide left to cancel"
                        binding.ivRecordingMic.clearColorFilter()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording(send = !isCancelSlide)
                    hideRecordingOverlay()
                }
            }
            true
        }
    }

    private fun showRecordingOverlay() {
        binding.recordingOverlay.visibility = View.VISIBLE
        startAmplitudePolling()
    }

    private fun hideRecordingOverlay() {
        binding.recordingOverlay.visibility = View.GONE
        binding.ivRecordingMic.clearColorFilter()
        binding.tvRecordingHint.text = "Slide left to cancel"
        stopAmplitudePolling()
    }

    private fun startAmplitudePolling() {
        if (recordingHandler != null) return
        recordingHandler = Handler(Looper.getMainLooper())
        amplitudeRunnable = object : Runnable {
            override fun run() {
                try {
                    val amp = mediaRecorder?.maxAmplitude ?: 0
                    // Map amplitude (0..32767) to scale 1.0..1.8
                    val normalized = (amp / 32767f).coerceIn(0f, 1f)
                    val scale = 1.0f + (normalized * 0.8f)
                    binding.ivRecordingMic.scaleX = scale
                    binding.ivRecordingMic.scaleY = scale
                } catch (e: Exception) {
                }
                recordingHandler?.postDelayed(this, 120)
            }
        }
        recordingHandler?.post(amplitudeRunnable!!)
    }

    private fun stopAmplitudePolling() {
        amplitudeRunnable?.let { recordingHandler?.removeCallbacks(it) }
        amplitudeRunnable = null
        recordingHandler = null
        binding.ivRecordingMic.scaleX = 1.0f
        binding.ivRecordingMic.scaleY = 1.0f
    }

    private fun startRecording() {
        try {
            audioPath = "${externalCacheDir?.absolutePath}/voice_${System.currentTimeMillis()}.m4a"
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioPath)
                prepare()
                start()
            }
            android.util.Log.d("VOICE_REC", "Recording started successfully. Path: $audioPath")
            isRecordingActive = true
            startAmplitudePolling()
        } catch (e: Exception) {
            android.util.Log.e("VOICE_REC", "Failed to start voice recorder: ${e.message}", e)
            Toast.makeText(this, "Mic Init Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording(send: Boolean = true) {
        try {
            if (!isRecordingActive && mediaRecorder == null) return
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecordingActive = false
            audioPath?.let {
                if (send) {
                    uploadFile(Uri.fromFile(File(it)), "AUDIO")
                } else {
                    try { File(it).delete() } catch (_: Exception) {}
                }
            }
            audioPath = null
        } catch (e: Exception) {
            android.util.Log.e("VOICE_REC", "Failed to stop/upload recording: ${e.message}", e)
        } finally {
            stopAmplitudePolling()
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            messages,
            onReaction = { msg, emoji -> addReaction(msg, emoji) },
            onSwipe = { setupReply(it) },
            onForward = { forwardMessage(it) },
            onDelete = { deleteMessage(it) },
            onMediaClick = { viewMedia(it) },
            onVote = { msg, index -> handleVote(msg, index) }
        )
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatRecyclerView.adapter = chatAdapter
    }

    private fun listenForMessages() {
        val collectionName = if (isGroup) "groupMessages" else "messages"
        db.collection(collectionName)
            .whereEqualTo("conversationId", chatId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.message?.contains("index") == true) {
                        db.collection(collectionName)
                            .whereEqualTo("conversationId", chatId)
                            .addSnapshotListener { s, _ ->
                                if (s != null && !isFinishing) {
                                    val newMessages = s.toObjects(Message::class.java).sortedBy { 
                                        val t = it.timestamp
                                        when (t) {
                                            is Long -> t
                                            is com.google.firebase.Timestamp -> t.toDate().time
                                            is Map<*, *> -> (t["seconds"] as? Long ?: 0L) * 1000
                                            else -> 0L
                                        }
                                    }
                                    messages.clear()
                                    messages.addAll(newMessages)
                                    chatAdapter.updateData(messages)
                                    if (messages.isNotEmpty()) {
                                        binding.chatRecyclerView.scrollToPosition(messages.size - 1)
                                    }
                                }
                            }
                    }
                    return@addSnapshotListener
                }
                
                if (snapshot != null && !isFinishing) {
                    messages.clear()
                    val newMessages = snapshot.toObjects(Message::class.java)
                    messages.addAll(newMessages)
                    chatAdapter.updateData(messages)
                    if (messages.isNotEmpty()) {
                        binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)
                    }

                    val currentUid = auth.uid
                    snapshot.documentChanges.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val msg = change.document.toObject(Message::class.java)
                            if (msg.receiverId == currentUid && msg.status != "READ") {
                                val isSnap = msg.type == "SNAP" || msg.isSnap
                                if (isSnap) {
                                    // For snaps, only mark as READ (delivered), don't mark as viewed yet
                                    change.document.reference.update("status", "READ")
                                } else {
                                    // For regular messages, mark as both READ and viewed
                                    change.document.reference.update("status", "READ", "viewed", true)
                                }
                            }
                        }
                    }
                }
            }
    }
    
    private fun fetchPartnerInfo() {
        if (isGroup) {
            binding.tvUserStatus.text = "Group Chat"
            return
        }
        val partnerId = receiverId ?: return
        
        // Listen to partner's user profile for online status
        db.collection("users").document(partnerId)
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists() && !isFinishing) {
                    val name = doc.getString("name") ?: "Chat"
                    val isOnline = doc.getBoolean("online") ?: false
                    binding.tvReceiverName.text = name
                    
                    if (binding.tvUserStatus.text != "Typing...") {
                        binding.tvUserStatus.text = if (isOnline) "Online" else "Offline"
                    }

                    val photo = doc.getString("profileImageUrl")
                    if (!photo.isNullOrEmpty()) {
                        if (photo.startsWith("data:image") || photo.length > 1000) {
                            try {
                                val cleanBase64 = photo.substringAfter(",")
                                val decodedString: ByteArray = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                                val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                                binding.ivReceiverProfile.setImageBitmap(decodedByte)
                            } catch (e: Exception) {}
                        } else {
                            binding.ivReceiverProfile.load(photo)
                        }
                    }
                }
            }
            
        // Listen to typing collection for typing status
        db.collection("typing").document(chatId)
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists() && !isFinishing) {
                    val typingUsers = doc.data ?: emptyMap<String, Any>()
                    val isPartnerTyping = typingUsers[partnerId] == true
                    
                    if (isPartnerTyping) {
                        binding.tvUserStatus.text = "Typing..."
                        binding.typingIndicator.root.visibility = View.VISIBLE
                        startTypingAnimation()
                    } else {
                        binding.typingIndicator.root.visibility = View.GONE
                        // Restore status from user profile (handled by other listener)
                    }
                }
            }
    }

    private fun startTypingAnimation() {
        if (!::binding.isInitialized || binding.typingIndicator.root.visibility != View.VISIBLE) return
        
        val dots = listOf(
            binding.typingIndicator.dot1,
            binding.typingIndicator.dot2,
            binding.typingIndicator.dot3
        )
        dots.forEachIndexed { index, dot ->
            dot.animate().scaleX(1.5f).scaleY(1.5f).alpha(0.5f).setDuration(400)
                .setStartDelay(index * 200L)
                .withEndAction {
                    if (::binding.isInitialized) {
                        dot.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(400)
                            .withEndAction { 
                                if (::binding.isInitialized && binding.typingIndicator.root.visibility == View.VISIBLE) {
                                    startTypingAnimation()
                                }
                            }
                    }
                }
        }
    }

    private fun markMessagesAsRead() {
        val currentUid = auth.uid ?: return
        val collectionName = if (isGroup) "groupMessages" else "messages"
        db.collection(collectionName)
            .whereEqualTo("conversationId", chatId)
            .whereEqualTo("receiverId", currentUid)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) return@addOnSuccessListener
                val batch = db.batch()
                var hasUpdates = false
                for (doc in snapshot.documents) {
                    val status = doc.getString("status")
                    val type = doc.getString("type") ?: "TEXT"
                    val isSnap = (doc.get("isSnap") == true) || (doc.get("snap") == true) || type == "SNAP"
                    
                    if (status != "READ") {
                        if (isSnap) {
                            // For snaps, only mark as READ (delivered/seen in chat), but NOT viewed (opened)
                            batch.update(doc.reference, "status", "READ")
                        } else {
                            // For regular messages, mark as both READ and viewed
                            batch.update(doc.reference, "status", "READ", "viewed", true)
                        }
                        hasUpdates = true
                    }
                }
                if (hasUpdates) batch.commit()
            }
    }

    private fun setupTextChangeListener() {
        binding.btnSend.setOnLongClickListener {
            val popup = androidx.appcompat.widget.PopupMenu(this, it)
            popup.menu.add("Normal")
            popup.menu.add("Shout (Shake)")
            popup.menu.add("Whisper (Fade)")
            popup.menu.add("Balloons")
            
            popup.setOnMenuItemClickListener { item ->
                val effect = when (item.title) {
                    "Shout (Shake)" -> "SHOUT"
                    "Whisper (Fade)" -> "WHISPER"
                    "Balloons" -> "BALLOONS"
                    else -> "NONE"
                }
                val content = binding.etMessage.text.toString().trim()
                if (content.isNotEmpty()) {
                    sendMessage(content, "TEXT", effect = effect)
                    binding.etMessage.setText("")
                }
                true
            }
            popup.show()
            true
        }

        binding.etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSendButtonUI()
                val isTyping = s?.isNotEmpty() == true
                val currentUid = auth.uid ?: return
                db.collection("typing").document(chatId)
                    .update(currentUid, isTyping)
                    .addOnFailureListener {
                        // If document doesn't exist, create it
                        db.collection("typing").document(chatId).set(mapOf(currentUid to isTyping))
                    }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun updateSendButtonUI() {
        val hasText = binding.etMessage.text.toString().trim().isNotEmpty()
        val hasMedia = pendingMediaUri != null
        val shouldShowSend = hasText || hasMedia

        binding.btnSend.visibility = if (shouldShowSend) View.VISIBLE else View.GONE
        binding.btnMic.visibility = if (shouldShowSend) View.GONE else View.VISIBLE
        binding.btnAttach.visibility = if (shouldShowSend) View.GONE else View.VISIBLE
    }

    private fun onDelete(message: com.example.chatsnap.models.Message) {
        val currentUid = auth.uid ?: return
        val collectionName = if (isGroup) "groupMessages" else "messages"
        
        val options = if (message.senderId == currentUid) {
            arrayOf("Unsend (Delete for Everyone)", "Delete for Me")
        } else {
            arrayOf("Delete for Me")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Unsend (Delete for Everyone)" -> {
                        db.collection(collectionName).document(message.messageId)
                            .update("isDeleted", true, "content", "This message was unsent")
                            .addOnSuccessListener {
                                Toast.makeText(this, "Message unsent", Toast.LENGTH_SHORT).show()
                            }
                    }
                    "Delete for Me" -> {
                        // In a real app, this would update a 'deletedFor' list
                        Toast.makeText(this, "Deleted for you", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun generateChatId(id1: String, id2: String): String = if (id1 < id2) "${id1}_${id2}" else "${id2}_${id1}"

    private fun resolveThemeColor(attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }
    private fun checkAudioPermissions(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAudioPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        val currentUid = auth.uid ?: return
        db.collection("typing").document(chatId).update(currentUid, false)
    }
}
