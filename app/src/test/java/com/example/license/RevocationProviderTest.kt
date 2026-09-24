package com.example.license

import com.example.license.revocation.GitHubRevocationProvider
import com.example.license.storage.LicenseStateDao
import com.example.license.storage.LicenseStateEntity
import com.example.license.storage.LicenseStorage
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RevocationProviderTest {

    private class InMemoryLicenseStateDao : LicenseStateDao {
        var entity: LicenseStateEntity? = null
        override fun getState(): LicenseStateEntity? = entity
        override fun upsert(entity: LicenseStateEntity) {
            this.entity = entity
        }
    }

    private fun createMockClient(interceptor: Interceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @Test
    fun http_404_fails_open_without_modifying_cache_or_throwing() = runBlocking {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val client = createMockClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val provider = GitHubRevocationProvider(storage, "https://example.com/revoked.json", client)
        provider.refresh()

        assertTrue(provider.cachedRevokedIds().isEmpty())
    }

    @Test
    fun http_500_fails_open_without_crashing() = runBlocking {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val client = createMockClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("Server error".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val provider = GitHubRevocationProvider(storage, "https://example.com/revoked.json", client)
        provider.refresh()

        assertTrue(provider.cachedRevokedIds().isEmpty())
    }

    @Test
    fun socket_timeout_fails_open_safely() = runBlocking {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val client = createMockClient {
            throw SocketTimeoutException("Simulated connection timeout")
        }

        val provider = GitHubRevocationProvider(storage, "https://example.com/revoked.json", client)
        provider.refresh()

        assertTrue(provider.cachedRevokedIds().isEmpty())
    }

    @Test
    fun malformed_json_response_fails_open_without_corrupting_cache() = runBlocking {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val client = createMockClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<!DOCTYPE html><html>Broken response</html>".toResponseBody("text/html".toMediaType()))
                .build()
        }

        val provider = GitHubRevocationProvider(storage, "https://example.com/revoked.json", client)
        provider.refresh()

        assertTrue(provider.cachedRevokedIds().isEmpty())
    }

    @Test
    fun valid_json_updates_cached_revoked_ids() = runBlocking {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val jsonPayload = """
            {
                "revokedLicenseIds": [
                    "MAX-2026-000042",
                    "MAX-2026-000099"
                ]
            }
        """.trimIndent()

        val client = createMockClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val provider = GitHubRevocationProvider(storage, "https://example.com/revoked.json", client)
        provider.refresh()

        val cached = provider.cachedRevokedIds()
        assertEquals(2, cached.size)
        assertTrue(cached.contains("MAX-2026-000042"))
        assertTrue(cached.contains("MAX-2026-000099"))
    }
}
