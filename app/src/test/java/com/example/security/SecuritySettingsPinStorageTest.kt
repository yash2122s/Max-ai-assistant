package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.memory.security.MemoryCryptography
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecuritySettingsPinStorageTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("security_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun pinRoundTripsThroughEncryptedStorage() {
        val settings = SecuritySettings(context)
        settings.encryptedPin = "1234"

        assertEquals("1234", settings.getDecryptedPin())

        val stored = prefs.getString("pin_enc", null)
        assertNotNull(stored)
        assertTrue(stored!!.startsWith(MemoryCryptography.VERSION_PREFIX))
        assertFalse(stored.contains("1234"))
        assertFalse(stored.contains(Base64.encodeToString("1234".toByteArray(), Base64.NO_WRAP)))
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun legacyBase64PinIsMigratedOnce() {
        val legacy = Base64.encodeToString("4321".toByteArray(), Base64.DEFAULT)
        prefs.edit().putString("pin_enc", legacy).commit()

        val settings = SecuritySettings(context)

        assertEquals("4321", settings.getDecryptedPin())
        val stored = prefs.getString("pin_enc", null)
        assertNotNull(stored)
        assertTrue(stored!!.startsWith(MemoryCryptography.VERSION_PREFIX))
        assertFalse(stored == legacy)
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun encryptionFailureRetainsLegacyValueAndFailsClosed() {
        val legacy = Base64.encodeToString("1234".toByteArray(), Base64.DEFAULT)
        prefs.edit().putString("pin_enc", legacy).commit()
        val failingCipher = object : PinCipher {
            override fun encrypt(pin: String): String =
                throw GeneralSecurityException("key unavailable")

            override fun decrypt(ciphertext: String): String =
                throw GeneralSecurityException("key unavailable")

            override fun isCiphertext(value: String): Boolean =
                value.startsWith(MemoryCryptography.VERSION_PREFIX)
        }

        val settings = SecuritySettings(context, failingCipher)

        assertEquals("", settings.getDecryptedPin())
        assertEquals(legacy, prefs.getString("pin_enc", null))
        assertEquals(0, prefs.getInt("pin_storage_version", 0))

        try {
            settings.encryptedPin = "9999"
            throw AssertionError("Expected encrypted PIN persistence to fail")
        } catch (expected: IllegalStateException) {
            assertEquals(legacy, prefs.getString("pin_enc", null))
        }
    }

    @Test
    fun missingDecryptionKeyFailsClosedWithoutDeletingCiphertext() {
        val ciphertext = MemoryCryptography.encrypt("1234")
        prefs.edit()
            .putString("pin_enc", ciphertext)
            .putInt("pin_storage_version", 2)
            .commit()
        val failingCipher = object : PinCipher {
            override fun encrypt(pin: String): String =
                throw GeneralSecurityException("key unavailable")

            override fun decrypt(ciphertext: String): String =
                throw GeneralSecurityException("key unavailable")

            override fun isCiphertext(value: String): Boolean =
                value.startsWith(MemoryCryptography.VERSION_PREFIX)
        }

        val settings = SecuritySettings(context, failingCipher)

        assertEquals("", settings.getDecryptedPin())
        assertEquals(ciphertext, prefs.getString("pin_enc", null))
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun corruptedCiphertextFailsClosedWithoutChangingStorage() {
        val settings = SecuritySettings(context)
        settings.encryptedPin = "1234"
        val corrupted = "enc_v1:not-valid-iv:not-valid-ciphertext"
        prefs.edit().putString("pin_enc", corrupted).commit()

        val reloaded = SecuritySettings(context)

        assertEquals("", reloaded.getDecryptedPin())
        assertEquals(corrupted, prefs.getString("pin_enc", null))
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun clearingPinRemovesStoredValueAndSetsVersion() {
        val settings = SecuritySettings(context)
        settings.encryptedPin = "1234"
        assertEquals("1234", settings.getDecryptedPin())

        settings.encryptedPin = ""
        assertEquals("", settings.getDecryptedPin())
        assertEquals("", settings.encryptedPin)
        assertEquals(null, prefs.getString("pin_enc", null))
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun futureStorageVersionFailsClosedWithoutModifyingStorage() {
        val ciphertext = MemoryCryptography.encrypt("1234")
        prefs.edit()
            .putString("pin_enc", ciphertext)
            .putInt("pin_storage_version", 999)
            .commit()

        val settings = SecuritySettings(context)

        assertEquals("", settings.getDecryptedPin())
        assertEquals("", settings.encryptedPin)
        assertEquals(ciphertext, prefs.getString("pin_enc", null))
        assertEquals(999, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun corruptedLegacyBase64FailsClosedAndRetainsValue() {
        val corruptedLegacy = "===="
        prefs.edit()
            .putString("pin_enc", corruptedLegacy)
            .putInt("pin_storage_version", 0)
            .commit()

        val settings = SecuritySettings(context)

        assertEquals("", settings.getDecryptedPin())
        assertEquals(corruptedLegacy, prefs.getString("pin_enc", null))
        assertEquals(0, prefs.getInt("pin_storage_version", 0))
    }

    @Test
    fun alreadyCiphertextStorageWithLegacyVersionUpgradesVersion() {
        val ciphertext = MemoryCryptography.encrypt("5678")
        prefs.edit()
            .putString("pin_enc", ciphertext)
            .putInt("pin_storage_version", 0)
            .commit()

        val settings = SecuritySettings(context)

        assertEquals("5678", settings.getDecryptedPin())
        assertEquals(ciphertext, prefs.getString("pin_enc", null))
        assertEquals(2, prefs.getInt("pin_storage_version", 0))
    }


    @Test
    fun legacyBackupRulesExcludeSecuritySettings() {
        val exclusions = parseExclusions(com.example.R.xml.backup_rules)
        assertTrue(exclusions.contains("sharedpref:security_settings.xml"))
    }

    @Test
    fun dataExtractionRulesExcludeSecuritySettingsFromCloudAndTransfer() {
        val sections = parseSections(com.example.R.xml.data_extraction_rules)
        assertTrue(
            sections["cloud-backup"]?.contains("sharedpref:security_settings.xml") == true
        )
        assertTrue(
            sections["device-transfer"]?.contains("sharedpref:security_settings.xml") == true
        )
    }

    private fun parseExclusions(resourceId: Int): Set<String> {
        val exclusions = mutableSetOf<String>()
        val parser = context.resources.getXml(resourceId)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "exclude") {
                val domain = parser.getAttributeValue(null, "domain")
                val path = parser.getAttributeValue(null, "path")
                if (domain != null && path != null) {
                    exclusions.add("$domain:$path")
                }
            }
            event = parser.next()
        }
        return exclusions
    }

    private fun parseSections(resourceId: Int): Map<String, Set<String>> {
        val sections = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(resourceId)
        var currentSection: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> {
                        currentSection = parser.name
                        sections.getOrPut(parser.name) { mutableSetOf() }
                    }
                    "exclude" -> {
                        val section = currentSection
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        if (section != null && domain != null && path != null) {
                            sections.getOrPut(section) { mutableSetOf() }
                                .add("$domain:$path")
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                        currentSection = null
                    }
                }
            }
            event = parser.next()
        }
        return sections
    }
}
