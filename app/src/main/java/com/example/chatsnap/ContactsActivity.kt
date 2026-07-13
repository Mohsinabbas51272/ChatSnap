package com.example.chatsnap

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.chatsnap.databinding.ActivityContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ContactsActivity : BaseActivity() {

    private lateinit var binding: ActivityContactsBinding

    private val requestReadContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadContactCount()
        } else {
            Toast.makeText(this, "Permission denied. Cannot export contacts.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestWriteContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pickVcfFile()
        } else {
            Toast.makeText(this, "Permission denied. Cannot import contacts.", Toast.LENGTH_SHORT).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importVcf(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnExport.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                exportContacts()
            } else {
                requestReadContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        binding.btnImport.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                pickVcfFile()
            } else {
                requestWriteContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadContactCount()
        }
    }

    private fun loadContactCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                var total = 0
                val cursor = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    null, null, null, null
                )
                cursor?.use {
                    total = it.count
                }
                total
            }
            binding.tvExportCount.text = "$count contacts found on device."
        }
    }

    private fun exportContacts() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val vcfContent = withContext(Dispatchers.IO) {
                    val sb = java.lang.StringBuilder()
                    val cursor = contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        null, null, null, null
                    )
                    cursor?.use {
                        val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        while (it.moveToNext()) {
                            val contactId = it.getString(idIndex)
                            val name = it.getString(nameIndex) ?: "Unnamed"

                            val phoneCursor = contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                arrayOf(contactId),
                                null
                            )
                            var phone: String? = null
                            phoneCursor?.use { pc ->
                                if (pc.moveToFirst()) {
                                    val numberIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    if (numberIdx != -1) phone = pc.getString(numberIdx)
                                }
                            }

                            sb.append("BEGIN:VCARD\n")
                            sb.append("VERSION:3.0\n")
                            sb.append("FN:$name\n")
                            if (phone != null) {
                                sb.append("TEL;TYPE=CELL:$phone\n")
                            }
                            sb.append("END:VCARD\n")
                        }
                    }
                    sb.toString()
                }

                if (vcfContent.isNotEmpty()) {
                    val docsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                    val file = File(docsDir, "contacts_export.vcf")
                    file.parentFile?.mkdirs()
                    file.writeText(vcfContent)
                    val uri = FileProvider.getUriForFile(this@ContactsActivity, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/x-vcard"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Contacts VCF"))
                } else {
                    Toast.makeText(this@ContactsActivity, "No contacts found to export", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ContactsActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun pickVcfFile() {
        filePickerLauncher.launch("text/x-vcard")
    }

    private fun importVcf(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvImportStatus.text = "Importing contacts..."
        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    val list = mutableListOf<Pair<String, String>>()
                    val inputStream = contentResolver.openInputStream(uri)
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String?
                        var currentName = ""
                        var currentPhone = ""
                        while (reader.readLine().also { line = it } != null) {
                            val l = line!!
                            if (l.startsWith("BEGIN:VCARD")) {
                                currentName = ""
                                currentPhone = ""
                            } else if (l.startsWith("FN:")) {
                                currentName = l.substring(3).trim()
                            } else if (l.startsWith("TEL;")) {
                                val colonIndex = l.indexOf(':')
                                if (colonIndex != -1) {
                                    currentPhone = l.substring(colonIndex + 1).trim()
                                }
                            } else if (l.startsWith("END:VCARD")) {
                                if (currentName.isNotEmpty() && currentPhone.isNotEmpty()) {
                                    list.add(Pair(currentName, currentPhone))
                                }
                            }
                        }
                    }
                    list
                }

                if (contacts.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        for (contact in contacts) {
                            val ops = ArrayList<ContentProviderOperation>()
                            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                .build())

                            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.first)
                                .build())

                            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.second)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build())

                            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                        }
                    }
                    Toast.makeText(this@ContactsActivity, "Successfully imported ${contacts.size} contacts!", Toast.LENGTH_LONG).show()
                    binding.tvImportStatus.text = "Import complete! ${contacts.size} contacts added."
                    loadContactCount()
                } else {
                    Toast.makeText(this@ContactsActivity, "No valid contacts found in file", Toast.LENGTH_SHORT).show()
                    binding.tvImportStatus.text = "Import failed: No valid contacts in VCF."
                }
            } catch (e: Exception) {
                Toast.makeText(this@ContactsActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                binding.tvImportStatus.text = "Import error: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
