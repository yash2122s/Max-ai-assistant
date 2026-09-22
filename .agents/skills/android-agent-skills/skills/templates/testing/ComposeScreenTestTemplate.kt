package com.example.app.feature.template

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * Template for testing Compose UI screens.
 * 
 * This template demonstrates best practices for Compose UI testing:
 * - Screen rendering tests
 * - User interaction tests
 * - State-driven UI tests
 * - Accessibility verification
 * - Semantic testing
 * 
 * Test Coverage Goals:
 * - UI rendering: 30% (all states render correctly)
 * - User interactions: 35% (clicks, inputs, gestures)
 * - State changes: 20% (state reflects in UI)
 * - Accessibility: 15% (semantics, content descriptions)
 * 
 * Test Tags Convention:
 * - Use TestTags object for consistent tag names
 * - Format: "[ScreenName]_[ComponentName]"
 * 
 * @see 05-jetpack-compose.md for Compose patterns
 * @see 06-compose-performance.md for performance testing
 * @see 11-testing.md for testing guidelines
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// TEST TAGS (for consistent test identification)
// ============================================================

object ProfileScreenTestTags {
    const val SCREEN = "ProfileScreen"
    const val LOADING_INDICATOR = "ProfileScreen_LoadingIndicator"
    const val ERROR_MESSAGE = "ProfileScreen_ErrorMessage"
    const val RETRY_BUTTON = "ProfileScreen_RetryButton"
    const val USER_AVATAR = "ProfileScreen_UserAvatar"
    const val USER_NAME = "ProfileScreen_UserName"
    const val USER_EMAIL = "ProfileScreen_UserEmail"
    const val NAME_INPUT = "ProfileScreen_NameInput"
    const val EMAIL_INPUT = "ProfileScreen_EmailInput"
    const val SAVE_BUTTON = "ProfileScreen_SaveButton"
    const val EDIT_BUTTON = "ProfileScreen_EditButton"
    const val EMPTY_STATE = "ProfileScreen_EmptyState"
}

// ============================================================
// EXAMPLE SCREEN TO TEST
// ============================================================

// UI State
@Immutable
data class ProfileUiState(
    val user: User? = null,
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && email.contains("@") && !isSaving
    val hasChanges: Boolean get() = user?.let { it.name != name || it.email != email } ?: false
}

data class User(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
)

// Screen Composable (Stateless)
// Parameter order: Required → State → Modifier → Callbacks
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onEditClick: () -> Unit,
    onRetryClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(ProfileScreenTestTags.SCREEN)
    ) {
        when {
            state.isLoading -> {
                LoadingContent()
            }
            state.error != null -> {
                ErrorContent(
                    message = state.error,
                    onRetryClick = onRetryClick,
                )
            }
            state.user == null -> {
                EmptyContent()
            }
            else -> {
                ProfileContent(
                    state = state,
                    onNameChange = onNameChange,
                    onEmailChange = onEmailChange,
                    onSaveClick = onSaveClick,
                    onEditClick = onEditClick,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .testTag(ProfileScreenTestTags.LOADING_INDICATOR)
                .semantics { contentDescription = "Loading profile" }
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier,
    onRetryClick: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .testTag(ProfileScreenTestTags.ERROR_MESSAGE)
                .semantics { contentDescription = "Error: $message" }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetryClick,
            modifier = Modifier.testTag(ProfileScreenTestTags.RETRY_BUTTON),
        ) {
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(ProfileScreenTestTags.EMPTY_STATE),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No profile found",
            modifier = Modifier.semantics { contentDescription = "No profile found" }
        )
    }
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onEditClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // User info display
        Text(
            text = state.user?.name ?: "",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag(ProfileScreenTestTags.USER_NAME),
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = state.user?.email ?: "",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(ProfileScreenTestTags.USER_EMAIL),
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (state.isEditing) {
            // Edit mode
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProfileScreenTestTags.NAME_INPUT),
                enabled = !state.isSaving,
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = { Text("Email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProfileScreenTestTags.EMAIL_INPUT),
                enabled = !state.isSaving,
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onSaveClick,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProfileScreenTestTags.SAVE_BUTTON),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save")
                }
            }
        } else {
            // View mode
            Button(
                onClick = onEditClick,
                modifier = Modifier.testTag(ProfileScreenTestTags.EDIT_BUTTON),
            ) {
                Text("Edit Profile")
            }
        }
    }
}

// ============================================================
// TEST SUITE
// ============================================================

/**
 * Comprehensive test suite for ProfileScreen.
 * 
 * Test Organization:
 * - Rendering tests: Verify UI displays correctly for each state
 * - Interaction tests: Verify user actions trigger callbacks
 * - State tests: Verify state changes reflect in UI
 * - Accessibility tests: Verify semantic properties
 */
class ProfileScreenTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    // Test data
    private val testUser = User(
        id = "user-1",
        name = "John Doe",
        email = "john@example.com",
    )
    
    private val defaultState = ProfileUiState(
        user = testUser,
        name = testUser.name,
        email = testUser.email,
    )
    
    // ========================================
    // Rendering Tests (30%)
    // ========================================
    
    @Test
    fun whenLoadingState_thenShowsLoadingIndicator() {
        // Given
        val state = ProfileUiState(isLoading = true)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.LOADING_INDICATOR)
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.USER_NAME)
            .assertDoesNotExist()
    }
    
    @Test
    fun whenErrorState_thenShowsErrorMessageAndRetryButton() {
        // Given
        val errorMessage = "Failed to load profile"
        val state = ProfileUiState(error = errorMessage)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.ERROR_MESSAGE)
            .assertIsDisplayed()
            .assertTextContains(errorMessage)
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.RETRY_BUTTON)
            .assertIsDisplayed()
            .assertIsEnabled()
    }
    
    @Test
    fun whenEmptyState_thenShowsEmptyContent() {
        // Given
        val state = ProfileUiState(user = null)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EMPTY_STATE)
            .assertIsDisplayed()
    }
    
    @Test
    fun whenSuccessState_thenShowsUserProfile() {
        // Given
        val state = defaultState
        
        // When
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.USER_NAME)
            .assertIsDisplayed()
            .assertTextEquals("John Doe")
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.USER_EMAIL)
            .assertIsDisplayed()
            .assertTextEquals("john@example.com")
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EDIT_BUTTON)
            .assertIsDisplayed()
    }
    
    @Test
    fun whenEditingMode_thenShowsInputFields() {
        // Given
        val state = defaultState.copy(isEditing = true)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .assertIsDisplayed()
            .assertIsEnabled()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EMAIL_INPUT)
            .assertIsDisplayed()
            .assertIsEnabled()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.SAVE_BUTTON)
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EDIT_BUTTON)
            .assertDoesNotExist()
    }
    
    // ========================================
    // User Interaction Tests (35%)
    // ========================================
    
    @Test
    fun whenEditButtonClicked_thenCallsOnEditClick() {
        // Given
        var editClicked = false
        val state = defaultState
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = { editClicked = true },
                onRetryClick = {},
            )
        }
        
        // When
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EDIT_BUTTON)
            .performClick()
        
        // Then
        assert(editClicked) { "onEditClick should be called" }
    }
    
    @Test
    fun whenRetryButtonClicked_thenCallsOnRetryClick() {
        // Given
        var retryClicked = false
        val state = ProfileUiState(error = "Error")
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = { retryClicked = true },
            )
        }
        
        // When
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.RETRY_BUTTON)
            .performClick()
        
        // Then
        assert(retryClicked) { "onRetryClick should be called" }
    }
    
    @Test
    fun whenNameInputChanged_thenCallsOnNameChange() {
        // Given
        var capturedName = ""
        val state = defaultState.copy(isEditing = true)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = { capturedName = it },
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // When
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .performTextClearance()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .performTextInput("Jane Doe")
        
        // Then
        assert(capturedName == "Jane Doe") { "onNameChange should be called with new name" }
    }
    
    @Test
    fun whenEmailInputChanged_thenCallsOnEmailChange() {
        // Given
        var capturedEmail = ""
        val state = defaultState.copy(isEditing = true)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = { capturedEmail = it },
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // When
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EMAIL_INPUT)
            .performTextClearance()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EMAIL_INPUT)
            .performTextInput("jane@example.com")
        
        // Then
        assert(capturedEmail == "jane@example.com") { "onEmailChange should be called with new email" }
    }
    
    @Test
    fun whenSaveButtonClicked_thenCallsOnSaveClick() {
        // Given
        var saveClicked = false
        val state = defaultState.copy(
            isEditing = true,
            name = "Jane",
            email = "jane@test.com"
        )
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = { saveClicked = true },
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // When
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.SAVE_BUTTON)
            .performClick()
        
        // Then
        assert(saveClicked) { "onSaveClick should be called" }
    }
    
    // ========================================
    // State Change Tests (20%)
    // ========================================
    
    @Test
    fun whenSaveButtonDisabled_thenCannotClick() {
        // Given - Invalid email (missing @)
        val state = defaultState.copy(
            isEditing = true,
            name = "Jane",
            email = "invalid-email"  // Missing @
        )
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.SAVE_BUTTON)
            .assertIsNotEnabled()
    }
    
    @Test
    fun whenSaving_thenInputsAreDisabled() {
        // Given
        val state = defaultState.copy(
            isEditing = true,
            isSaving = true,
        )
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .assertIsNotEnabled()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.EMAIL_INPUT)
            .assertIsNotEnabled()
    }
    
    @Test
    fun whenNameIsBlank_thenSaveIsDisabled() {
        // Given
        val state = defaultState.copy(
            isEditing = true,
            name = "   ",  // Blank
            email = "valid@email.com"
        )
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.SAVE_BUTTON)
            .assertIsNotEnabled()
    }
    
    // ========================================
    // Accessibility Tests (15%)
    // ========================================
    
    @Test
    fun loadingIndicator_hasContentDescription() {
        // Given
        val state = ProfileUiState(isLoading = true)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithContentDescription("Loading profile")
            .assertExists()
    }
    
    @Test
    fun errorMessage_hasContentDescription() {
        // Given
        val errorMessage = "Network error"
        val state = ProfileUiState(error = errorMessage)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithContentDescription("Error: $errorMessage")
            .assertExists()
    }
    
    @Test
    fun emptyState_hasContentDescription() {
        // Given
        val state = ProfileUiState(user = null)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then
        composeTestRule
            .onNodeWithContentDescription("No profile found")
            .assertExists()
    }
    
    // ========================================
    // Edge Cases
    // ========================================
    
    @Test
    fun whenLongUserName_thenDisplaysCorrectly() {
        // Given
        val longName = "A".repeat(100)
        val state = defaultState.copy(
            user = testUser.copy(name = longName),
            name = longName,
        )
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // Then - Should not crash
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.USER_NAME)
            .assertExists()
    }
    
    @Test
    fun whenSpecialCharactersInInput_thenHandlesCorrectly() {
        // Given
        var capturedName = ""
        val state = defaultState.copy(isEditing = true)
        
        composeTestRule.setContent {
            ProfileScreen(
                state = state,
                onNameChange = { capturedName = it },
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
        
        // When
        val specialChars = "José García <script>alert('xss')</script>"
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .performTextClearance()
        
        composeTestRule
            .onNodeWithTag(ProfileScreenTestTags.NAME_INPUT)
            .performTextInput(specialChars)
        
        // Then - Should handle without crashing
        assert(capturedName == specialChars)
    }
}

// ============================================================
// SCREENSHOT TESTING (with Paparazzi/Roborazzi)
// ============================================================

/**
 * Screenshot tests for visual regression testing.
 * 
 * Setup required:
 * - Add Paparazzi or Roborazzi dependency
 * - Configure in build.gradle.kts
 * 
 * Run: ./gradlew :app:recordPaparazziDebug
 * Verify: ./gradlew :app:verifyPaparazziDebug
 */

/*
// Example with Paparazzi
class ProfileScreenScreenshotTest {
    
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material3.Light",
    )
    
    @Test
    fun loadingState() {
        paparazzi.snapshot {
            ProfileScreen(
                state = ProfileUiState(isLoading = true),
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
    }
    
    @Test
    fun errorState() {
        paparazzi.snapshot {
            ProfileScreen(
                state = ProfileUiState(error = "Network error"),
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
    }
    
    @Test
    fun profileViewMode() {
        paparazzi.snapshot {
            ProfileScreen(
                state = ProfileUiState(
                    user = User("1", "John Doe", "john@example.com"),
                    name = "John Doe",
                    email = "john@example.com",
                ),
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
    }
    
    @Test
    fun profileEditMode() {
        paparazzi.snapshot {
            ProfileScreen(
                state = ProfileUiState(
                    user = User("1", "John Doe", "john@example.com"),
                    name = "John Doe",
                    email = "john@example.com",
                    isEditing = true,
                ),
                onNameChange = {},
                onEmailChange = {},
                onSaveClick = {},
                onEditClick = {},
                onRetryClick = {},
            )
        }
    }
}
*/
