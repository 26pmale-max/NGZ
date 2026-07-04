package com.homelab.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Lightweight background WorkManager worker that syncs pending offline tasks.
 * Tries local server first (fast 2s timeout), falls back to GitHub API.
 * Minimal RAM: no Activity references, no Retrofit, just raw OkHttp.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val PREFS_NAME = "calendar_prefs"
        private const val KEY_PENDING_TASKS = "pending_tasks"
        private const val GITHUB_PAT_KEY = "github_pat"
        private const val SYNC_CHANNEL_ID = "SYNC_CHANNEL_ID"

        private const val LOCAL_BASE = "http://192.168.0.10:8085"
        private const val GITHUB_RAW_URL = "https://raw.githubusercontent.com/26pmale-max/NGZ/main/tasks.enc"
        private const val GITHUB_API_URL = "https://api.github.com/repos/26pmale-max/NGZ/contents/tasks.enc"
    }

    // Lightweight OkHttp client with aggressive timeouts
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val pending = getPendingTasks()
        if (pending.isEmpty()) return Result.success()

        Log.d(TAG, "SyncWorker: ${pending.size} pending tasks to sync")

        // Strategy 1: Try local homelab server
        val localSynced = tryLocalSync(pending)
        if (localSynced) {
            Log.d(TAG, "SyncWorker: All tasks synced via local server")
            showSyncNotification(pending.size, "homelab")
            return Result.success()
        }

        // Strategy 2: Try GitHub API (merge pending into cloud database)
        val cloudSynced = tryCloudSync(pending)
        if (cloudSynced) {
            Log.d(TAG, "SyncWorker: All tasks synced via GitHub cloud")
            showSyncNotification(pending.size, "cloud")
            return Result.success()
        }

        // Neither worked, retry later
        Log.d(TAG, "SyncWorker: Both sync methods failed, will retry")
        return Result.retry()
    }

    private fun tryLocalSync(pending: List<Task>): Boolean {
        val remaining = pending.toMutableList()
        for (task in pending) {
            try {
                val json = JSONObject().apply {
                    put("description", task.description)
                    put("date", task.date)
                    put("time", task.time)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$LOCAL_BASE/api/tasks")
                    .post(body)
                    .build()

                val response = localClient.newCall(request).execute()
                if (response.isSuccessful) {
                    remaining.remove(task)
                }
                response.close()
            } catch (e: Exception) {
                Log.d(TAG, "Local sync failed for task: ${e.message}")
                return false // Local server unreachable, stop trying
            }
        }
        savePendingTasks(remaining)
        return remaining.isEmpty()
    }

    private fun tryCloudSync(pending: List<Task>): Boolean {
        val pat = getGitHubPat() ?: return false

        try {
            // 1. Fetch current encrypted database & SHA from GitHub API (never cached by CDN!)
            val existingTasks = mutableListOf<Task>()
            var sha: String? = null
            try {
                val getReq = Request.Builder()
                    .url(GITHUB_API_URL)
                    .get()
                    .addHeader("Authorization", "Bearer $pat")
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()
                val getRes = cloudClient.newCall(getReq).execute()
                val getBody = getRes.body?.string()
                getRes.close()
                if (getBody != null && getRes.isSuccessful) {
                    val json = JSONObject(getBody)
                    if (json.has("sha")) sha = json.getString("sha")
                    val base64Content = json.optString("content", "").replace("\n", "").replace("\r", "")
                    if (base64Content.isNotEmpty()) {
                        val encData = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        val decryptedJson = CryptoHelper.decrypt(encData)
                        val taskType = object : TypeToken<List<Task>>() {}.type
                        val list: List<Task>? = Gson().fromJson(decryptedJson, taskType)
                        if (list != null) existingTasks.addAll(list)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not fetch existing cloud data: ${e.message}")
                // Continue with empty list — first push
            }

            // 2. Merge pending tasks (assign new IDs)
            var maxId = existingTasks.maxOfOrNull { it.id } ?: 0
            for (task in pending) {
                maxId++
                existingTasks.add(Task(maxId, task.description, task.date, task.time, task.completed))
            }

            // 3. Encrypt merged database
            val mergedJson = Gson().toJson(existingTasks)
            val encryptedData = CryptoHelper.encrypt(mergedJson)

            // 5. Push via GitHub Contents API
            val pushJson = JSONObject().apply {
                put("message", "Sync ${pending.size} task(s) from mobile")
                put("content", android.util.Base64.encodeToString(
                    encryptedData.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                if (sha != null) put("sha", sha)
            }

            val body = pushJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .put(body)
                .addHeader("Authorization", "Bearer $pat")
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            val response = cloudClient.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.e(TAG, "GitHub push failed: ${response.code} ${response.body?.string()}")
            }
            response.close()

            if (success) {
                savePendingTasks(emptyList())
            }
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync exception: ${e.message}")
            return false
        }
    }

    private fun getGitHubFileSha(pat: String): String? {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .get()
                .addHeader("Authorization", "Bearer $pat")
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            val response = cloudClient.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()
            if (responseBody != null) {
                val json = JSONObject(responseBody)
                return if (json.has("sha")) json.getString("sha") else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not fetch SHA: ${e.message}")
        }
        return null
    }

    // --- SharedPreferences (lightweight, no Activity needed) ---
    private fun getPendingTasks(): List<Task> {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_TASKS, null) ?: return emptyList()
        val type = object : TypeToken<List<Task>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun savePendingTasks(list: List<Task>) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_TASKS, Gson().toJson(list)).apply()
    }

    private fun getGitHubPat(): String? {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(GITHUB_PAT_KEY, null)
    }

    // --- Notification ---
    private fun showSyncNotification(count: Int, via: String) {
        ensureNotificationChannel()
        val message = "Synced $count task${if (count > 1) "s" else ""} via $via"
        val builder = NotificationCompat.Builder(applicationContext, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("Homelab Sync Complete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            val nm = NotificationManagerCompat.from(applicationContext)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                nm.notify(2001, builder.build())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYNC_CHANNEL_ID, "Calendar Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Background sync notifications" }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
