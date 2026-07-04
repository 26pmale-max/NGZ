package com.homelab.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.homelab.calendar.databinding.ActivityMainBinding
import com.homelab.calendar.databinding.ItemCalendarDayBinding
import com.homelab.calendar.databinding.ItemTaskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiService: ApiService
    private lateinit var okHttpClient: OkHttpClient
    private var tasks = mutableListOf<Task>()
    private var isLocalMode = false

    // Calendar state
    private val calendarInstance = Calendar.getInstance()
    private var selectedDate = Calendar.getInstance()
    private val today = Calendar.getInstance()

    // SharedPreferences
    private val PREFS_NAME = "calendar_prefs"
    private val KEY_PENDING_TASKS = "pending_tasks"
    private val GITHUB_PAT_KEY = "github_pat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 120Hz refresh rate on compatible screens
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay?.let { display ->
                    val modes = display.supportedModes
                    val highRefreshMode = modes.filter { it.refreshRate >= 119.0f }
                        .maxByOrNull { it.refreshRate }
                    highRefreshMode?.let { mode ->
                        val lp = window.attributes
                        lp.preferredDisplayModeId = mode.modeId
                        window.attributes = lp
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // Request notification permission for Android 13+ (S24)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        createNotificationChannel()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OkHttp client with 1s connect timeout → instant failover to cloud
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://192.168.0.10:8085/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiService = retrofit.create(ApiService::class.java)

        setupUI()

        // Prompt for GitHub PAT on first launch if not set
        checkGitHubPatSetup()

        loadData()

        // Schedule background WorkManager sync (every 15 min when network available)
        scheduleBackgroundSync()
    }

    override fun onResume() {
        super.onResume()
        // Re-check for pending tasks to sync whenever app comes to foreground
        val pending = getPendingTasks()
        if (pending.isNotEmpty()) {
            triggerImmediateSync()
        }
    }

    private fun setupUI() {
        updateDateButtonText()
        updateTimeButtonText()

        binding.btnRefresh.setOnClickListener {
            loadData()
            // Also trigger immediate sync of pending tasks
            triggerImmediateSync()
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }

        binding.btnPickDate.setOnClickListener {
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                selectedDate.set(Calendar.YEAR, year)
                selectedDate.set(Calendar.MONTH, month)
                selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateButtonText()
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(this, { _, hourOfDay, minute ->
                selectedDate.set(Calendar.HOUR_OF_DAY, hourOfDay)
                selectedDate.set(Calendar.MINUTE, minute)
                updateTimeButtonText()
            }, selectedDate.get(Calendar.HOUR_OF_DAY), selectedDate.get(Calendar.MINUTE), true).show()
        }

        binding.btnSubmit.setOnClickListener { submitTask() }

        binding.btnPrevMonth.setOnClickListener {
            calendarInstance.add(Calendar.MONTH, -1)
            updateCalendarGrid()
        }
        binding.btnNextMonth.setOnClickListener {
            calendarInstance.add(Calendar.MONTH, 1)
            updateCalendarGrid()
        }

        binding.taskRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.calendarGrid.layoutManager = GridLayoutManager(this, 7)
        updateCalendarGrid()
    }

    // ─── GitHub PAT Setup ────────────────────────────────────────────

    private fun checkGitHubPatSetup() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pat = prefs.getString(GITHUB_PAT_KEY, null)
        if (pat.isNullOrEmpty()) {
            // Show dialog to enter PAT
            val input = android.widget.EditText(this).apply {
                hint = "ghp_xxxxxxxxxxxxxxxxxxxx"
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle("GitHub Cloud Sync Setup")
                .setMessage("Enter your GitHub Personal Access Token to enable cloud writing when away from home.\n\nCreate one at: github.com → Settings → Developer settings → Personal access tokens → Fine-grained → repo:NGZ → Contents: Read and write")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val token = input.text.toString().trim()
                    if (token.isNotEmpty()) {
                        prefs.edit().putString(GITHUB_PAT_KEY, token).apply()
                        Toast.makeText(this, "PAT saved! Cloud sync enabled.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    // ─── WorkManager Background Sync ─────────────────────────────────

    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSync = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "calendar_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSync
        )
    }

    private fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeSync = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(oneTimeSync)
    }

    // ─── Network & Wifi Helper ───────────────────────────────────────

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val netInfo = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return netInfo?.type == ConnectivityManager.TYPE_WIFI || netInfo?.type == ConnectivityManager.TYPE_ETHERNET
        }
    }

    // ─── Data Loading ────────────────────────────────────────────────

    private fun loadData() {
        if (!isWifiConnected()) {
            isLocalMode = false
            tryFallbackCloud()
            return
        }
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.getTasks() }
                if (response.isSuccessful && response.body() != null) {
                    isLocalMode = true
                    tasks.clear()
                    tasks.addAll(response.body()!!)

                    // Show pending offline tasks too
                    val pending = getPendingTasks()
                    tasks.addAll(pending)

                    updateTaskListUI()
                    updateCalendarGrid()
                    binding.statusText.text = "Local"
                    binding.statusText.setBackgroundResource(R.drawable.glass_badge_green)
                    binding.statusText.setTextColor(android.graphics.Color.parseColor("#34d399"))

                    // Auto-sync pending if local is available
                    if (pending.isNotEmpty()) {
                        syncPendingViaLocal(pending)
                    }
                } else {
                    isLocalMode = false
                    tryFallbackCloud()
                }
            } catch (e: Exception) {
                isLocalMode = false
                tryFallbackCloud(e.localizedMessage)
            }
        }
    }

    private fun tryFallbackCloud(errorMessage: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://raw.githubusercontent.com/26pmale-max/NGZ/main/tasks.enc"
                val encData = URL(url).readText().trim()
                if (encData.isNotEmpty()) {
                    val decryptedJson = CryptoHelper.decrypt(encData)
                    val taskType = object : TypeToken<List<Task>>() {}.type
                    val cloudTasks = Gson().fromJson<List<Task>>(decryptedJson, taskType)

                    launch(Dispatchers.Main) {
                        tasks.clear()
                        tasks.addAll(cloudTasks)
                        tasks.addAll(getPendingTasks())
                        updateTaskListUI()
                        updateCalendarGrid()
                        binding.statusText.text = "Cloud (Encrypted)"
                        binding.statusText.setBackgroundResource(R.drawable.glass_badge_orange)
                        binding.statusText.setTextColor(android.graphics.Color.parseColor("#fbbf24"))
                    }
                } else {
                    launch(Dispatchers.Main) { showOffline(errorMessage) }
                }
            } catch (ce: Exception) {
                launch(Dispatchers.Main) { showOffline(errorMessage ?: ce.localizedMessage) }
            }
        }
    }

    private fun showOffline(reason: String?) {
        tasks.clear()
        tasks.addAll(getPendingTasks())
        updateTaskListUI()
        updateCalendarGrid()
        binding.statusText.text = "Offline"
        binding.statusText.setBackgroundResource(0)
        binding.statusText.setTextColor(android.graphics.Color.parseColor("#f87171"))
    }

    // ─── Task Submission ─────────────────────────────────────────────

    private fun submitTask() {
        val description = binding.inputDesc.text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a task description", Toast.LENGTH_SHORT).show()
            return
        }

        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateStr = dateSdf.format(selectedDate.time)
        val timeStr = timeSdf.format(selectedDate.time)

        val body = mapOf("description" to description, "date" to dateStr, "time" to timeStr)

        if (!isWifiConnected() || !isLocalMode) {
            cloudAddTask(description, dateStr, timeStr)
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.addTask(body) }
                if (response.isSuccessful) {
                    binding.inputDesc.setText("")
                    loadData()
                    Toast.makeText(this@MainActivity, "Task added!", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            } catch (_: Exception) { }
            // Fallback: cloud add
            cloudAddTask(description, dateStr, timeStr)
        }
    }

    private fun saveOffline(description: String, date: String, time: String) {
        val tempId = -(1000 + Random().nextInt(10000))
        val offlineTask = Task(tempId, description, date, time, false)

        val pending = getPendingTasks().toMutableList()
        pending.add(offlineTask)
        savePendingTasks(pending)

        tasks.add(offlineTask)
        updateTaskListUI()
        updateCalendarGrid()

        binding.inputDesc.setText("")
        Toast.makeText(this, "Saved offline ⏳ Will sync automatically.", Toast.LENGTH_LONG).show()

        // Immediately enqueue a background sync attempt
        triggerImmediateSync()
    }

    private fun syncPendingViaLocal(pendingList: List<Task>) {
        lifecycleScope.launch {
            var allSuccessful = true
            val remaining = pendingList.toMutableList()

            for (task in pendingList) {
                try {
                    val body = mapOf(
                        "description" to task.description,
                        "date" to task.date,
                        "time" to task.time
                    )
                    val response = withContext(Dispatchers.IO) { apiService.addTask(body) }
                    if (response.isSuccessful) {
                        remaining.remove(task)
                    } else {
                        allSuccessful = false
                    }
                } catch (e: Exception) {
                    allSuccessful = false
                    break
                }
            }

            savePendingTasks(remaining)
            if (allSuccessful && pendingList.isNotEmpty()) {
                showSyncNotification(pendingList.size)
                loadData()
            }
        }
    }

    // ─── Task Actions ────────────────────────────────────────────────

    private fun toggleTask(taskId: Int) {
        if (taskId < 0) {
            Toast.makeText(this, "Cannot toggle offline tasks until synced", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isWifiConnected() || !isLocalMode) {
            cloudToggleTask(taskId)
            return
        }
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.toggleTask(taskId) }
                if (response.isSuccessful) loadData()
                else cloudToggleTask(taskId)
            } catch (e: Exception) { cloudToggleTask(taskId) }
        }
    }

    private fun deleteTask(taskId: Int) {
        if (taskId < 0) {
            val pending = getPendingTasks().toMutableList()
            val toRemove = pending.find { it.id == taskId }
            if (toRemove != null) {
                pending.remove(toRemove)
                savePendingTasks(pending)
                tasks.remove(toRemove)
                updateTaskListUI()
                updateCalendarGrid()
                Toast.makeText(this, "Offline task deleted", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!isWifiConnected() || !isLocalMode) {
            cloudDeleteTask(taskId)
            return
        }
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.deleteTask(taskId) }
                if (response.isSuccessful) {
                    loadData()
                    Toast.makeText(this@MainActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                } else cloudDeleteTask(taskId)
            } catch (e: Exception) { cloudDeleteTask(taskId) }
        }
    }

    // ─── Direct Cloud API Modifications ──────────────────────────────

    private fun cloudAddTask(description: String, dateStr: String, timeStr: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pat = prefs.getString(GITHUB_PAT_KEY, null)
        if (pat.isNullOrEmpty()) {
            saveOffline(description, dateStr, timeStr)
            checkGitHubPatSetup()
            return
        }

        Toast.makeText(this, "Pushing to GitHub Cloud ☁️…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (currentTasks, sha) = fetchCloudTasksAndSha(pat)
                val newId = if (currentTasks.isNotEmpty()) currentTasks.maxOf { it.id } + 1 else 1
                val newTask = Task(newId, description, dateStr, timeStr, false)
                currentTasks.add(newTask)

                val success = pushCloudTasks(pat, currentTasks, sha, "Add task from mobile cloud")
                if (success) {
                    launch(Dispatchers.Main) {
                        binding.inputDesc.setText("")
                        loadData()
                        Toast.makeText(this@MainActivity, "Saved to GitHub Cloud ☁️✨", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    launch(Dispatchers.Main) { saveOffline(description, dateStr, timeStr) }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { saveOffline(description, dateStr, timeStr) }
            }
        }
    }

    private fun cloudToggleTask(taskId: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pat = prefs.getString(GITHUB_PAT_KEY, null)
        if (pat.isNullOrEmpty()) {
            Toast.makeText(this, "GitHub PAT required for cloud edit", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Updating GitHub Cloud ☁️…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (currentTasks, sha) = fetchCloudTasksAndSha(pat)
                val target = currentTasks.find { it.id == taskId }
                if (target != null) {
                    target.completed = !target.completed
                    val success = pushCloudTasks(pat, currentTasks, sha, "Toggle task from mobile cloud")
                    if (success) {
                        launch(Dispatchers.Main) { loadData() }
                    } else {
                        launch(Dispatchers.Main) { showError("Cloud update failed") }
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { showError("Cloud connection failed") }
            }
        }
    }

    private fun cloudDeleteTask(taskId: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pat = prefs.getString(GITHUB_PAT_KEY, null)
        if (pat.isNullOrEmpty()) {
            Toast.makeText(this, "GitHub PAT required for cloud delete", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Deleting from GitHub Cloud ☁️…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (currentTasks, sha) = fetchCloudTasksAndSha(pat)
                val initialSize = currentTasks.size
                currentTasks.removeAll { it.id == taskId }
                if (currentTasks.size < initialSize) {
                    val success = pushCloudTasks(pat, currentTasks, sha, "Delete task from mobile cloud")
                    if (success) {
                        launch(Dispatchers.Main) {
                            loadData()
                            Toast.makeText(this@MainActivity, "Deleted from Cloud ☁️", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        launch(Dispatchers.Main) { showError("Cloud delete failed") }
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { showError("Cloud connection failed") }
            }
        }
    }

    private fun fetchCloudTasksAndSha(pat: String): Pair<MutableList<Task>, String?> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/26pmale-max/NGZ/contents/tasks.enc")
            .get()
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        response.close()

        val currentTasks = mutableListOf<Task>()
        var sha: String? = null

        if (body != null && response.isSuccessful) {
            val json = JSONObject(body)
            if (json.has("sha")) sha = json.getString("sha")
            val base64Content = json.optString("content", "").replace("\n", "").replace("\r", "")
            if (base64Content.isNotEmpty()) {
                val encData = String(android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val decJson = CryptoHelper.decrypt(encData)
                val type = object : TypeToken<List<Task>>() {}.type
                val list: List<Task>? = Gson().fromJson(decJson, type)
                if (list != null) currentTasks.addAll(list)
            }
        }
        return Pair(currentTasks, sha)
    }

    private fun pushCloudTasks(pat: String, taskList: List<Task>, sha: String?, message: String): Boolean {
        val plainJson = Gson().toJson(taskList)
        val encryptedData = CryptoHelper.encrypt(plainJson)
        val base64Content = android.util.Base64.encodeToString(
            encryptedData.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )

        val pushJson = JSONObject().apply {
            put("message", message)
            put("content", base64Content)
            if (sha != null) put("sha", sha)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = pushJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.github.com/repos/26pmale-max/NGZ/contents/tasks.enc")
            .put(requestBody)
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val success = response.isSuccessful
        response.close()
        return success
    }

    // ─── SharedPreferences ───────────────────────────────────────────

    private fun getPendingTasks(): List<Task> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_TASKS, null) ?: return emptyList()
        val type = object : TypeToken<List<Task>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun savePendingTasks(list: List<Task>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_TASKS, Gson().toJson(list)).apply()
    }

    // ─── Notifications ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "SYNC_CHANNEL_ID", "Calendar Sync",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Homelab sync notifications" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun showSyncNotification(count: Int) {
        val message = "Synced $count task${if (count > 1) "s" else ""} with your homelab!"
        val builder = NotificationCompat.Builder(this, "SYNC_CHANNEL_ID")
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("Homelab Sync Complete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            val nm = NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                nm.notify(1002, builder.build())
            }
        } catch (e: SecurityException) { e.printStackTrace() }

        Toast.makeText(this, "🔄 $message", Toast.LENGTH_LONG).show()
    }

    // ─── UI Helpers ──────────────────────────────────────────────────

    private fun updateDateButtonText() {
        binding.btnPickDate.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(selectedDate.time)
    }

    private fun updateTimeButtonText() {
        binding.btnPickTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(selectedDate.time)
    }

    private fun updateTaskListUI() {
        binding.taskRecyclerView.adapter = TaskAdapter(tasks.sortedWith(compareBy({ it.date }, { it.time })))
    }

    private fun updateCalendarGrid() {
        binding.txtCurrentMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendarInstance.time)
        val days = mutableListOf<CalendarDay>()
        val cal = calendarInstance.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        cal.add(Calendar.DAY_OF_MONTH, -firstDayOfWeek)

        for (i in 0 until 42) {
            val cellDate = cal.clone() as Calendar
            val formattedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cellDate.time)
            val hasTasks = tasks.any { it.date == formattedDate }
            val isCurrentMonth = cellDate.get(Calendar.MONTH) == calendarInstance.get(Calendar.MONTH)
            val isToday = cellDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    cellDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            days.add(CalendarDay(cellDate.get(Calendar.DAY_OF_MONTH), isCurrentMonth, isToday, hasTasks))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        binding.calendarGrid.adapter = CalendarDayAdapter(days)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ─── Data Classes & Adapters ─────────────────────────────────────

    data class CalendarDay(val dayNum: Int, val isCurrentMonth: Boolean, val isToday: Boolean, val hasTasks: Boolean)

    inner class CalendarDayAdapter(private val daysList: List<CalendarDay>) :
        RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder>() {
        inner class DayViewHolder(val binding: ItemCalendarDayBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            return DayViewHolder(ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            val day = daysList[position]
            holder.binding.txtDayNumber.text = day.dayNum.toString()
            holder.binding.txtDayNumber.alpha = if (day.isCurrentMonth) 1.0f else 0.3f
            if (day.isToday) {
                holder.binding.txtDayNumber.setTextColor(android.graphics.Color.parseColor("#38bdf8"))
                holder.binding.txtDayNumber.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                holder.binding.txtDayNumber.setTextColor(android.graphics.Color.parseColor("#f8fafc"))
                holder.binding.txtDayNumber.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            holder.binding.dotIndicator.visibility = if (day.hasTasks) View.VISIBLE else View.INVISIBLE
        }
        override fun getItemCount() = daysList.size
    }

    inner class TaskAdapter(private val taskList: List<Task>) :
        RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {
        inner class TaskViewHolder(val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            return TaskViewHolder(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val task = taskList[position]
            val isOffline = task.id < 0
            holder.binding.txtDescription.text = if (isOffline) "${task.description} ⏳" else task.description
            holder.binding.txtDateTime.text = "${task.date} @ ${task.time}"
            holder.binding.cbCompleted.isChecked = task.completed

            if (task.completed) {
                holder.binding.txtDescription.paintFlags = holder.binding.txtDescription.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.binding.txtDescription.alpha = 0.5f
            } else {
                holder.binding.txtDescription.paintFlags = holder.binding.txtDescription.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.txtDescription.alpha = 1.0f
            }

            if (isOffline) {
                holder.binding.txtDescription.setTypeface(null, android.graphics.Typeface.ITALIC)
                holder.binding.txtDescription.setTextColor(android.graphics.Color.parseColor("#fbbf24"))
            } else {
                holder.binding.txtDescription.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.binding.txtDescription.setTextColor(android.graphics.Color.parseColor("#f8fafc"))
            }

            holder.binding.cbCompleted.setOnClickListener { toggleTask(task.id) }
            holder.binding.btnDelete.setOnClickListener { deleteTask(task.id) }
        }
        override fun getItemCount() = taskList.size
    }
}
