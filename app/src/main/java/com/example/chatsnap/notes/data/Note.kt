package com.example.chatsnap.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var title: String = "",
    var description: String = "",
    var category: String = "Others",
    var colorName: String = "Yellow",
    var isPinned: Boolean = false,
    var isFavorite: Boolean = false,
    val createdTime: Long = System.currentTimeMillis(),
    var modifiedTime: Long = System.currentTimeMillis(),
    var checklistJson: String = "[]",
    var imagesJson: String = "[]",
    var documentsJson: String = "[]"
) {
    // Utility helpers for checklists
    fun getChecklist(): List<ChecklistItem> {
        val list = mutableListOf<ChecklistItem>()
        if (checklistJson.isEmpty()) return list
        try {
            val array = JSONArray(checklistJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ChecklistItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        text = obj.optString("text", ""),
                        isChecked = obj.optBoolean("isChecked", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun setChecklist(items: List<ChecklistItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("isChecked", item.isChecked)
            }
            array.put(obj)
        }
        checklistJson = array.toString()
    }

    // Utility helpers for image attachments
    fun getImages(): List<String> {
        val list = mutableListOf<String>()
        if (imagesJson.isEmpty()) return list
        try {
            val array = JSONArray(imagesJson)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun setImages(list: List<String>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item)
        }
        imagesJson = array.toString()
    }

    // Utility helpers for document attachments
    fun getDocuments(): List<String> {
        val list = mutableListOf<String>()
        if (documentsJson.isEmpty()) return list
        try {
            val array = JSONArray(documentsJson)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun setDocuments(list: List<String>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item)
        }
        documentsJson = array.toString()
    }
}
