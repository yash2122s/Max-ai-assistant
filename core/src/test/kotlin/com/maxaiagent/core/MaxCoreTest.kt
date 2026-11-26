package com.maxaiagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class MaxCoreTest {
    @Test
    fun `aes gcm round trip succeeds and rejects tampered payload`() {
        val salt = MaxCrypto.generateSalt()
        val key = MaxCrypto.deriveKey("max-desktop-passphrase", salt)
        val secret = "MAX_MEMORY_VAULT_ENTRY_01"

        val encrypted = MaxCrypto.encryptAesGcm(secret, key)
        val decrypted = MaxCrypto.decryptAesGcm(encrypted, key)
        assertEquals(secret, decrypted)

        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("AA")) "BB" else "AA"
        assertThrows(AEADBadTagException::class.java) {
            MaxCrypto.decryptAesGcm(tampered, key)
        }
    }

    @Test
    fun `command normalizer classifies english and telugu home commands`() {
        val en = MaxCommandNormalizer.normalize("  Go to home screen  ")
        val te = MaxCommandNormalizer.normalize("హోమ్ ఓపెన్ చేయి")

        assertEquals(MaxIntentKind.HOME_NAVIGATION, en.intent)
        assertEquals(MaxIntentKind.HOME_NAVIGATION, te.intent)
        assertEquals(64, en.fingerprint.length)
        assertNotEquals(en.fingerprint, te.fingerprint)
    }
}
