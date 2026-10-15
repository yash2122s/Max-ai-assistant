package com.example.firebase

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.license.entitlement.Entitlement
import com.example.license.entitlement.EntitlementManagerImpl
import com.example.license.licensing.LicenseVerificationResult
import com.example.license.licensing.LicenseVerifier
import com.example.license.licensing.SignedLicense
import com.example.license.revocation.RevocationProvider
import com.example.license.storage.LicenseStorage
import com.example.license.trial.TrialManager
import com.example.ui.license.refreshForAccountUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogoutEntitlementRefreshTest {
    private lateinit var database: AppDatabase
    private lateinit var entitlement: EntitlementManagerImpl

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        entitlement = EntitlementManagerImpl(
            verifier = object : LicenseVerifier {
                override fun verify(license: SignedLicense): LicenseVerificationResult = error("No signed license fixture")
            },
            storage = LicenseStorage(database.licenseStateDao()),
            trialManager = object : TrialManager {
                override fun getTrialState(): Entitlement = Entitlement.Free
            },
            revocationProvider = object : RevocationProvider {
                override fun cachedRevokedIds(): Set<String> = emptySet()
                override suspend fun refresh() = Unit
            })
    }

    @After fun teardown() { database.close() }

    @Test fun originalLogoutRefreshOnMainReproducesRoomException() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val error = assertThrows(IllegalStateException::class.java) { entitlement.refresh() }
        assertTrue(error.message.orEmpty().contains("main thread"))
    }

    @Test fun logoutUiRefreshMovesRoomReadOffMainAndReturnsToCaller() = runBlocking {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        entitlement.refreshForAccountUi()
        assertEquals(Entitlement.Free, entitlement.entitlementFlow.value)
        assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
}
