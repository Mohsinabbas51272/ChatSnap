package com.example.chatsnap.notes.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.MediaViewerActivity
import com.example.chatsnap.R
import com.example.chatsnap.databinding.ActivityNoteEditorBinding
import com.example.chatsnap.notes.data.Note
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NoteEditorActivity"
    }

    private lateinit var binding: ActivityNoteEditorBinding
    private val viewModel: NotesViewModel by viewModels()

    private var checklistAdapter: ChecklistAdapter? = null
    private var imageAttachmentAdapter: ImageAttachmentAdapter? = null
    private var docAttachmentAdapter: DocAttachmentAdapter? = null

    private var isUpdatingUi = false
    private var tempCameraUri: Uri? = null

    private val capturePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempCameraUri?.let { uri ->
                val localPath = saveImageToPersistentStorage(uri)
                if (localPath != null) {
                    viewModel.updateActiveNote { note ->
                        val list = note.getImages().toMutableList()
                        list.add(localPath)
                        note.setImages(list)
                    }
                }
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val localPath = saveImageToPersistentStorage(uri)
            if (localPath != null) {
                viewModel.updateActiveNote { note ->
                    val list = note.getImages().toMutableList()
                    list.add(localPath)
                    note.setImages(list)
                }
            }
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val localPath = saveDocToPersistentStorage(uri)
            if (localPath != null) {
                viewModel.updateActiveNote { note ->
                    val list = note.getDocuments().toMutableList()
                    list.add(localPath)
                    note.setDocuments(list)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityNoteEditorBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val noteId = intent.getLongExtra("note_id", -1L)
            Log.d(TAG, "onCreate: noteId=$noteId")

            if (noteId != -1L) {
                viewModel.loadNoteById(noteId)
            } else {
                viewModel.createNewNote()
            }

            setupRecyclerViews()
            setupListeners()
            setupColorPaletteSelector()
            observeActiveNote()

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.forceSaveActiveNote()
                    finish()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: crash", e)
            Toast.makeText(this, "Error opening note editor", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupRecyclerViews() {
        checklistAdapter = ChecklistAdapter { updatedItems ->
            if (!isUpdatingUi) {
                viewModel.updateActiveNote { it.setChecklist(updatedItems) }
            }
        }
        binding.rvChecklist.layoutManager = LinearLayoutManager(this)
        binding.rvChecklist.adapter = checklistAdapter

        imageAttachmentAdapter = ImageAttachmentAdapter(
            onImageClicked = { path ->
                try {
                    val intent = Intent(this, MediaViewerActivity::class.java).apply {
                        putExtra("mediaUrl", path)
                        putExtra("mediaType", "IMAGE")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening image", e)
                }
            },
            onRemoveClicked = { path ->
                viewModel.updateActiveNote { note ->
                    val list = note.getImages().toMutableList()
                    list.remove(path)
                    note.setImages(list)
                }
            }
        )
        binding.rvImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvImages.adapter = imageAttachmentAdapter

        docAttachmentAdapter = DocAttachmentAdapter(
            onDocClicked = { path -> openDocument(path) },
            onRemoveClicked = { path ->
                viewModel.updateActiveNote { note ->
                    val list = note.getDocuments().toMutableList()
                    list.remove(path)
                    note.setDocuments(list)
                }
            }
        )
        binding.rvDocs.layoutManager = LinearLayoutManager(this)
        binding.rvDocs.adapter = docAttachmentAdapter
    }

    private fun setupListeners() {
        binding.editorToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnAddChecklistItem.setOnClickListener {
            checklistAdapter?.addItem()
        }

        binding.etTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isUpdatingUi) {
                    viewModel.updateActiveNote { it.title = s.toString() }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etDescription.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isUpdatingUi) {
                    viewModel.updateActiveNote { it.description = s.toString() }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnTogglePin.setOnClickListener {
            val isPinned = viewModel.activeNote.value?.isPinned ?: false
            viewModel.updateActiveNote { it.isPinned = !isPinned }
            Toast.makeText(
                this,
                if (!isPinned) "Note pinned to top" else "Note unpinned",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnToggleFavorite.setOnClickListener {
            val isFav = viewModel.activeNote.value?.isFavorite ?: false
            viewModel.updateActiveNote { it.isFavorite = !isFav }
        }

        binding.btnCategorySelect.setOnClickListener { view ->
            showCategoryPopupMenu(view)
        }

        binding.btnDeleteNote.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        binding.btnShareNote.setOnClickListener {
            shareNoteContent()
        }

        binding.btnAttachGallery.setOnClickListener {
            try {
                pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: Exception) {
                Log.e(TAG, "Error launching gallery picker", e)
            }
        }

        binding.btnAttachCamera.setOnClickListener {
            try {
                launchCameraForPhoto()
            } catch (e: Exception) {
                Log.e(TAG, "Error launching camera", e)
            }
        }

        binding.btnAttachDocument.setOnClickListener {
            try {
                DocumentPickDialog.show(
                    this,
                    onDocSelected = { file ->
                        viewModel.updateActiveNote { note ->
                            val list = note.getDocuments().toMutableList()
                            list.add(file.absolutePath)
                            note.setDocuments(list)
                        }
                    },
                    onLaunchSystemPicker = {
                        pickDocumentLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error launching document picker", e)
            }
        }
    }

    private fun showCategoryPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val categories = listOf("Personal", "Work", "Study", "Ideas", "Shopping", "Others")
        categories.forEachIndexed { index, cat ->
            popup.menu.add(0, index, 0, cat)
        }
        popup.setOnMenuItemClickListener { item ->
            val selectedCat = categories[item.itemId]
            viewModel.updateActiveNote { it.category = selectedCat }
            true
        }
        popup.show()
    }

    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Note?")
            .setMessage("Are you sure you want to permanently delete this note?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteActiveNote {
                    Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun shareNoteContent() {
        val note = viewModel.activeNote.value ?: return
        val shareBody = StringBuilder().apply {
            append("📌 ${note.title.ifEmpty { "Untitled Note" }}\n\n")
            if (note.description.isNotEmpty()) {
                append("${note.description}\n\n")
            }
            val checklist = note.getChecklist()
            if (checklist.isNotEmpty()) {
                append("☑️ Checklist:\n")
                checklist.forEach { item ->
                    append(if (item.isChecked) "[✓] " else "[ ] ")
                    append("${item.text}\n")
                }
                append("\n")
            }
            append("Category: ${note.category}\n")
        }.toString()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title.ifEmpty { "Note" })
            putExtra(Intent.EXTRA_TEXT, shareBody)
        }

        val firstImage = note.getImages().firstOrNull()
        if (firstImage != null) {
            val file = File(firstImage)
            if (file.exists()) {
                try {
                    val authority = "$packageName.fileprovider"
                    val uri = FileProvider.getUriForFile(this, authority, file)
                    shareIntent.type = "image/jpeg"
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sharing image", e)
                }
            }
        }

        startActivity(Intent.createChooser(shareIntent, "Share Note"))
    }

    private fun setupColorPaletteSelector() {
        try {
            val paletteContainer = binding.layoutColorPalette
            paletteContainer.removeAllViews()

            NoteColor.COLORS.forEach { colorOption ->
                val density = resources.displayMetrics.density
                val size = (36 * density).toInt()
                val layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    marginStart = 8
                    marginEnd = 8
                }

                val frame = FrameLayout(this).apply {
                    this.layoutParams = layoutParams
                    setOnClickListener {
                        viewModel.updateActiveNote { it.colorName = colorOption.name }
                    }
                }

                val dot = View(this).apply {
                    val drawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(colorOption.displayColor))
                        setStroke(2, Color.WHITE)
                    }
                    background = drawable
                    this.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                frame.addView(dot)

                val checkMark = ImageView(this).apply {
                    setImageResource(R.drawable.ic_single_tick)
                    setColorFilter(Color.WHITE)
                    visibility = View.GONE
                    tag = colorOption.name
                    this.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                frame.addView(checkMark)

                paletteContainer.addView(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupColorPaletteSelector: error", e)
        }
    }

    private fun observeActiveNote() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeNote.collectLatest { note ->
                    if (note == null) return@collectLatest
                    try {
                        isUpdatingUi = true

                        if (binding.etTitle.text.toString() != note.title) {
                            binding.etTitle.setText(note.title)
                        }
                        if (binding.etDescription.text.toString() != note.description) {
                            binding.etDescription.setText(note.description)
                        }

                        val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        binding.tvModifiedDateTime.text = "Last modified: ${formatter.format(Date(note.modifiedTime))}"

                        binding.btnTogglePin.imageAlpha = if (note.isPinned) 255 else 100

                        val favIcon = if (note.isFavorite) {
                            android.R.drawable.btn_star_big_on
                        } else {
                            android.R.drawable.btn_star_big_off
                        }
                        binding.btnToggleFavorite.setImageResource(favIcon)

                        binding.tvCategoryLabel.text = "Category: ${note.category}"

                        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        val noteColorObj = NoteColor.getColorByName(note.colorName)
                        val colorVal = noteColorObj.getBackgroundColor(isDark)
                        binding.editorRoot.setBackgroundColor(colorVal)

                        // Update color palette selection
                        for (i in 0 until binding.layoutColorPalette.childCount) {
                            val child = binding.layoutColorPalette.getChildAt(i)
                            if (child is FrameLayout && child.childCount >= 2) {
                                val tick = child.getChildAt(1)
                                if (tick is ImageView) {
                                    tick.visibility = if (tick.tag == note.colorName) View.VISIBLE else View.GONE
                                }
                            }
                        }

                        checklistAdapter?.submitList(note.getChecklist())

                        val images = note.getImages()
                        binding.tvImagesHeader.visibility = if (images.isNotEmpty()) View.VISIBLE else View.GONE
                        binding.rvImages.visibility = if (images.isNotEmpty()) View.VISIBLE else View.GONE
                        imageAttachmentAdapter?.submitList(images)

                        val docs = note.getDocuments()
                        binding.tvDocsHeader.visibility = if (docs.isNotEmpty()) View.VISIBLE else View.GONE
                        binding.rvDocs.visibility = if (docs.isNotEmpty()) View.VISIBLE else View.GONE
                        docAttachmentAdapter?.submitList(docs)

                        isUpdatingUi = false
                    } catch (e: Exception) {
                        Log.e(TAG, "observeActiveNote: error updating UI", e)
                        isUpdatingUi = false
                    }
                }
            }
        }
    }

    private fun launchCameraForPhoto() {
        val tempFile = File(cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val authority = "$packageName.fileprovider"
        tempCameraUri = FileProvider.getUriForFile(this, authority, tempFile)
        tempCameraUri?.let { uri ->
            capturePhotoLauncher.launch(uri)
        }
    }

    private fun saveImageToPersistentStorage(uri: Uri): String? {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val folder = File(filesDir, "note_images")
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "img_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveImageToPersistentStorage: error", e)
        }
        return null
    }

    private fun saveDocToPersistentStorage(uri: Uri): String? {
        try {
            val name = getFileNameFromUri(uri) ?: "doc_${System.currentTimeMillis()}.pdf"
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val folder = File(filesDir, "note_documents")
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, name)
            file.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveDocToPersistentStorage: error", e)
        }
        return null
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun openDocument(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Document file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val mime = if (path.lowercase().endsWith(".pdf")) "application/pdf" else "image/jpeg"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to open this document", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.forceSaveActiveNote()
    }
}
