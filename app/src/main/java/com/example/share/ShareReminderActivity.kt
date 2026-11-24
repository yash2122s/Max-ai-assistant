package com.example.share

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.MyApplication
import com.example.data.preferences.SettingsManager
import com.example.data.repository.JarvisRepository
import com.example.ui.theme.MaxAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

class ShareReminderViewModel : ViewModel() {
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    fun save(repo: JarvisRepository, title: String, trigger: Long) {
        if (saving || saved || title.isBlank()) return
        if (trigger <= System.currentTimeMillis()) {
            error = "Choose a future reminder time."
            return
        }
        saving = true
        error = ""
        viewModelScope.launch {
            try {
                repo.saveReminder(null, title.trim(), trigger)
                saved = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "MAX could not schedule this reminder. Check alarm access and try again."
            } finally {
                saving = false
            }
        }
    }
}

class ShareReminderActivity : ComponentActivity() {
    private val model: ShareReminderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val image = runCatching { sharedImage(intent) }.getOrNull()
        if (image == null) {
            Toast.makeText(this, "Share one screenshot to MAX.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val settings = SettingsManager(this)
        val app = application as MyApplication
        setContent {
            MaxAppTheme {
                val user by app.maxLicenseManager.currentUser.collectAsState()
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (user == null || settings.onboardingStage != SettingsManager.OnboardingStage.COMPLETED) {
                        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Open MAX and finish signing in, then share the screenshot again.")
                            Button(onClick = { startActivity(Intent(this@ShareReminderActivity, MainActivity::class.java)); finish() }) { Text("Open MAX") }
                        }
                    } else {
                        Editor(extract = { readScreenshot(applicationContext, image) })
                    }
                }
            }
        }
    }

    @Composable
    private fun Editor(extract: suspend () -> String) {
        val zone = remember { TimeZone.getDefault() }
        var loaded by rememberSaveable { mutableStateOf(false) }
        var text by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf("") }
        var date by rememberSaveable { mutableStateOf("") }
        var time by rememberSaveable { mutableStateOf("") }
        var before by rememberSaveable { mutableStateOf("0") }
        var notificationHint by rememberSaveable { mutableStateOf(false) }
        var showText by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!loaded) {
                try {
                    text = extract().take(12000)
                    val draft = ReminderDraftParser.extract(text, System.currentTimeMillis(), zone)
                    title = draft.title
                    date = draft.date
                    time = draft.time
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    text = ""
                }
                loaded = true
            }
        }
        val trigger = ReminderDraftParser.trigger(date, time, before, zone)
        fun persist() {
            if (trigger != null) model.save(JarvisRepository(applicationContext), title, trigger)
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) persist() else notificationHint = true
        }
        LaunchedEffect(model.saved) {
            if (model.saved) {
                Toast.makeText(this@ShareReminderActivity, "Reminder saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Create reminder", style = MaterialTheme.typography.headlineMedium)
            if (!loaded) {
                LinearProgressIndicator()
                Text("Reading screenshot on this device…")
            } else {
                if (text.isBlank()) Text("No readable text. Enter the reminder details below.")
                else {
                    TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide screenshot text" else "View screenshot text") }
                    if (showText) Text(text)
                }
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("Reminder title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Event date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("Event time (HH:mm, 24-hour)") }, singleLine = true)
                OutlinedTextField(before, { before = it }, label = { Text("How early?") },
                    supportingText = { Text("Minutes, or “30 minutes mundu remind chey”") }, singleLine = true)
                val formatted = trigger?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply { timeZone = zone }.format(Date(it)) }
                Text(if (formatted != null) "Reminder: $formatted (${zone.id})" else "Enter a valid date, time and lead time (0–10080 minutes). Times use ${zone.id}.")
                if (trigger != null && trigger <= System.currentTimeMillis()) Text("Choose a future reminder time.")
                if (notificationHint) Text("Enable MAX notifications in Android Settings to receive the reminder.")
                if (model.error.isNotEmpty()) Text(model.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { finish() }, enabled = !model.saving) { Text("Cancel") }
                    Button(enabled = title.isNotBlank() && trigger != null && trigger > System.currentTimeMillis() && !model.saving && !model.saved,
                        onClick = {
                            if (NotificationManagerCompat.from(this@ShareReminderActivity).areNotificationsEnabled()) persist()
                            else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else notificationHint = true
                        }) { Text(if (model.saving) "Saving…" else "Save reminder") }
                }
            }
        }
    }
}
