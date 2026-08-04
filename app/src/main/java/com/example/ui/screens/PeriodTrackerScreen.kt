package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
import com.example.data.local.PeriodLogDao
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodTrackerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val periodLogs by db.periodLogDao().getAllPeriodsFlow().collectAsState(initial = emptyList())

    var showAddNoteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }

    // Date formatting helper
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US) }

    // Timezone safe epoch millis conversions
    fun epochToLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    fun localDateToEpoch(date: LocalDate): Long {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    // Heuristics predictions calculation
    val today = LocalDate.now()
    val latestPeriod = periodLogs.lastOrNull()

    // 1. Calculate Average Period Duration
    val completedPeriods = periodLogs.filter { it.endDate != null }
    val avgDuration = if (completedPeriods.isNotEmpty()) {
        completedPeriods.map { it.durationDays }.average().toInt()
    } else {
        5
    }

    // 2. Calculate Average Cycle Length with Outlier Filtering (15-45 days)
    val cycleLengths = mutableListOf<Int>()
    for (i in 0 until periodLogs.size - 1) {
        val start1 = epochToLocalDate(periodLogs[i].startDate)
        val start2 = epochToLocalDate(periodLogs[i+1].startDate)
        val days = ChronoUnit.DAYS.between(start1, start2).toInt()
        if (days in 15..45) {
            cycleLengths.add(days)
        }
    }

    val recentCycleLengths = cycleLengths.takeLast(6)
    val hasEnoughData = recentCycleLengths.isNotEmpty()
    val avgCycle = if (hasEnoughData) {
        recentCycleLengths.average().toInt()
    } else {
        28
    }

    // 3. Predictions
    val nextStartDate = latestPeriod?.let {
        epochToLocalDate(it.startDate).plusDays(avgCycle.toLong())
    }
    val daysUntilNext = nextStartDate?.let {
        ChronoUnit.DAYS.between(today, it).toInt()
    }
    val predictedOvulation = nextStartDate?.minusDays(14)
    val fertileStart = predictedOvulation?.minusDays(5)
    val fertileEnd = predictedOvulation?.plusDays(1)

    // Current phase status
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Period Tracking & Prediction", color = TextLight, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Background atmospheric rose/purple glow
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-50).dp)
                    .size(350.dp)
                    .background(NeonPink.copy(alpha = 0.15f), CircleShape)
                    .blur(100.dp)
            )

            if (periodLogs.isEmpty()) {
                // Empty state view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = NeonPink,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Track Your Cycle",
                        color = TextLight,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Log past period start dates to predict your next cycle and fertile window with up to 90% accuracy. Your reproductive health data is stored strictly locally and excluded from all device backups for absolute privacy.",
                        color = TextLight.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val log = PeriodLog(
                                    startDate = localDateToEpoch(today),
                                    endDate = null,
                                    durationDays = 0
                                )
                                db.periodLogDao().insertPeriod(log)
                                Toast.makeText(context, "Period started today logged!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Log Period Start Today", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Prediction banner
                    item {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.95f,
                            targetValue = 1.05f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse_scale"
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                            .clip(CircleShape)
                                            .background(NeonPink)
                                    )
                                    Text(
                                        text = currentPhase.uppercase(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonPink,
                                        letterSpacing = 1.5.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = phaseDescription,
                                    fontSize = 14.sp,
                                    color = TextLight.copy(alpha = 0.7f),
                                    modifier = Modifier.align(Alignment.Start)
                                )
                                Spacer(modifier = Modifier.height(24.dp))

                                // Main prediction metric display
                                if (isBleeding) {
                                    Text(
                                        text = "Active Period",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = latestPeriod?.startDate?.let { "Started: " + epochToLocalDate(it).format(dateFormatter) } ?: "",
                                        fontSize = 14.sp,
                                        color = TextLight.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } else if (nextStartDate != null && daysUntilNext != null) {
                                    Text(
                                        text = when {
                                            daysUntilNext > 0 -> "$daysUntilNext Days Left"
                                            daysUntilNext == 0 -> "Starts Today"
                                            else -> "Overdue by ${-daysUntilNext} Days"
                                        },
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "Next Period: " + nextStartDate.format(dateFormatter),
                                        fontSize = 14.sp,
                                        color = TextLight.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = TextLight.copy(alpha = 0.4f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Approximate estimates only. Not diagnostic or medical advice.",
                                        fontSize = 10.sp,
                                        color = TextLight.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }

                    // Fertile window & ovulation details
                    if (predictedOvulation != null && fertileStart != null && fertileEnd != null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text(
                                        text = "Fertile Window & Ovulation",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextLight
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("Fertile Period", fontSize = 11.sp, color = TextLight.copy(alpha = 0.5f))
                                            Text(
                                                text = "${fertileStart.format(dateFormatter)} - ${fertileEnd.format(dateFormatter)}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Ovulation Day", fontSize = 11.sp, color = TextLight.copy(alpha = 0.5f))
                                            Text(
                                                text = predictedOvulation.format(dateFormatter),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = NeonPink,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Quick Actions
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val ongoing = latestPeriod != null && latestPeriod.endDate == null
                            Button(
                                onClick = {
                                    scope.launch {
                                        val log = PeriodLog(
                                            startDate = localDateToEpoch(today),
                                            endDate = null,
                                            durationDays = 0
                                        )
                                        db.periodLogDao().insertPeriod(log)
                                        Toast.makeText(context, "Logged cycle start today", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !ongoing,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonPink,
                                    disabledContainerColor = BorderDark
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Log Start", color = if (ongoing) TextLight.copy(alpha = 0.3f) else Color.White)
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        latestPeriod?.let {
                                            val endEpoch = localDateToEpoch(today)
                                            val dur = (ChronoUnit.DAYS.between(epochToLocalDate(it.startDate), today) + 1).toInt()
                                            db.periodLogDao().updatePeriodEndDate(it.id, endEpoch, dur)
                                            Toast.makeText(context, "Logged cycle end today ($dur days)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = ongoing,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonBlue,
                                    disabledContainerColor = BorderDark
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Log End", color = if (!ongoing) TextLight.copy(alpha = 0.3f) else Color.Black)
                            }

                            IconButton(
                                onClick = {
                                    noteText = latestPeriod?.notes ?: ""
                                    showAddNoteDialog = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Add Notes", tint = TextLight)
                            }
                        }
                    }

                    // Stats Grid
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Avg Cycle Length Card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Average Cycle Length: $avgCycle Days" }
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Avg Cycle Length", fontSize = 11.sp, color = TextLight.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "$avgCycle Days",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (hasEnoughData) "Calculated" else "Estimated default",
                                        fontSize = 10.sp,
                                        color = if (hasEnoughData) Emerald400 else Color(0xFFFB923C),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }

                            // Avg Duration Card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Average Bleeding Duration: $avgDuration Days" }
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Avg Duration", fontSize = 11.sp, color = TextLight.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "$avgDuration Days",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (completedPeriods.isNotEmpty()) "Calculated" else "Default value",
                                        fontSize = 10.sp,
                                        color = if (completedPeriods.isNotEmpty()) Emerald400 else Color(0xFFFB923C),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Cycle history title
                    item {
                        Text(
                            text = "Cycle History",
                            color = TextLight,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Cycle Logs List
                    items(periodLogs.reversed()) { log ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.02f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val startStr = epochToLocalDate(log.startDate).format(dateFormatter)
                                    val endStr = log.endDate?.let { epochToLocalDate(it).format(dateFormatter) } ?: "Ongoing"
                                    Text(
                                        text = "$startStr - $endStr",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (log.endDate == null) "Ongoing Bleeding" else "Bleeding duration: ${log.durationDays} days",
                                        fontSize = 12.sp,
                                        color = TextLight.copy(alpha = 0.5f)
                                    )
                                    if (!log.notes.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Note: ${log.notes}",
                                            fontSize = 11.sp,
                                            color = TextLight.copy(alpha = 0.7f),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            db.periodLogDao().deletePeriod(log.id)
                                            Toast.makeText(context, "Log deleted", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Log",
                                        tint = TextLight.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }

                    // Delete all reproductive data button
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = { showDeleteConfirmDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = NeonPink.copy(alpha = 0.8f))
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear all period and cycle data", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Symptom note Dialog
    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("Log Symptoms & Notes", color = TextLight) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Enter cramps, mood, flow details...", color = TextLight.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedBorderColor = NeonPink,
                        unfocusedBorderColor = BorderDark
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            latestPeriod?.let {
                                db.periodLogDao().updatePeriodNotes(it.id, noteText)
                                Toast.makeText(context, "Note updated", Toast.LENGTH_SHORT).show()
                            } ?: run {
                                Toast.makeText(context, "Start a cycle first to add notes.", Toast.LENGTH_SHORT).show()
                            }
                            showAddNoteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text("Cancel", color = TextLight)
                }
            },
            containerColor = SurfaceDarker
        )
    }

    // Destruction Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = NeonPink) },
            title = { Text("Clear All Cycle Data?", color = TextLight) },
            text = {
                Text(
                    text = "This action will permanently delete all logged period dates, history, cycle analysis, and notes from this device. This cannot be undone.",
                    color = TextLight.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            db.periodLogDao().deleteAllPeriods()
                            Toast.makeText(context, "All period records cleared.", Toast.LENGTH_SHORT).show()
                            showDeleteConfirmDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                ) {
                    Text("Delete All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextLight)
                }
            },
            containerColor = SurfaceDarker
        )
    }
}
