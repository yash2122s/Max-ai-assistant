package com.example.ui.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NotificationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        settingsManager = SettingsManager(app)
        settingsManager.whatsappAutoReplyScope = "ALL"
        settingsManager.whatsappSelectedContacts = emptySet()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setWhatsAppAutoReplyScope_updatesSettingsAndState() = runTest(testDispatcher) {
        val viewModel = NotificationsViewModel(app)
        viewModel.setWhatsAppAutoReplyScope("SELECTED")

        assertEquals("SELECTED", settingsManager.whatsappAutoReplyScope)
    }

    @Test
    fun addAndRemoveSelectedContact_persistsToSettings() = runTest(testDispatcher) {
        val viewModel = NotificationsViewModel(app)

        viewModel.addSelectedContact("Alice")
        assertTrue(settingsManager.whatsappSelectedContacts.contains("Alice"))

        viewModel.addSelectedContact("+1234567890")
        assertEquals(setOf("Alice", "+1234567890"), settingsManager.whatsappSelectedContacts)

        viewModel.removeSelectedContact("Alice")
        assertEquals(setOf("+1234567890"), settingsManager.whatsappSelectedContacts)
    }

    @Test
    fun contactPickerAndSearchQuery_workAsExpected() = runTest(testDispatcher) {
        val viewModel = NotificationsViewModel(app)

        viewModel.openContactPicker()
        viewModel.updateContactSearchQuery("Bob")
        viewModel.closeContactPicker()
    }
}
