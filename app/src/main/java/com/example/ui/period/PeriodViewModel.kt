package com.example.ui.period

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
import com.example.memory.account.MemoryAccountScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class PeriodViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).periodLogDao()

    private val _isCreatingNew = MutableStateFlow(false)
    private val _selectedLogForEdit = MutableStateFlow<PeriodLog?>(null)

    private val _events = Channel<PeriodUiEvent>(Channel.BUFFERED)
    val events: Flow<PeriodUiEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<PeriodUiState> = combine(
        MemoryAccountScope.sessions.flatMapLatest { session ->
            if (session.ownerKey == MemoryAccountScope.UNAVAILABLE) flowOf(emptyList())
            else dao.getAllPeriodsFlow(session.ownerKey).map { logs ->
                if (MemoryAccountScope.isCurrent(session)) logs else emptyList()
            }.onStart { emit(emptyList()) }
        },
        _isCreatingNew,
        _selectedLogForEdit
    ) { logs, isCreating, editLog ->
        val prediction = calculatePrediction(logs)
        PeriodUiState(
            logs = logs.sortedByDescending { it.startDate },
            prediction = prediction,
            isCreatingNew = isCreating,
            selectedLogForEdit = editLog,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PeriodUiState(isLoading = true)
    )

    private fun epochToLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun calculatePrediction(logs: List<PeriodLog>): PeriodPrediction {
        if (logs.isEmpty()) return PeriodPrediction()

        val sortedAsc = logs.sortedBy { it.startDate }
        val today = LocalDate.now()
        val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)

        val completedPeriods = sortedAsc.filter { it.endDate != null }
        val avgDuration = if (completedPeriods.isNotEmpty()) {
            completedPeriods.map { it.durationDays }.average().toInt().coerceIn(1, 15)
        } else {
            5
        }

        val cycleLengths = mutableListOf<Int>()
        for (i in 0 until sortedAsc.size - 1) {
            val start1 = epochToLocalDate(sortedAsc[i].startDate)
            val start2 = epochToLocalDate(sortedAsc[i + 1].startDate)
            val days = ChronoUnit.DAYS.between(start1, start2).toInt()
            if (days in 15..45) {
                cycleLengths.add(days)
            }
        }
        val recentCycleLengths = cycleLengths.takeLast(6)
        val avgCycle = if (recentCycleLengths.isNotEmpty()) {
            recentCycleLengths.average().toInt().coerceIn(20, 45)
        } else {
            28
        }

        val latestPeriod = sortedAsc.lastOrNull()
        val nextStartDate = latestPeriod?.let {
            epochToLocalDate(it.startDate).plusDays(avgCycle.toLong())
        }
        val daysUntilNext = nextStartDate?.let {
            ChronoUnit.DAYS.between(today, it).toInt()
        }
        val predictedOvulation = nextStartDate?.minusDays(14)
        val fertileStart = predictedOvulation?.minusDays(5)
        val fertileEnd = predictedOvulation?.plusDays(1)

        val isBleeding = latestPeriod?.let {
            if (it.endDate == null) {
                today >= epochToLocalDate(it.startDate)
            } else {
                today >= epochToLocalDate(it.startDate) && today <= epochToLocalDate(it.endDate)
            }
        } ?: false

        val isFertile = fertileStart != null && fertileEnd != null && today >= fertileStart && today <= fertileEnd

        val currentPhase = when {
            isBleeding -> "Bleeding / Period Phase"
            isFertile -> "Fertile Window (Approximate)"
            daysUntilNext != null && daysUntilNext in 0..5 -> "Pre-menstrual / Luteal Phase"
            latestPeriod != null -> "Follicular Phase"
            else -> "No Data Available"
        }

        val phaseDescription = when {
            isBleeding -> "Log symptoms or stay hydrated."
            isFertile -> "Highest chance of conception."
            daysUntilNext != null && daysUntilNext in 0..5 -> "Expect period soon."
            latestPeriod != null -> "Normal follicular activity."
            else -> "Add logs to calculate."
        }

        return PeriodPrediction(
            avgDurationDays = avgDuration,
            avgCycleDays = avgCycle,
            nextStartDateStr = nextStartDate?.format(dateFormatter),
            daysUntilNext = daysUntilNext,
            predictedOvulationStr = predictedOvulation?.format(dateFormatter),
            fertileWindowStr = if (fertileStart != null && fertileEnd != null) "${fertileStart.format(dateFormatter)} - ${fertileEnd.format(dateFormatter)}" else null,
            currentPhase = currentPhase,
            phaseDescription = phaseDescription,
            isBleeding = isBleeding,
            isFertile = isFertile
        )
    }

    fun startNewPeriod(notes: String = "") {
        val session = MemoryAccountScope.capture()
        viewModelScope.launch(Dispatchers.IO) {
            MemoryAccountScope.requireCurrent(session)
            val now = System.currentTimeMillis()
            val log = PeriodLog(
                startDate = now,
                notes = notes.ifBlank { null }, ownerKey = session.ownerKey
            )
            dao.insertPeriod(log)
            _events.send(PeriodUiEvent.ShowToast("Period start logged"))
        }
    }

    fun endCurrentPeriod() {
        val session = MemoryAccountScope.capture()
        viewModelScope.launch(Dispatchers.IO) {
            MemoryAccountScope.requireCurrent(session)
            val latest = dao.getLatestPeriod(session.ownerKey)
            if (latest == null || latest.endDate != null) {
                _events.send(PeriodUiEvent.ShowToast("No active ongoing period found"))
                return@launch
            }
            val now = System.currentTimeMillis()
            val startLocal = epochToLocalDate(latest.startDate)
            val endLocal = epochToLocalDate(now)
            val duration = (ChronoUnit.DAYS.between(startLocal, endLocal).toInt() + 1).coerceAtLeast(1)
            MemoryAccountScope.requireCurrent(session)
            dao.updatePeriodEndDate(latest.id, now, duration, session.ownerKey)
            _events.send(PeriodUiEvent.ShowToast("Period end logged ($duration days)"))
        }
    }

    fun saveCustomPeriod(
        startDate: Long,
        endDate: Long?,
        notes: String
    ) {
        val session = MemoryAccountScope.capture()
        val currentEdit = _selectedLogForEdit.value
        viewModelScope.launch(Dispatchers.IO) {
            MemoryAccountScope.requireCurrent(session)
            val duration = if (endDate != null) {
                val startLocal = epochToLocalDate(startDate)
                val endLocal = epochToLocalDate(endDate)
                (ChronoUnit.DAYS.between(startLocal, endLocal).toInt() + 1).coerceAtLeast(1)
            } else 0

            if (currentEdit != null) {
                if (currentEdit.ownerKey != session.ownerKey) return@launch
                val updated = currentEdit.copy(
                    startDate = startDate,
                    endDate = endDate,
                    durationDays = duration,
                    notes = notes.ifBlank { null }, ownerKey = session.ownerKey
                )
                dao.insertPeriod(updated)
            } else {
                val newLog = PeriodLog(
                    startDate = startDate,
                    endDate = endDate,
                    durationDays = duration,
                    notes = notes.ifBlank { null }, ownerKey = session.ownerKey
                )
                dao.insertPeriod(newLog)
            }
            dismissEditor()
            _events.send(PeriodUiEvent.ShowToast("Period log saved"))
        }
    }

    fun deletePeriod(id: Int) {
        val session = MemoryAccountScope.capture()
        viewModelScope.launch(Dispatchers.IO) {
            MemoryAccountScope.requireCurrent(session)
            dao.deletePeriod(id, session.ownerKey)
            _events.send(PeriodUiEvent.ShowToast("Period log deleted"))
        }
    }

    fun startCreate() {
        _isCreatingNew.value = true
        _selectedLogForEdit.value = null
    }

    fun startEdit(log: PeriodLog) {
        if (!MemoryAccountScope.isCurrent(MemoryAccountScope.capture()) ||
            log.ownerKey != MemoryAccountScope.capture().ownerKey) return
        _selectedLogForEdit.value = log
        _isCreatingNew.value = false
    }

    fun dismissEditor() {
        _isCreatingNew.value = false
        _selectedLogForEdit.value = null
    }
}
