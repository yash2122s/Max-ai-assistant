package com.example.automation.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionResult
import com.example.automation.engine.TaskContext
import com.example.automation.engine.TaskSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class TaskReceiver : BroadcastReceiver() {
    private val TAG = "TaskReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_RUN_TASK) {
            val taskId = intent.getStringExtra("TASK_ID") ?: return
            Log.d(TAG, "Alarm triggered for task: $taskId")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val taskManager = TaskManager(context)
                    val repository = SchedulerRepository(context)
                    val task = taskManager.getTaskById(taskId)
                    if (task != null && task.status == TaskStatus.PENDING) {
                        Log.d(TAG, "Executing scheduled task: ${task.toolName} with arguments: ${task.arguments.json}")
                        
                        // Set task status to RUNNING
                        val runningTask = task.copy(status = TaskStatus.RUNNING, updatedAt = System.currentTimeMillis())
                        repository.updateTaskStatus(runningTask)

                        val argsObj = JSONObject(task.arguments.json)
                        val taskCtx = TaskContext(
                            source = TaskSource.SCHEDULER,
                            taskId = task.id,
                            scheduled = true,
                            createdAt = task.createdAt
                        )

                        // Run through ExecutionEngine
                        val request = com.example.automation.engine.ExecutionRequest(
                            action = when (task.toolName) {
                                "flashlight" -> "FLASHLIGHT_ON"
                                "send_whatsapp_message" -> "SEND_WHATSAPP"
                                "open_app" -> "OPEN_APP"
                                "schedule_task" -> "SCHEDULE_TASK"
                                "cancel_task" -> "CANCEL_TASK"
                                "list_scheduled_tasks" -> "LIST_TASKS"
                                else -> task.toolName.uppercase()
                            },
                            arguments = com.google.gson.JsonParser.parseString(task.arguments.json).asJsonObject,
                            source = com.example.automation.engine.ExecutionSource.SCHEDULER
                        )
                        val toolResult = ExecutionEngine.execute(context.applicationContext, request)
                        Log.d(TAG, "ExecutionEngine result: $toolResult")

                        val result = ExecutionResult(
                            success = toolResult.success,
                            output = toolResult.metadata,
                            error = toolResult.message,
                            duration = toolResult.metrics?.totalDurationMs ?: 0L
                        )

                        // TaskManager handles completion, repeating, retries, and logs
                        taskManager.handleExecutionResult(task, result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing task: $taskId", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
