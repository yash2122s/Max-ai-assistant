package com.example.license.revocation

import com.example.license.storage.LicenseStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface RevocationProvider {
    /** Cached list only — reading this never blocks or requires network. */
    fun cachedRevokedIds(): Set<String>

    /** Call opportunistically (app start, if online). Fails open on any error. */
    suspend fun refresh()
}

@Serializable
private data class RevocationList(val revokedLicenseIds: List<String>)

/**
 * Free, no-server revocation: host a small JSON file at a GitHub raw
 * URL (e.g. raw.githubusercontent.com/you/max-revocations/main/revoked.json)
 * and edit it by hand when a license needs pulling.
 *
 * Fails open on purpose: a network error, timeout, or malformed
 * response NEVER counts as "revoked." Only a positive match against a
 * successfully-fetched list does. Getting this backwards would let a
 * flaky connection lock out legitimate paying users.
 */
class GitHubRevocationProvider(
    private val storage: LicenseStorage,
    private val revocationListUrl: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build(),
) : RevocationProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun cachedRevokedIds(): Set<String> = storage.getCachedRevokedIds()

    override suspend fun refresh() {
        if (revocationListUrl.isBlank()) return
        try {
            val request = Request.Builder().url(revocationListUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                val list = json.decodeFromString(RevocationList.serializer(), body)
                storage.setCachedRevokedIds(list.revokedLicenseIds.toSet())
            }
        } catch (e: Exception) {
            // Deliberately swallowed: offline or network being slow must
            // never be treated as a signal about license validity.
        }
    }
}
