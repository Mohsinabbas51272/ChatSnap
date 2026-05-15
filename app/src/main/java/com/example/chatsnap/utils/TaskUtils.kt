package com.example.chatsnap.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

object TaskUtils {
    fun markTaskAsDone(taskId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        
        // taskId map: 
        // TASK_STORY -> lastTask2Date
        // TASK_MESSAGE -> lastTask3Date
        // TASK_CALL -> lastTask4Date
        
        val field = when(taskId) {
            "TASK_STORY" -> "pendingTask2Date"
            "TASK_MESSAGE" -> "pendingTask3Date"
            "TASK_CALL" -> "pendingTask4Date"
            else -> return
        }

        db.collection("users").document(uid)
            .collection("wallet").document("data")
            .set(mapOf(field to today), SetOptions.merge())
    }
}
