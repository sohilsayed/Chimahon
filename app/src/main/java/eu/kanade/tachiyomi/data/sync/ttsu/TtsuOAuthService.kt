package eu.kanade.tachiyomi.data.sync.ttsu

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import eu.kanade.domain.sync.SyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL

/**
 * Standalone OAuth service for TTU/Hoshi-compatible novel sync.
 * Uses the user's own OAuth client and is fully independent from Komikku's sync service.
 */
class TtsuOAuthService(private val context: Context) {

    companion object {
        const val REDIRECT_URI = "app.chimahon.ttu.oauth:/oauth2redirect"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private val syncPreferences: SyncPreferences = Injekt.get()
    private var cachedCredential: GoogleCredential? = null
    private var cachedDriveService: Drive? = null

    val isAuthenticated: Boolean
        get() = syncPreferences.ttuAccessToken().get().isNotEmpty() &&
            syncPreferences.ttuRefreshToken().get().isNotEmpty()

    val hasClientId: Boolean
        get() = syncPreferences.ttuClientId().get().isNotEmpty()

    /**
     * Builds an Intent to open the browser for Google OAuth consent.
     */
    fun getSignInIntent(): Intent {
        val clientId = syncPreferences.ttuClientId().get()
        val url = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        return Intent(Intent.ACTION_VIEW).apply {
            data = url
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Exchanges authorization code for access + refresh tokens.
     */
    suspend fun handleAuthorizationCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val clientId = syncPreferences.ttuClientId().get()
        if (clientId.isBlank()) return@withContext false
        val clientSecret = syncPreferences.ttuClientSecret().get()
        try {
            val paramsList = mutableListOf(
                "code" to code,
                "client_id" to clientId,
                "redirect_uri" to REDIRECT_URI,
                "grant_type" to "authorization_code",
            )
            if (clientSecret.isNotEmpty()) {
                paramsList.add("client_secret" to clientSecret)
            }
            val params = paramsList.joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }

            val result = postTokenRequest(params)
            if (result != null) {
                syncPreferences.ttuAccessToken().set(result.accessToken)
                syncPreferences.ttuAccessTokenExpiry().set(result.expiresAt)
                cachedCredential?.accessToken = result.accessToken
                if (result.refreshToken != null) {
                    syncPreferences.ttuRefreshToken().set(result.refreshToken)
                }
                logcat(LogPriority.INFO) { "TTU OAuth: Successfully authenticated" }
                true
            } else {
                logcat(LogPriority.ERROR) { "TTU OAuth: Token exchange failed" }
                false
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e) { "TTU OAuth: Failed to exchange code" }
            false
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     */
    suspend fun ensureValidToken(): Boolean = withContext(Dispatchers.IO) {
        val accessToken = syncPreferences.ttuAccessToken().get()
        val accessTokenExpiry = syncPreferences.ttuAccessTokenExpiry().get()

        if (accessToken.isNotEmpty() && accessTokenExpiry > System.currentTimeMillis()) {
            return@withContext true
        }

        return@withContext refreshToken()
    }

    private suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val clientId = syncPreferences.ttuClientId().get()
        val refreshToken = syncPreferences.ttuRefreshToken().get()

        if (clientId.isEmpty() || refreshToken.isEmpty()) {
            return@withContext false
        }

        try {
            val clientSecret = syncPreferences.ttuClientSecret().get()
            val paramsList = mutableListOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            )
            if (clientSecret.isNotEmpty()) {
                paramsList.add("client_secret" to clientSecret)
            }
            val params = paramsList.joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }

            val result = postTokenRequest(params)
            if (result != null) {
                syncPreferences.ttuAccessToken().set(result.accessToken)
                syncPreferences.ttuAccessTokenExpiry().set(result.expiresAt)
                cachedCredential?.accessToken = result.accessToken
                logcat(LogPriority.DEBUG) { "TTU OAuth: Token refreshed" }
                return@withContext true
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e) { "TTU OAuth: Failed to refresh token" }
        }

        // Refresh failed, clear tokens so settings reflect that sign-in is required.
        syncPreferences.ttuAccessToken().set("")
        syncPreferences.ttuAccessTokenExpiry().set(0L)
        syncPreferences.ttuRefreshToken().set("")
        false
    }

    /**
     * Builds a Google Drive service using the current TTU access token.
     * Returns null if not authenticated.
     */
    fun getDriveService(): Drive? {
        val accessToken = syncPreferences.ttuAccessToken().get()
        val hasRefreshToken = syncPreferences.ttuRefreshToken().get().isNotEmpty()

        if (accessToken.isEmpty() || !hasRefreshToken) {
            return null
        }

        cachedDriveService?.let { service ->
            cachedCredential?.accessToken = accessToken
            return service
        }

        val credential = GoogleCredential().apply {
            this.accessToken = accessToken
        }

        val service = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        ).setApplicationName(context.stringResource(MR.strings.app_name))
            .build()

        cachedCredential = credential
        cachedDriveService = service
        return service
    }

    /**
     * Sign out and clear OAuth tokens while keeping client credentials.
     */
    fun signOut() {
        syncPreferences.ttuAccessToken().set("")
        syncPreferences.ttuAccessTokenExpiry().set(0L)
        syncPreferences.ttuRefreshToken().set("")
        cachedCredential = null
        cachedDriveService = null
    }

    private data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
    )

    private fun postTokenRequest(params: String): TokenResult? {
        val url = URL(TOKEN_ENDPOINT)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write(params.toByteArray()) }

            if (conn.responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                logcat(LogPriority.ERROR) { "TTU OAuth token error ${conn.responseCode}: $error" }
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(body)

            return TokenResult(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 3600L) * 1000L) - 60_000L,
            )
        } finally {
            conn.disconnect()
        }
    }

}
