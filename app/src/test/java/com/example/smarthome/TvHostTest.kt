package com.example.smarthome
import org.junit.Assert.*
import org.junit.Test
class TvHostTest {
 @Test fun acceptsLocalTv() { assertTrue(lanHost("192.168.1.23")) }
 @Test fun rejectsExternalAndInvalidHosts() { listOf("8.8.8.8","localhost","10.0.1.300","172.32.1.1").forEach { assertFalse(lanHost(it)) } }
}