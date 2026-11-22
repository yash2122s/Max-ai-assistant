package com.example.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.theme.MaxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyLanguageSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allSupportedLanguagesCanBeSelectedWithoutEditingPartialText() {
        val selected = mutableStateOf("Tenglish")
        val choices = mutableListOf<String>()
        compose.setContent {
            MaxTheme {
                ReplyLanguageSelector(selected.value, { selected.value = it; choices.add(it) })
            }
        }
        compose.onNodeWithText("Tinglish").assertIsSelected()
        for (language in listOf("Telugu", "English", "Hindi", "Tinglish")) {
            compose.onNodeWithText(language).assertIsDisplayed().performClick().assertIsSelected()
        }
        assertEquals(listOf("Telugu", "English", "Hindi", "Tinglish"), choices)
    }

    @Test fun externalPreferenceUpdateChangesSelectedChoice() {
        val selected = mutableStateOf("Telugu")
        compose.setContent { MaxTheme { ReplyLanguageSelector(selected.value, {}) } }
        compose.onNodeWithText("Telugu").assertIsSelected()
        compose.runOnIdle { selected.value = "Hindi" }
        compose.onNodeWithText("Telugu").assertIsNotSelected()
        compose.onNodeWithText("Hindi").assertIsSelected()
        compose.onNodeWithText("Reply & voice language").assertIsDisplayed()
    }
}
