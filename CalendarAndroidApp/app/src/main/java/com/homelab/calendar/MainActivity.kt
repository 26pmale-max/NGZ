package com.homelab.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.homelab.calendar.databinding.ActivityMainBinding
import com.homelab.calendar.databinding.ItemCalendarDayBinding
import com.homelab.calendar.databinding.ItemTaskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiService: ApiService
    private var tasks = mutableListOf<Task>()
    private var isLocalMode = false
    
    // Calendar state
    private val calendarInstance = Calendar.getInstance()
    private var selectedDate = Calendar.getInstance()
    private val today = Calendar.getInstance()

    // SharedPreferences for Offline Queue
    private val PREFS_NAME = "calendar_prefs"
    private val KEY_PENDING_TASKS = "pending_tasks"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge layout styling for S24 - set to true to avoid overlapping with status/nav bars
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Enforce 120Hz refresh rate on compatible screens (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Request notification permission for Android 13+ (S24)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Create Notification Channel
        createNotificationChannel()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Retrofit linking to local PC server
        val retrofit = Retrofit.Builder()
            .baseUrl("http://192.168.0.10:8085/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        apiService = retrofit.create(ApiService::class.java)

        setupUI()
        loadData()
    }

    private fun setupUI() {
        updateDateButtonText()
        updateTimeButtonText()

        binding.btnRefresh.setOnClickListener {
            loadData()
            Toast.makeText(this, "Refreshed tasks from server", Toast.LENGTH_SHORT).show()
        }

        binding.btnPickDate.setOnClickListener {
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                selectedDate.set(Calendar.YEAR, year)
                selectedDate.set(Calendar.MONTH, month)
                selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateButtonText()
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(this, { _, hourOfDay, minute ->
                selectedDate.set(Calendar.HOUR_OF_DAY, hourOfDay)
                selectedDate.set(Calendar.MINUTE, minute)
                updateTimeButtonText()
            }, selectedDate.get(Calendar.HOUR_OF_DAY), selectedDate.get(Calendar.MINUTE), true).show()
        }

        binding.btnSubmit.setOnClickListener {
            submitTask()
        }

        // Calendar Month view controls
        binding.btnPrevMonth.setOnClickListener {
            calendarInstance.add(Calendar.MONTH, -1)
            updateCalendarGrid()
        }
        binding.btnNextMonth.setOnClickListener {
            calendarInstance.add(Calendar.MONTH, 1)
            updateCalendarGrid()
        }

        // Task List Recycler setup
        binding.taskRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // Calendar Grid setup
        binding.calendarGrid.layoutManager = GridLayoutManager(this, 7)
        updateCalendarGrid()
    }

    private fun updateDateButtonText() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        binding.btnPickDate.text = sdf.format(selectedDate.time)
    }

    private fun updateTimeButtonText() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.btnPickTime.text = sdf.format(selectedDate.time)
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                // 1. Try local server first
                val response = apiService.getTasks()
                if (response.isSuccessful && response.body() != null) {
                    isLocalMode = true
                    tasks.clear()
                    tasks.addAll(response.body()!!)
                    
                    // Show pending offline tasks as well until they are synced
                    val pending = getPendingTasks()
                    tasks.addAll(pending)
                    
                    updateTaskListUI()
                    updateCalendarGrid()
                    binding.statusText.text = "Local"
                    binding.statusText.setBackgroundResource(R.drawable.glass_badge_green)
                    binding.statusText.setTextColor(android.graphics.Color.parseColor("#34d399"))

                    // Automatically trigger upload of offline tasks since connection is restored!
                    if (pending.isNotEmpty()) {
                        syncPendingTasks(pending)
                    }
                } else {
                    isLocalMode = false
                    tryFallbackCloud()
                }
            } catch (e: Exception) {
                // Local server offline/unreachable, fallback to GitHub tasks.enc
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
                        
                        // Append offline pending tasks so they appear in Cloud Mode
                        tasks.addAll(getPendingTasks())
                        
                        updateTaskListUI()
                        updateCalendarGrid()
                        binding.statusText.text = "Cloud (Encrypted)"
                        binding.statusText.setBackgroundResource(R.drawable.glass_badge_orange)
                        binding.statusText.setTextColor(android.graphics.Color.parseColor("#fbbf24"))
                        Toast.makeText(this@MainActivity, "Loaded from Encrypted Cloud", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    launch(Dispatchers.Main) {
                        showOffline(errorMessage)
                    }
                }
            } catch (ce: Exception) {
                launch(Dispatchers.Main) {
                    showOffline(errorMessage ?: ce.localizedMessage)
                }
            }
        }
    }

    private fun showOffline(reason: String?) {
        showError("Unreachable: $reason")
        tasks.clear()
        // Display whatever is queued offline so the user can still see them
        tasks.addAll(getPendingTasks())
        updateTaskListUI()
        updateCalendarGrid()
        
        binding.statusText.text = "Offline"
        binding.statusText.setBackgroundResource(0)
        binding.statusText.setTextColor(android.graphics.Color.parseColor("#f87171"))
    }

    private fun updateTaskListUI() {
        val sortedTasks = tasks.sortedWith(compareBy({ it.date }, { it.time }))
        binding.taskRecyclerView.adapter = TaskAdapter(sortedTasks)
    }

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

        val body = mapOf(
            "description" to description,
            "date" to dateStr,
            "time" to timeStr
        )

        lifecycleScope.launch {
            if (isLocalMode) {
                try {
                    val response = apiService.addTask(body)
                    if (response.isSuccessful) {
                        binding.inputDesc.setText("")
                        loadData()
                        Toast.makeText(this@MainActivity, "Task added!", Toast.LENGTH_SHORT).show()
                    } else {
                        saveOffline(description, dateStr, timeStr)
                    }
                } catch (e: Exception) {
                    saveOffline(description, dateStr, timeStr)
                }
            } else {
                // Not in local mode (Cloud or Offline), save locally and sync later!
                saveOffline(description, dateStr, timeStr)
            }
        }
    }

    private fun saveOffline(description: String, date: String, time: String) {
        val tempId = -(1000 + Random().nextInt(10000))
        val offlineTask = Task(tempId, description, date, time, false)
        
        val pending = getPendingTasks().toMutableList()
        pending.add(offlineTask)
        savePendingTasks(pending)
        
        // Add to active view instantly
        tasks.add(offlineTask)
        updateTaskListUI()
        updateCalendarGrid()
        
        binding.inputDesc.setText("")
        Toast.makeText(this, "Saved offline. Will sync when local connection returns.", Toast.LENGTH_LONG).show()
    }

    private fun syncPendingTasks(pendingList: List<Task>) {
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
                    val response = apiService.addTask(body)
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
            
            if (allSuccessful) {
                showSyncNotification(pendingList.size)
                loadData()
            }
        }
    }

    // --- SharedPreferences Helpers ---
    private fun getPendingTasks(): List<Task> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_TASKS, null) ?: return emptyList()
        val type = object : TypeToken<List<Task>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun savePendingTasks(pendingList: List<Task>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(pendingList)
        prefs.edit().putString(KEY_PENDING_TASKS, json).apply()
    }

    // --- Native Android Notification ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Calendar Sync"
            val descriptionText = "Notifications for automatic homelab synchronization updates"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("SYNC_CHANNEL_ID", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSyncNotification(syncedCount: Int) {
        val message = "Successfully synced $syncedCount task" + (if (syncedCount > 1) "s" else "") + " with your homelab!"
        val builder = NotificationCompat.Builder(this, "SYNC_CHANNEL_ID")
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("Homelab Sync Complete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            val notificationManager = NotificationManagerCompat.from(this)
            // Double check permission on Android 13+
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(1002, builder.build())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        
        Toast.makeText(this, "🔄 $message", Toast.LENGTH_LONG).show()
    }

    private fun toggleTask(taskId: Int) {
        if (taskId < 0) {
            Toast.makeText(this, "Cannot toggle offline tasks until they are synced", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val response = apiService.toggleTask(taskId)
                if (response.isSuccessful) {
                    loadData()
                } else {
                    showError("Failed to update status")
                }
            } catch (e: Exception) {
                showError("Connection failed")
            }
        }
    }

    private fun deleteTask(taskId: Int) {
        if (taskId < 0) {
            // Delete locally queued offline task immediately
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
        lifecycleScope.launch {
            try {
                val response = apiService.deleteTask(taskId)
                if (response.isSuccessful) {
                    loadData()
                    Toast.makeText(this@MainActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                } else {
                    showError("Failed to delete task")
                }
            } catch (e: Exception) {
                showError("Connection failed")
            }
        }
    }

    private fun updateCalendarGrid() {
        val monthSdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.txtCurrentMonth.text = monthSdf.format(calendarInstance.time)

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

    data class CalendarDay(
        val dayNum: Int,
        val isCurrentMonth: Boolean,
        val isToday: Boolean,
        val hasTasks: Boolean
    )

    inner class CalendarDayAdapter(private val daysList: List<CalendarDay>) :
        RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder>() {

        inner class DayViewHolder(val binding: ItemCalendarDayBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val b = ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return DayViewHolder(b)
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
            val b = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TaskViewHolder(b)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val task = taskList[position]
            
            // Differentiate offline tasks visually (add a small label or italic text)
            val isOffline = task.id < 0
            val descText = if (isOffline) "${task.description} ⏳" else task.description
            holder.binding.txtDescription.text = descText
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
                holder.binding.txtDescription.setTextColor(android.graphics.Color.parseColor("#fbbf24")) // Yellow for offline
            } else {
                holder.binding.txtDescription.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.binding.txtDescription.setTextColor(android.graphics.Color.parseColor("#f8fafc"))
            }

            holder.binding.cbCompleted.setOnClickListener {
                toggleTask(task.id)
            }

            holder.binding.btnDelete.setOnClickListener {
                deleteTask(task.id)
            }
        }

        override fun getItemCount() = taskList.size
    }
}
