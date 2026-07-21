package com.example.chatsnap.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import coil.load
import androidx.core.content.ContextCompat
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ItemMessageReceivedBinding
import com.example.chatsnap.databinding.ItemMessageSentBinding
import com.example.chatsnap.models.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private var messages: List<Message>,
    private val onReaction: (Message, String) -> Unit,
    private val onSwipe: (Message) -> Unit,
    private val onForward: (Message) -> Unit,
    private val onDelete: (Message) -> Unit,
    private val onMediaClick: (Message) -> Unit,
    private val onVote: (Message, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val userCache = mutableMapOf<String, String>()
    private val adminCache = mutableMapOf<String, Boolean>()
    private val db = FirebaseFirestore.getInstance()

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var playingMessageId: String? = null
    private var activePlayButton: android.widget.TextView? = null

    private fun playAudio(message: Message, textView: android.widget.TextView) {
        val mediaUrl = message.mediaUrl ?: return
        val context = textView.context

        if (playingMessageId == message.messageId) {
            stopAudio()
            return
        }

        stopAudio()

        try {
            if (!mediaUrl.startsWith("data:audio")) {
                Toast.makeText(context, "Invalid audio format", Toast.LENGTH_SHORT).show()
                return
            }

            val cleanBase64 = mediaUrl.substringAfter(",")
            val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
            
            val tempFile = java.io.File.createTempFile("temp_voice_", ".m4a", context.cacheDir)
            tempFile.deleteOnExit()
            java.io.FileOutputStream(tempFile).use { fos ->
                fos.write(decodedBytes)
            }

            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                
                playingMessageId = message.messageId
                activePlayButton = textView
                
                textView.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_pause, 0, 0, 0)
                textView.text = "Playing Voice Note... 🔊"
                
                setOnCompletionListener {
                    stopAudio()
                }
            }
        } catch (e: java.lang.Exception) {
            Toast.makeText(context, "Failed to play voice note: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: java.lang.Exception) {}
        mediaPlayer = null
        
        activePlayButton?.let { tv ->
            tv.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
            tv.text = "Voice Note • Tap to Play 🎙️"
        }
        activePlayButton = null
        playingMessageId = null
    }

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        val myUid = FirebaseAuth.getInstance().uid
        
        // If the message is from an Admin, it should always be on the left (Official style)
        // We'll check the cache first. If not in cache, it will use standard logic until resolved.
        val isAdmin = adminCache[msg.senderId] ?: false
        if (isAdmin) return VIEW_TYPE_RECEIVED
        
        return if (msg.senderId == myUid) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SENT) {
            SentViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false))
        } else {
            ReceivedViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is SentViewHolder) holder.bind(message) else (holder as ReceivedViewHolder).bind(message)
    }

    override fun getItemCount(): Int = messages.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        stopAudio()
    }

    fun updateData(newMessages: List<Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    inner class SentViewHolder(private val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            val isMedia = (message.type == "IMAGE" || message.type == "VIDEO") && !message.mediaUrl.isNullOrEmpty()
            val isSnap = message.type == "SNAP" || message.isSnap
            val isPoll = message.type == "POLL"

            binding.pollContainer.visibility = if (isPoll) View.VISIBLE else View.GONE

            if (isMedia) {
                binding.ivMessageImage.visibility = View.VISIBLE
                val mediaUrl = message.mediaUrl
                if (mediaUrl != null && mediaUrl.startsWith("data:image")) {
                    try {
                        val cleanBase64 = mediaUrl.substringAfter(",")
                        val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        binding.ivMessageImage.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        binding.ivMessageImage.load(mediaUrl)
                    }
                } else {
                    binding.ivMessageImage.load(mediaUrl)
                }
                binding.tvMessage.text = if (message.type == "IMAGE") "Photo" else "Video"
            } else if (isSnap) {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.viewed) "Opened" else "Sent Snap"
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_camera, 0, 0, 0)
                binding.tvMessage.compoundDrawablePadding = 16
            } else if (isPoll) {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = message.pollQuestion
                renderPoll(binding.pollContainer, message)
            } else if (message.type == "LOCATION") {
                binding.ivMessageImage.visibility = View.VISIBLE
                binding.ivMessageImage.imageTintList = null
                val mapUrl = "https://static-maps.yandex.ru/1.x/?ll=${message.longitude},${message.latitude}&z=15&l=map&size=350,350&pt=${message.longitude},${message.latitude},pm2rdm"
                binding.ivMessageImage.load(mapUrl) {
                    placeholder(R.drawable.ic_location)
                    error(R.drawable.ic_location)
                }
                binding.tvMessage.text = "📍 Location Shared\n(Tap to view on Map)"
                val tv1 = android.util.TypedValue()
                binding.root.context.theme.resolveAttribute(com.example.chatsnap.R.attr.colorLink, tv1, true)
                binding.tvMessage.setTextColor(tv1.data)
            } else if (message.type == "AUDIO") {
                binding.ivMessageImage.visibility = View.GONE
                if (playingMessageId == message.messageId) {
                    binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_pause, 0, 0, 0)
                    binding.tvMessage.text = "Playing Voice Note... 🔊"
                } else {
                    binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
                    binding.tvMessage.text = "Voice Note • Tap to Play 🎙️"
                }
                binding.tvMessage.compoundDrawablePadding = 16
            } else if (message.type == "DOCUMENT") {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.isDeleted) "This message was deleted" else "📄 ${message.content}\n(Tap to Open)"
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            } else {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.isDeleted) "This message was deleted" else message.content
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            binding.tvTime.text = formatTime(message.timestamp)
            
            if (!message.isDeleted) {
                binding.ivStatus.visibility = View.VISIBLE
                when (message.status) {
                    "READ" -> {
                        binding.ivStatus.setImageResource(R.drawable.ic_tick_double)
                        val tv2 = android.util.TypedValue()
                        binding.root.context.theme.resolveAttribute(com.example.chatsnap.R.attr.colorLink, tv2, true)
                        binding.ivStatus.imageTintList = ColorStateList.valueOf(tv2.data)
                    }
                    "DELIVERED" -> {
                        binding.ivStatus.setImageResource(R.drawable.ic_tick_double)
                        val tv3 = android.util.TypedValue()
                        binding.root.context.theme.resolveAttribute(com.example.chatsnap.R.attr.colorError, tv3, true)
                        binding.ivStatus.imageTintList = ColorStateList.valueOf(tv3.data)
                    }
                    else -> {
                        binding.ivStatus.setImageResource(R.drawable.ic_tick_single)
                        binding.ivStatus.imageTintList = ColorStateList.valueOf(Color.GRAY)
                    }
                }
            } else {
                binding.ivStatus.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (isMedia || isSnap) onMediaClick(message)
                else if (message.type == "DOCUMENT") {
                    openDocument(message, binding.root.context)
                } else if (message.type == "LOCATION") {
                    val uri = "geo:${message.latitude},${message.longitude}?q=${message.latitude},${message.longitude}"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    binding.root.context.startActivity(intent)
                } else if (message.type == "AUDIO") {
                    playAudio(message, binding.tvMessage)
                }
            }

            setupCommonClickListeners(binding.root, message)
            applyEffects(binding.tvMessage, message.effect)
            renderReactions(binding.tvReactions, message)
        }
    }

    inner class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            val isMedia = (message.type == "IMAGE" || message.type == "VIDEO") && !message.mediaUrl.isNullOrEmpty()
            val isSnap = message.type == "SNAP" || message.isSnap
            val isPoll = message.type == "POLL"

            binding.pollContainer.visibility = if (isPoll) View.VISIBLE else View.GONE

            if (isMedia) {
                binding.ivMessageImage.visibility = View.VISIBLE
                val mediaUrl = message.mediaUrl
                if (mediaUrl != null && mediaUrl.startsWith("data:image")) {
                    try {
                        val cleanBase64 = mediaUrl.substringAfter(",")
                        val decodedString = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        binding.ivMessageImage.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        binding.ivMessageImage.load(mediaUrl)
                    }
                } else {
                    binding.ivMessageImage.load(mediaUrl)
                }
                binding.tvMessage.text = if (message.type == "IMAGE") "Photo" else "Video"
            } else if (isSnap) {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.viewed) "Opened" else "New Snap • Tap to View"
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_camera, 0, 0, 0)
                binding.tvMessage.compoundDrawablePadding = 16
            } else if (isPoll) {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = message.pollQuestion
                renderPoll(binding.pollContainer, message)
            } else if (message.type == "LOCATION") {
                binding.ivMessageImage.visibility = View.VISIBLE
                binding.ivMessageImage.imageTintList = null
                val mapUrl = "https://static-maps.yandex.ru/1.x/?ll=${message.longitude},${message.latitude}&z=15&l=map&size=350,350&pt=${message.longitude},${message.latitude},pm2rdm"
                binding.ivMessageImage.load(mapUrl) {
                    placeholder(R.drawable.ic_location)
                    error(R.drawable.ic_location)
                }
                binding.tvMessage.text = "📍 Location Shared\n(Tap to view on Map)"
                val tv4 = android.util.TypedValue()
                binding.root.context.theme.resolveAttribute(com.example.chatsnap.R.attr.colorLink, tv4, true)
                binding.tvMessage.setTextColor(tv4.data)
            } else if (message.type == "AUDIO") {
                binding.ivMessageImage.visibility = View.GONE
                if (playingMessageId == message.messageId) {
                    binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_pause, 0, 0, 0)
                    binding.tvMessage.text = "Playing Voice Note... 🔊"
                } else {
                    binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
                    binding.tvMessage.text = "Voice Note • Tap to Play 🎙️"
                }
                binding.tvMessage.compoundDrawablePadding = 16
            } else if (message.type == "DOCUMENT") {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.isDeleted) "This message was deleted" else "📄 ${message.content}\n(Tap to Open)"
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            } else {
                binding.ivMessageImage.visibility = View.GONE
                binding.tvMessage.text = if (message.isDeleted) "This message was deleted" else message.content
                binding.tvMessage.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            binding.tvTime.text = formatTime(message.timestamp)

            // Admin Logic: Show App Icon for Admin messages
            resolveUserData(message.senderId) { name, admin, photo ->
                if (admin) {
                    binding.ivSenderAvatar.visibility = View.VISIBLE
                    binding.ivSenderAvatar.setImageResource(R.drawable.ic_app_logo)
                    binding.tvSenderName.visibility = View.VISIBLE
                    binding.tvSenderName.text = "ChatSnap"
                    binding.tvSenderName.setTextColor(ContextCompat.getColor(binding.root.context, R.color.primary))
                    
                    // Trigger refresh once we know it's an admin to move to left if needed
                    if (adminCache[message.senderId] == null) {
                        adminCache[message.senderId] = true
                        notifyDataSetChanged()
                    }
                } else if (message.isGroup && !message.isDeleted) {
                    binding.ivSenderAvatar.visibility = View.VISIBLE
                    binding.tvSenderName.visibility = View.VISIBLE
                    binding.tvSenderName.text = name
                    binding.tvSenderName.setTextColor(ContextCompat.getColor(binding.root.context, R.color.primary))
                    binding.ivSenderAvatar.load(photo) { 
                        placeholder(R.drawable.ic_launcher_background)
                        crossfade(true) 
                    }
                } else {
                    binding.ivSenderAvatar.visibility = View.GONE
                    binding.tvSenderName.visibility = View.GONE
                }
            }

            binding.root.setOnClickListener {
                if (isMedia || isSnap) onMediaClick(message)
                else if (message.type == "DOCUMENT") {
                    openDocument(message, binding.root.context)
                } else if (message.type == "LOCATION") {
                    val uri = "geo:${message.latitude},${message.longitude}?q=${message.latitude},${message.longitude}"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    binding.root.context.startActivity(intent)
                } else if (message.type == "AUDIO") {
                    playAudio(message, binding.tvMessage)
                }
            }

            setupCommonClickListeners(binding.root, message)
            applyEffects(binding.tvMessage, message.effect)
            renderReactions(binding.tvReactions, message)
        }
    }

    private fun setupCommonClickListeners(view: View, message: Message) {
        view.setOnLongClickListener {
            if (!message.isDeleted) {
                showOptionsDialog(view, message)
            }
            true
        }
    }

    private fun showOptionsDialog(view: View, message: Message) {
        val options = if (message.type == "IMAGE" || message.type == "VIDEO" || message.type == "SNAP") {
            arrayOf("Reply", "Forward", "Delete", "React", "Save to Vault", "⚠️ Report")
        } else {
            arrayOf("Reply", "Forward", "Delete", "React", "⚠️ Report")
        }

        AlertDialog.Builder(view.context)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Reply" -> onSwipe(message)
                    "Forward" -> onForward(message)
                    "Delete" -> onDelete(message)
                    "React" -> showReactionDialog(view, message)
                    "Save to Vault" -> saveToVault(view, message)
                    "⚠️ Report" -> reportMessage(view, message)
                }
            }
            .show()
    }

    private fun reportMessage(view: View, message: Message) {
        val reasons = arrayOf("Spam", "Harassment", "Inappropriate Content", "Hate Speech", "Other")
        AlertDialog.Builder(view.context).setTitle("Report Reason").setItems(reasons) { _, which ->
            val uid = FirebaseAuth.getInstance().uid ?: return@setItems
            db.collection("users").document(uid).get().addOnSuccessListener { userDoc ->
                val reporterName = userDoc.getString("name") ?: "Unknown"
                val report = hashMapOf(
                    "messageId" to message.messageId,
                    "messageContent" to message.content,
                    "reportedUserId" to message.senderId,
                    "reporterId" to uid,
                    "reporterName" to reporterName,
                    "reason" to reasons[which],
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "pending"
                )
                db.collection("reports").add(report).addOnSuccessListener {
                    Toast.makeText(view.context, "Report submitted!", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }


    private fun saveToVault(view: View, message: Message) {
        val uid = FirebaseAuth.getInstance().uid ?: return
        db.collection("users").document(uid).collection("vault").document(message.messageId)
            .set(message)
            .addOnSuccessListener {
                Toast.makeText(view.context, "Saved to Media Vault", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(view.context, "Failed to save: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun renderPoll(container: LinearLayout, message: Message) {
        container.removeAllViews()
        val totalVotes = message.pollVotes.size
        message.pollOptions.forEachIndexed { index, option ->
            val voteCount = message.pollVotes.values.count { it == index }
            val percent = if (totalVotes > 0) (voteCount * 100 / totalVotes) else 0
            
            val pollView = LayoutInflater.from(container.context).inflate(R.layout.item_poll_option, container, false)
            val tvOption = pollView.findViewById<TextView>(R.id.tvOptionName)
            val tvPercent = pollView.findViewById<TextView>(R.id.tvPercent)
            val progress = pollView.findViewById<View>(R.id.voteProgress)
            
            tvOption.text = option
            tvPercent.text = "$percent%"
            
            val params = progress.layoutParams
            params.width = (container.context.resources.displayMetrics.widthPixels * 0.5 * percent / 100).toInt()
            progress.layoutParams = params

            // Show voters for this option
            val votersForThisOption = message.pollVotes.filter { it.value == index }.keys
            if (votersForThisOption.isNotEmpty()) {
                val tvVoters = TextView(container.context).apply {
                    textSize = 10f
                    val tv5 = android.util.TypedValue()
                    container.context.theme.resolveAttribute(android.R.attr.textColorSecondary, tv5, true)
                    setTextColor(tv5.data)
                    setPadding(10, 0, 10, 5)
                }
                pollView.findViewById<LinearLayout>(R.id.pollOptionLayout)?.addView(tvVoters)
                
                val voterNames = mutableListOf<String>()
                var processed = 0
                votersForThisOption.take(3).forEach { uid ->
                    resolveUserData(uid) { name, _, _ ->
                        voterNames.add(name)
                        processed++
                        if (processed == votersForThisOption.take(3).size) {
                            var text = voterNames.joinToString(", ")
                            if (votersForThisOption.size > 3) text += " and ${votersForThisOption.size - 3} others"
                            tvVoters.text = "Voted by: $text"
                        }
                    }
                }
            }

            pollView.setOnClickListener {
                onVote(message, index)
            }
            container.addView(pollView)
        }
    }

    private fun resolveUserData(uid: String, callback: (String, Boolean, String) -> Unit) {
        if (userCache.containsKey(uid)) {
            callback(userCache[uid]!!, adminCache[uid] ?: false, "") // Photo URL not cached for simplicity
            return
        }
        
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("name") ?: "User"
                val isAdmin = doc.getBoolean("isAdmin") ?: false
                val photo = doc.getString("profileImageUrl") ?: ""
                userCache[uid] = name
                adminCache[uid] = isAdmin
                callback(name, isAdmin, photo)
            }
            .addOnFailureListener {
                callback("User", false, "")
            }
    }

    private fun showReactionDialog(anchor: View, message: Message) {
        val reactions = arrayOf("❤️", "😂", "😮", "😢", "😡", "👍")
        val context = anchor.context
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.setPadding(30, 30, 30, 30)
        layout.gravity = android.view.Gravity.CENTER

        val dialog = AlertDialog.Builder(context)
            .setView(layout)
            .create()

        reactions.forEach { emoji ->
            val tv = TextView(context)
            tv.text = emoji
            tv.textSize = 28f
            tv.setPadding(15, 10, 15, 10)
            tv.setOnClickListener {
                onReaction(message, emoji)
                dialog.dismiss()
            }
            layout.addView(tv)
        }
        dialog.show()
    }

    private fun renderReactions(textView: TextView, message: Message) {
        if (message.reactions.isEmpty()) {
            textView.visibility = View.GONE
            return
        }

        textView.visibility = View.VISIBLE
        val reactionCounts = message.reactions.values.groupingBy { it }.eachCount()
        val display = reactionCounts.map { "${it.key} ${it.value}" }.joinToString("  ")
        textView.text = display
    }

    private fun formatTime(timestamp: Any?): String {
        val timeMillis = when (timestamp) {
            is com.google.firebase.Timestamp -> timestamp.toDate().time
            is Long -> timestamp
            is Map<*, *> -> (timestamp["seconds"] as? Long ?: 0L) * 1000
            else -> System.currentTimeMillis()
        }
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun applyEffects(view: View, effect: String) {
        when (effect) {
            "SHOUT" -> {
                view.scaleX = 1.2f
                view.scaleY = 1.2f
                view.animate().translationX(10f).setDuration(50).withEndAction {
                    view.animate().translationX(-20f).setDuration(50).withEndAction {
                        view.animate().translationX(20f).setDuration(50).withEndAction {
                            view.animate().translationX(0f).setDuration(50).start()
                        }
                    }
                }.start()
            }
            "WHISPER" -> {
                view.scaleX = 0.7f
                view.scaleY = 0.7f
                view.alpha = 0.6f
            }
            "BALLOONS" -> {
                view.animate().translationY(-20f).setDuration(500).withEndAction {
                    view.animate().translationY(0f).setDuration(500).start()
                }.start()
            }
            else -> {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.alpha = 1.0f
                view.translationX = 0f
                view.translationY = 0f
            }
        }
    }
    private fun openDocument(message: Message, context: android.content.Context) {
        val mediaUrl = message.mediaUrl ?: return
        try {
            val mimeType = mediaUrl.substringBefore(";base64,").substringAfter("data:")
            val cleanBase64 = mediaUrl.substringAfter(",")
            val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
            
            val rawName = message.content.removePrefix("File:").trim()
            val fileName = if (rawName.isEmpty()) "document" else rawName
            val tempFile = java.io.File(context.cacheDir, fileName)
            java.io.FileOutputStream(tempFile).use { fos ->
                fos.write(decodedBytes)
            }
            
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open document: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
