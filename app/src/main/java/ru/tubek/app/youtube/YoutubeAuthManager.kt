package ru.tubek.app.youtube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import ru.tubek.app.BuildConfig

data class YoutubeAuthSession(
    val accessToken: String,
    val accountEmail: String?,
    val displayName: String
)

sealed class SignInPrepareResult {
    data class SignedIn(val session: YoutubeAuthSession) : SignInPrepareResult()
    data class NeedsUserConsent(val pendingIntent: android.app.PendingIntent) : SignInPrepareResult()
}

/**
 * Google OAuth через AuthorizationClient (scopes YouTube).
 * Требует Web Client ID в BuildConfig / local.properties.
 */
class YoutubeAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<YoutubeAuthSession?> = _session.asStateFlow()

    fun accessToken(): String? = _session.value?.accessToken

    fun isSignedIn(): Boolean = !_session.value?.accessToken.isNullOrBlank()

    suspend fun restoreSilently(): YoutubeAuthSession? = withContext(Dispatchers.IO) {
        val webClientId = BuildConfig.YOUTUBE_OAUTH_WEB_CLIENT_ID
        if (webClientId.isBlank()) return@withContext _session.value
        try {
            val result = Identity.getAuthorizationClient(appContext)
                .authorize(buildRequest(webClientId))
                .await()
            if (!result.hasResolution()) {
                persist(result)
            } else {
                _session.value
            }
        } catch (_: Exception) {
            _session.value
        }
    }

    suspend fun prepareSignIn(activity: Activity): SignInPrepareResult =
        withContext(Dispatchers.IO) {
            val webClientId = BuildConfig.YOUTUBE_OAUTH_WEB_CLIENT_ID
            require(webClientId.isNotBlank()) {
                "OAuth Web Client ID не задан. Добавьте youtube.oauth.web.client.id в local.properties"
            }
            val result = Identity.getAuthorizationClient(activity)
                .authorize(buildRequest(webClientId))
                .await()
            if (result.hasResolution()) {
                val pending = result.pendingIntent
                    ?: error("Google не вернул PendingIntent для согласия")
                SignInPrepareResult.NeedsUserConsent(pending)
            } else {
                SignInPrepareResult.SignedIn(persist(result))
            }
        }

    suspend fun completeSignInFromIntent(data: Intent?): YoutubeAuthSession =
        withContext(Dispatchers.IO) {
            val result = Identity.getAuthorizationClient(appContext)
                .getAuthorizationResultFromIntent(data)
            persist(result)
        }

    fun signOut() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun buildRequest(webClientId: String): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(SCOPE_YOUTUBE),
                    Scope(SCOPE_YOUTUBE_READONLY)
                )
            )
            .requestOfflineAccess(webClientId)
            .build()

    private fun persist(result: AuthorizationResult): YoutubeAuthSession {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: error("Google не вернул access token")
        val account = result.toGoogleSignInAccount()
        val email = account?.email
        val name = account?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: email
            ?: "YouTube"
        val session = YoutubeAuthSession(
            accessToken = token,
            accountEmail = email,
            displayName = name
        )
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, name)
            .apply()
        _session.value = session
        return session
    }

    private fun readSession(): YoutubeAuthSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return YoutubeAuthSession(
            accessToken = token,
            accountEmail = prefs.getString(KEY_EMAIL, null),
            displayName = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: "YouTube"
        )
    }

    companion object {
        private const val PREFS = "youtube_auth"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "display_name"
        private const val SCOPE_YOUTUBE = "https://www.googleapis.com/auth/youtube"
        private const val SCOPE_YOUTUBE_READONLY =
            "https://www.googleapis.com/auth/youtube.readonly"

        fun friendlyError(error: Throwable): String {
            val message = error.message.orEmpty()
            return when {
                error is ApiException && error.statusCode == 12501 -> "Вход отменён"
                message.contains("Web Client ID", ignoreCase = true) -> message
                message.isBlank() -> "Не удалось войти через Google"
                else -> message
            }
        }
    }
}
