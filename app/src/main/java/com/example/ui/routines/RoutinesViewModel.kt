package com.example.ui.routines

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.tools.RoutineTool
import com.example.automation.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class RoutinesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RoutinesUiState(routines = loadAllRoutines()))
    val uiState: StateFlow<RoutinesUiState> = _uiState.asStateFlow()

    private val _events = Channel<RoutinesUiEvent>(Channel.BUFFERED)
    val events: Flow<RoutinesUiEvent> = _events.receiveAsFlow()

    fun refreshRoutines() {
        _uiState.update { it.copy(routines = loadAllRoutines()) }
    }

    private fun loadAllRoutines(): List<RoutineItemModel> {
        val list = mutableListOf<RoutineItemModel>()
        val context = getApplication<Application>()

        // 1. Built-in
        list.add(
            RoutineItemModel(
                name = "sleep",
                isBuiltIn = true,
                steps = listOf(
                    RoutineStepModel("SET_DND", "Enable Do Not Disturb"),
                    RoutineStepModel("SET_BRIGHTNESS", "Set screen brightness to 0%"),
                    RoutineStepModel("SET_BLUETOOTH", "Turn off Bluetooth")
                )
            )
        )
        list.add(
            RoutineItemModel(
                name = "morning",
                isBuiltIn = true,
                steps = listOf(
                    RoutineStepModel("SET_DND", "Disable Do Not Disturb"),
                    RoutineStepModel("SET_BRIGHTNESS", "Set screen brightness to 60%"),
                    RoutineStepModel("GET_DEVICE_STATUS", "Read device status & battery")
                )
            )
        )
        list.add(
            RoutineItemModel(
                name = "work",
                isBuiltIn = true,
                steps = listOf(
                    RoutineStepModel("SET_DND", "Enable Do Not Disturb"),
                    RoutineStepModel("SET_VOLUME", "Set media volume to 0%")
                )
            )
        )
        list.add(
            RoutineItemModel(
                name = "gaming",
                isBuiltIn = true,
                steps = listOf(
                    RoutineStepModel("SET_DND", "Enable Do Not Disturb"),
                    RoutineStepModel("SET_BRIGHTNESS", "Set screen brightness to 80%"),
                    RoutineStepModel("SET_VOLUME", "Set volume to 50%")
                )
            )
        )

        // 2. Custom from SharedPreferences
        val prefs = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        for ((key, jsonValue) in prefs.all) {
            try {
                val array = JSONArray(jsonValue.toString())
                val steps = mutableListOf<RoutineStepModel>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val action = obj.optString("action", "ACTION")
                    steps.add(RoutineStepModel(action, "Execute $action"))
                }
                list.add(RoutineItemModel(name = key, isBuiltIn = false, steps = steps))
            } catch (e: Exception) {
                // Ignore corrupted item
            }
        }

        return list
    }

    fun runRoutine(routineName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExecuting = true, currentlyRunningRoutine = routineName) }
            try {
                val routineTool = ToolRegistry.getToolByName("routines") ?: RoutineTool()
                val args = com.google.gson.JsonObject().apply {
                    addProperty("routineName", routineName)
                    addProperty("dryRun", false)
                }
                val request = ExecutionRequest(
                    action = "RUN_ROUTINE",
                    arguments = args,
                    source = ExecutionSource.MANUAL
                )
                val result = routineTool.execute(getApplication(), request)
                _uiState.update {
                    it.copy(
                        isExecuting = false,
                        currentlyRunningRoutine = null,
                        lastExecutionLog = result.message
                    )
                }
                _events.send(RoutinesUiEvent.ShowToast(result.message ?: "Routine executed"))
            } catch (e: Exception) {
                _uiState.update { it.copy(isExecuting = false, currentlyRunningRoutine = null) }
                _events.send(RoutinesUiEvent.ShowToast("Execution failed: ${e.localizedMessage}"))
            }
        }
    }

    fun createCustomRoutine(name: String, stepsJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val routineTool = ToolRegistry.getToolByName("routines") ?: RoutineTool()
                val args = com.google.gson.JsonObject().apply {
                    addProperty("routineName", name)
                    addProperty("steps_json", stepsJson)
                }
                val request = ExecutionRequest(
                    action = "CREATE_ROUTINE",
                    arguments = args,
                    source = ExecutionSource.MANUAL
                )
                val result = routineTool.execute(getApplication(), request)
                if (result.success) {
                    refreshRoutines()
                    _uiState.update { it.copy(isCreatingNew = false) }
                }
                _events.send(RoutinesUiEvent.ShowToast(result.message ?: "Routine created"))
            } catch (e: Exception) {
                _events.send(RoutinesUiEvent.ShowToast("Failed: ${e.localizedMessage}"))
            }
        }
    }

    fun deleteRoutine(routineName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val routineTool = ToolRegistry.getToolByName("routines") ?: RoutineTool()
                val args = com.google.gson.JsonObject().apply {
                    addProperty("routineName", routineName)
                }
                val request = ExecutionRequest(
                    action = "DELETE_ROUTINE",
                    arguments = args,
                    source = ExecutionSource.MANUAL
                )
                val result = routineTool.execute(getApplication(), request)
                if (result.success) {
                    refreshRoutines()
                }
                _events.send(RoutinesUiEvent.ShowToast(result.message ?: "Routine deleted"))
            } catch (e: Exception) {
                _events.send(RoutinesUiEvent.ShowToast("Failed: ${e.localizedMessage}"))
            }
        }
    }

    fun startCreate() {
        _uiState.update { it.copy(isCreatingNew = true) }
    }

    fun dismissCreate() {
        _uiState.update { it.copy(isCreatingNew = false) }
    }
}
