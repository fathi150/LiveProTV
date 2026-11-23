package com.livepro.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.livepro.tv.model.ActivationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DeviceActivationManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("livepro_tv_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrBlank()) return saved

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "TVBOX-${System.currentTimeMillis()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .joinToString("") { "%02X".format(it) }
            .take(6)

        val generated = "LP-$hash"
        prefs.edit().putString("device_id", generated).apply()
        return generated
    }

    fun getActivationApiUrl(): String {
        return prefs.getString(
            "activation_api_url",
            "https://script.google.com/macros/s/YOUR_SCRIPT_ID/exec"
        ) ?: ""
    }

    fun saveActivationApiUrl(url: String) {
        prefs.edit().putString("activation_api_url", url.trim()).apply()
    }

    fun saveManualXtream(host: String, user: String, pass: String) {
        prefs.edit()
            .putBoolean("is_active", true)
            .putString("server_type", "xtream")
            .putString("host", host.trim().removeSuffix("/"))
            .putString("username", user.trim())
            .putString("password", pass.trim())
            .putString("expiry", "مفعل يدوياً")
            .apply()
    }

    fun saveManualM3u(m3uUrl: String) {
        prefs.edit()
            .putBoolean("is_active", true)
            .putString("server_type", "m3u")
            .putString("m3u_url", m3uUrl.trim())
            .putString("expiry", "مفعل يدوياً")
            .apply()
    }

    fun loadSavedStatus(): ActivationStatus {
        return ActivationStatus(
            isActive = prefs.getBoolean("is_active", false),
            deviceId = getDeviceId(),
            expiryDate = prefs.getString("expiry", "غير مفعل") ?: "غير مفعل",
            serverType = prefs.getString("server_type", "xtream") ?: "xtream",
            host = prefs.getString("host", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            m3uUrl = prefs.getString("m3u_url", "") ?: ""
        )
    }

    suspend fun verifyWithServer(apiBaseUrl: String): ActivationStatus = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId()
        saveActivationApiUrl(apiBaseUrl)
        try {
            val separator = if (apiBaseUrl.contains("?")) "&" else "?"
            val url = "$apiBaseUrl${separator}action=check&device_id=$deviceId"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext loadSavedStatus().copy(
                        isActive = false,
                        message = "خطأ في الاتصال بالسيرفر (${response.code})"
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val active = json.optBoolean("active", false) ||
                        json.optString("status").equals("active", ignoreCase = true)

                val expiry = json.optString("expiry", json.optString("expiry_date", "غير محدد"))
                val host = json.optString("host", "").removeSuffix("/")
                val username = json.optString("username", "")
                val password = json.optString("password", "")
                val m3uUrl = json.optString("m3u_url", "")
                val serverType = if (m3uUrl.isNotBlank() && host.isBlank()) "m3u" else "xtream"

                if (active) {
                    prefs.edit()
                        .putBoolean("is_active", true)
                        .putString("expiry", expiry)
                        .putString("server_type", serverType)
                        .putString("host", host)
                        .putString("username", username)
                        .putString("password", password)
                        .putString("m3u_url", m3uUrl)
                        .apply()
                }

                ActivationStatus(
                    isActive = active,
                    deviceId = deviceId,
                    expiryDate = expiry,
                    serverType = serverType,
                    host = host,
                    username = username,
                    password = password,
                    m3uUrl = m3uUrl,
                    message = if (active) "تم التفعيل بنجاح" else json.optString("message", "الجهاز غير مفعل")
                )
            }
        } catch (e: Exception) {
            loadSavedStatus().copy(
                message = "فشل الاتصال: ${e.localizedMessage ?: "خطأ غير معروف"}"
            )
        }
    }
}
