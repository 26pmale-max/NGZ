package com.homelab.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homelab.calendar.databinding.ActivityMainBinding
import com.homelab.calendar.databinding.ItemCalendarDayBinding
import com.homelab.calendar.databinding.ItemTaskBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiService: ApiService
    private var tasks = mutableListOf<Task>()
    
    // Calendar state
    private val calendarInstance = Calendar.getInstance()
    private var selectedDate = Calendar.getInstance()
    private val today = Calendar.getInstance()

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
                val response = apiService.getTasks()
                if (response.isSuccessful && response.body() != null) {
                    tasks.clear()
                    tasks.addAll(response.body()!!)
                    updateTaskListUI()
                    updateCalendarGrid()
                    binding.statusText.text = "Online"
                    binding.statusText.setBackgroundResource(R.drawable.glass_badge_green)
                    binding.statusText.setTextColor(android.graphics.Color.parseColor("#34d399"))
                } else {
                    showError("Failed to load tasks")
                }
            } catch (e: Exception) {
                showError("Server unreachable: ${e.localizedMessage}")
                binding.statusText.text = "Offline"
                binding.statusText.setBackgroundResource(0)
                binding.statusText.setTextColor(android.graphics.Color.parseColor("#f87171"))
            }
        }
    }

    private fun updateTaskListUI() {
        // Sort tasks by date and time
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
            try {
                val response = apiService.addTask(body)
                if (response.isSuccessful) {
                    binding.inputDesc.setText("")
                    loadData()
                    Toast.makeText(this@MainActivity, "Task added!", Toast.LENGTH_SHORT).show()
                } else {
                    showError("Failed to add task")
                }
            } catch (e: Exception) {
                showError("Connection failed")
            }
        }
    }

    private fun toggleTask(taskId: Int) {
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
        
        // Find start of month
        val cal = calendarInstance.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0-indexed Sun=0
        
        // Go back to the start of the week grid
        cal.add(Calendar.DAY_OF_MONTH, -firstDayOfWeek)

        // Generate 42 grid cells
        for (i in 0 until 42) {
            val cellDate = cal.clone() as Calendar
            val formattedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cellDate.time)
            
            // Check if there are tasks on this day
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

    // --- Data Model for Calendar Days ---
    data class CalendarDay(
        val dayNum: Int,
        val isCurrentMonth: Boolean,
        val isToday: Boolean,
        val hasTasks: Boolean
    )

    // --- RecyclerView Adapters ---
    
    // 1. Calendar Day Adapter
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
            
            // Day text opacity based on month
            holder.binding.txtDayNumber.alpha = if (day.isCurrentMonth) 1.0f else 0.3f
            
            // Highlight today
            if (day.isToday) {
                holder.binding.txtDayNumber.setTextColor(android.graphics.Color.parseColor("#38bdf8"))
                holder.binding.txtDayNumber.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                holder.binding.txtDayNumber.setTextColor(android.graphics.Color.parseColor("#f8fafc"))
                holder.binding.txtDayNumber.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            // Dot Indicator
            holder.binding.dotIndicator.visibility = if (day.hasTasks) View.VISIBLE else View.INVISIBLE
        }

        override fun getItemCount() = daysList.size
    }

    // 2. Task RecyclerView Adapter
    inner class TaskAdapter(private val taskList: List<Task>) :
        RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

        inner class TaskViewHolder(val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val b = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TaskViewHolder(b)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val task = taskList[position]
            holder.binding.txtDescription.text = task.description
            holder.binding.txtDateTime.text = "${task.date} @ ${task.time}"
            holder.binding.cbCompleted.isChecked = task.completed

            // Strike-through if completed
            if (task.completed) {
                holder.binding.txtDescription.paintFlags = holder.binding.txtDescription.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.binding.txtDescription.alpha = 0.5f
            } else {
                holder.binding.txtDescription.paintFlags = holder.binding.txtDescription.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.binding.txtDescription.alpha = 1.0f
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
