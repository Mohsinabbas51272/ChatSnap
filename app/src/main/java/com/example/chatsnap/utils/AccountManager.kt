package com.example.chatsnap.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AccountManager {
    private const val PREFS_NAME = "chatsnap_accounts"
    private const val KEY_ACCOUNTS = "saved_accounts"
    private const val KEY_ALIAS = "chatsnap_credential_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"

    data class SavedAccount(
        val uid: String,
        val email: String,
        val name: String,
        val profileImageUrl: String,
        val encryptedPasswordBase64: String,
        val ivBase64: String
    )

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (key != null) return key

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    fun encryptPassword(password: String): Pair<String, String> {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        val ivBase64 = Base64.encodeToString(cipher.iv, Base64.DEFAULT)
        return Pair(encryptedBase64, ivBase64)
    }

    fun decryptPassword(encryptedBase64: String, ivBase64: String): String {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedBytes = cipher.doFinal(Base64.decode(encryptedBase64, Base64.DEFAULT))
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun getAccounts(context: Context): List<SavedAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val list = mutableListOf<SavedAccount>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedAccount(
                        uid = obj.getString("uid"),
                        email = obj.getString("email"),
                        name = obj.getString("name"),
                        profileImageUrl = obj.getString("profileImageUrl"),
                        encryptedPasswordBase64 = obj.getString("encryptedPasswordBase64"),
                        ivBase64 = obj.getString("ivBase64")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveAccount(context: Context, account: SavedAccount) {
        val accounts = getAccounts(context).toMutableList()
        // Remove existing account with same email / uid
        accounts.removeAll { it.uid == account.uid || it.email == account.email }
        // Keep max 3 accounts
        if (accounts.size >= 3) {
            accounts.removeAt(0)
        }
        accounts.add(account)

        saveAccountsList(context, accounts)
    }

    fun removeAccount(context: Context, uid: String) {
        val accounts = getAccounts(context).toMutableList()
        accounts.removeAll { it.uid == uid }
        saveAccountsList(context, accounts)
    }

    private fun saveAccountsList(context: Context, list: List<SavedAccount>) {
        val arr = JSONArray()
        for (acc in list) {
            val obj = JSONObject().apply {
                put("uid", acc.uid)
                put("email", acc.email)
                put("name", acc.name)
                put("profileImageUrl", acc.profileImageUrl)
                put("encryptedPasswordBase64", acc.encryptedPasswordBase64)
                put("ivBase64", acc.ivBase64)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS, arr.toString())
            .apply()
    }
}
