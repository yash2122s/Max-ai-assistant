package com.example.automation

import com.example.automation.engine.DirectCallAuthorization
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCallAuthorizationTest {
    private val originalClock = DirectCallAuthorization.nowMillis

    @After
    fun tearDown() {
        DirectCallAuthorization.nowMillis = originalClock
        DirectCallAuthorization.clear()
    }

    @Test
    fun directGrantIsBoundToNumberAndSingleUse() {
        DirectCallAuthorization.nowMillis = { 1_000L }
        val token = DirectCallAuthorization.issue("call 9988776655", "9988776655")
        assertNotNull(token)

        assertTrue(DirectCallAuthorization.consume(token, "9988 776655"))
        assertFalse("A direct-call grant must not be replayable", DirectCallAuthorization.consume(token, "9988776655"))
    }

    @Test
    fun directGrantRejectsChangedNumberAndExpires() {
        var now = 1_000L
        DirectCallAuthorization.nowMillis = { now }
        val token = DirectCallAuthorization.issue("call +91 9988776655", "+919988776655")
        assertNotNull(token)
        assertFalse(DirectCallAuthorization.consume(token, "+9199887776656"))

        val expiringToken = DirectCallAuthorization.issue("dial 9988776655", "9988776655")
        assertNotNull(expiringToken)
        now += 30_001L
        assertFalse(DirectCallAuthorization.consume(expiringToken, "9988776655"))
    }

    @Test
    fun directGrantRejectsMessagingAndMultipleNumberUtterances() {
        assertTrue(DirectCallAuthorization.issue("send message to 9988776655", "9988776655") == null)
        assertTrue(DirectCallAuthorization.issue("call 9988776655 or 9111223344", "9988776655") == null)
        assertTrue(DirectCallAuthorization.issue("call 12345", "12345") == null)
    }

    @Test
    fun directGrantAcceptsTinglishNumberFirstCallCommand() {
        val token = DirectCallAuthorization.issue("9988776655 ki call chey", "9988776655")
        assertNotNull(token)
        assertTrue(DirectCallAuthorization.consume(token, "9988776655"))
    }

    @Test
    fun directGrantAcceptsBareNumberUtterance() {
        val token = DirectCallAuthorization.issue("9640461271", "9640461271")
        assertNotNull(token)
        assertTrue(DirectCallAuthorization.consume(token, "9640461271"))

        val token2 = DirectCallAuthorization.issue("49640461271", "49640461271")
        assertNotNull(token2)
        assertTrue(DirectCallAuthorization.consume(token2, "49640461271"))
    }
}
