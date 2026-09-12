package com.example.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.GoalPlanner
import com.example.automation.state.ActionStep
import com.example.knowledge.apps.InstalledAppsRepository
import com.example.network.provider.LLMPlanningProvider
import com.example.network.provider.PlanningRequest
import com.example.network.provider.PlanningResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppResolutionUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GoalPlanner.clearGoal()
        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator"),
            com.example.data.local.InstalledApp(packageName = "com.android.chrome", appName = "Google Chrome"),
            com.example.data.local.InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube")
        )
        InstalledAppsRepository.setCachedAppsForTesting(testApps)
        runBlocking {
            val db = com.example.data.local.AppDatabase.getDatabase(context)
            db.installedAppDao().insertApps(testApps)
        }
    }

    @Test
    fun test1_OpenCalculator_ResolvesInstalledAppCaseInsensitively() {
        val resolved1 = InstalledAppsRepository.findApp(context, "Calculator")
        assertNotNull(resolved1)
        assertEquals("com.google.android.calculator", resolved1)

        val resolved2 = InstalledAppsRepository.findApp(context, "calculator")
        assertNotNull(resolved2)
        assertEquals("com.google.android.calculator", resolved2)

        val resolved3 = InstalledAppsRepository.findApp(context, "CALCULATOR")
        assertNotNull(resolved3)
        assertEquals("com.google.android.calculator", resolved3)
    }

    @Test
    fun test2_UnknownApp_ReturnsNull() {
        val resolved = InstalledAppsRepository.findApp(context, "UnknownNonExistentApp123")
        assertNull(resolved)
    }

    @Test
    fun test3_ExplicitPackageName_ResolvedDirectly() {
        val resolved = InstalledAppsRepository.findApp(context, "com.google.android.calculator")
        assertEquals("com.google.android.calculator", resolved)
    }

    @Test
    fun test4_FastPath_ResolvesPackageDynamically() {
        val plan = GoalPlanner.resolveFastPathPlan("Open Calculator", context)
        assertNotNull(plan)
        assertEquals(1, plan!!.size)
        assertEquals("OPEN_APP", plan[0].actionType)
        assertEquals("com.google.android.calculator", plan[0].targetText)
    }

    @Test
    fun test5_FastPath_UnknownApp_ReturnsNullToPreventInvalidExecution() {
        val plan = GoalPlanner.resolveFastPathPlan("Open UnknownNonExistentApp123", context)
        assertNull(plan)
    }

    @Test
    fun test6_FastPath_BypassesLLMAndPassesAuthorization() {
        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator"),
            com.example.data.local.InstalledApp(packageName = "com.android.chrome", appName = "Google Chrome"),
            com.example.data.local.InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube")
        )
        InstalledAppsRepository.setCachedAppsForTesting(testApps)

        var providerCalled = false
        val spyProvider = object : LLMPlanningProvider {
            override val providerId: String = "spy"
            override val defaultModel: String = "spy-model"
            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                providerCalled = true
                return PlanningResponse(success = true, plan = emptyList())
            }
        }
        GoalPlanner.setPlanningProvider(spyProvider)

        val plan = GoalPlanner.resolveFastPathPlan("Open Calculator", context)
        assertNotNull("Plan must not be null for Open Calculator", plan)
        assertEquals(1, plan!!.size)
        assertEquals("OPEN_APP", plan[0].actionType)
        assertEquals("com.google.android.calculator", plan[0].targetText)

        val validationError = GoalPlanner.validatePlan(plan, "Open Calculator")
        assertNull(validationError)

        val auth = ActionAuthorizer.authorizeAction("Open Calculator", "OPEN_APP", plan[0].targetText)
        assertTrue(auth.authorized)

        assertFalse("LLM provider must not be called during fast-path resolution", providerCalled)
    }

    @Test
    fun test7_NaturalLanguage_Tanglish_CalculatorOpenChey() {
        val plan = GoalPlanner.resolveFastPathPlan("Calculator open chey", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.google.android.calculator", plan[0].targetText)
    }

    @Test
    fun test8_NaturalLanguage_Tanglish_CalculatorNiOpenChey() {
        val plan = GoalPlanner.resolveFastPathPlan("Calculator ni open chey", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.google.android.calculator", plan[0].targetText)
    }

    @Test
    fun test9_NaturalLanguage_PolitePrefix_PleaseOpenChrome() {
        val plan = GoalPlanner.resolveFastPathPlan("Please open Chrome", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)
    }

    @Test
    fun test10_NaturalLanguage_PolitePrefix_CanYouOpenChrome() {
        val plan = GoalPlanner.resolveFastPathPlan("Can you open Chrome", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)
    }

    @Test
    fun test11_NaturalLanguage_SuffixFiller_ChromeOpenChesiPettu() {
        val plan = GoalPlanner.resolveFastPathPlan("Chrome open chesi pettu", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)
    }

    @Test
    fun test12_NaturalLanguage_Telugu_CalculatorTeruvu() {
        // App resolution alias or target
        val plan = GoalPlanner.resolveFastPathPlan("Calculator తెరువు", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.google.android.calculator", plan[0].targetText)
    }

    @Test
    fun test13_NaturalLanguage_Telugu_OkaSariChromeOpenCheyi() {
        val plan = GoalPlanner.resolveFastPathPlan("ఓకసారి Chrome ఓపెన్ చేయి", context)
        assertNotNull(plan)
        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)
    }

    @Test
    fun test14_CompoundTask_YouTubeLoSongsSearchChey_ResolvedToFastPathPlan() {
        val plan = GoalPlanner.resolveFastPathPlan("YouTube lo songs search chey", context)
        assertNotNull("Search command must resolve to 4-step fast path plan", plan)
        assertEquals(4, plan?.size)
        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("songs", plan?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)
    }

    @Test
    fun test15_CompoundTask_WhatsAppOpenChesiBhaskarKiHelloPampu_RejectedFromFastPath() {
        val plan = GoalPlanner.resolveFastPathPlan("WhatsApp open chesi Bhaskar ki Hello pampu", context)
        assertNull("Compound multi-step task must not take single-step fast path", plan)
    }

    @Test
    fun test16_ActionAuthorizer_TeluguOpenKeywords() {
        val auth1 = ActionAuthorizer.authorizeAction("Calculator తెరువు", "OPEN_APP", "com.google.android.calculator")
        assertTrue(auth1.authorized)

        val auth2 = ActionAuthorizer.authorizeAction("ఓకసారి Chrome ఓపెన్ చేయి", "OPEN_APP", "com.android.chrome")
        assertTrue(auth2.authorized)
    }
}
