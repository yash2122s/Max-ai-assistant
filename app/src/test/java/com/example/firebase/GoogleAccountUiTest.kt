package com.example.firebase

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.firebase.model.FirebaseUserAccount
import com.example.ui.license.GoogleAccountSection
import com.example.ui.theme.MaxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class GoogleAccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accountSwitchAndLogoutAreDistinctVisibleActionsOnCompactScreen() {
        var switches = 0
        var logouts = 0
        compose.setContent {
            MaxTheme {
                GoogleAccountSection(FirebaseUserAccount(uid = "test-A", displayName = "Test Account"),
                    onSignIn = {}, onSignOut = { logouts++ }, onSwitchAccount = { switches++ })
            }
        }
        compose.onNodeWithText("Test Account").assertIsDisplayed()
        compose.onNodeWithText("Switch Account").assertIsDisplayed().performClick()
        assertEquals(1, switches)
        assertEquals(0, logouts)
        compose.onNodeWithText("Sign Out").assertIsDisplayed().performClick()
        assertEquals(1, logouts)
    }
}
