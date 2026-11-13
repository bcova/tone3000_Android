package com.bcova.namplayer.t3k

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import com.bcova.namplayer.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * TONE3000 sign-in (OAuth 2.0 + PKCE), per https://www.tone3000.com/api#authentication.
 *
 * Analogy: PKCE is a coat-check ticket. Before sending the user to TONE3000 we keep a secret
 * half (the "verifier") and send only a fingerprint of it (the "challenge"). When TONE3000 sends
 * the user back with a code, we hand in the secret half to prove the code is really ours.
 */
object T3kConfig {
    const val BASE_URL = "https://www.tone3000.com"
    const val REDIRECT_URI = "com.bcova.namplayer://callback"
    val CLIENT_ID: String = BuildConfig.T3K_CLIENT_ID
}

/** Why a sign-in was started, so we know what to do when the user comes back. */
enum class AuthPurpose { SIGN_IN, SELECT_TONE }

/** What the user picked the tone for. */
enum class TargetSlot { AMP, CAB }

class PendingAuth(
    val verifier: String,
    val state: String,
    val purpose: AuthPurpose,
    val target: TargetSlot,
)

/** Stores tokens and the in-progress sign-in in app-private storage. */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("t3k_auth", Context.MODE_PRIVATE)

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    val expiresAtMillis: Long get() = prefs.getLong(KEY_EXPIRES, 0L)
    val isSignedIn: Boolean get() = refreshToken != null

    fun save(session: Session) = prefs.edit {
        putString(KEY_ACCESS, session.accessToken)
        putString(KEY_REFRESH, session.refreshToken)
        // Refresh a minute early so a request never goes out with an expiring token.
        putLong(KEY_EXPIRES, System.currentTimeMillis() + (session.expiresIn - 60) * 1000)
    }

    fun clearTokens() = prefs.edit {
        remove(KEY_ACCESS)
        remove(KEY_REFRESH)
        remove(KEY_EXPIRES)
    }

    // The pending sign-in must survive the app being killed while the browser tab is open.
    fun savePending(p: PendingAuth) = prefs.edit {
        putString(KEY_P_VERIFIER, p.verifier)
        putString(KEY_P_STATE, p.state)
        putString(KEY_P_PURPOSE, p.purpose.name)
        putString(KEY_P_TARGET, p.target.name)
    }

    fun takePending(): PendingAuth? {
        val verifier = prefs.getString(KEY_P_VERIFIER, null) ?: return null
        val pending = PendingAuth(
            verifier = verifier,
            state = prefs.getString(KEY_P_STATE, "") ?: "",
            purpose = runCatching { AuthPurpose.valueOf(prefs.getString(KEY_P_PURPOSE, "")!!) }
                .getOrDefault(AuthPurpose.SIGN_IN),
            target = runCatching { TargetSlot.valueOf(prefs.getString(KEY_P_TARGET, "")!!) }
                .getOrDefault(TargetSlot.AMP),
        )
        prefs.edit {
            remove(KEY_P_VERIFIER)
            remove(KEY_P_STATE)
            remove(KEY_P_PURPOSE)
            remove(KEY_P_TARGET)
        }
        return pending
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_P_VERIFIER = "pending_verifier"
        const val KEY_P_STATE = "pending_state"
        const val KEY_P_PURPOSE = "pending_purpose"
        const val KEY_P_TARGET = "pending_target"
    }
}

object Pkce {
    private val random = SecureRandom()

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun newVerifier(): String = base64Url(ByteArray(48).also(random::nextBytes))

    fun newState(): String = base64Url(ByteArray(16).also(random::nextBytes))

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** Builds the URL that opens TONE3000's sign-in / browse page in a browser tab. */
fun buildAuthorizeUrl(pending: PendingAuth): Uri {
    val builder = Uri.parse("${T3kConfig.BASE_URL}/api/v1/oauth/authorize").buildUpon()
        .appendQueryParameter("client_id", T3kConfig.CLIENT_ID)
        .appendQueryParameter("redirect_uri", T3kConfig.REDIRECT_URI)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("code_challenge", Pkce.challenge(pending.verifier))
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("state", pending.state)
        .appendQueryParameter("menubar", "true") // back/close bar, recommended for in-app browsers
    if (pending.purpose == AuthPurpose.SELECT_TONE) {
        builder.appendQueryParameter("prompt", "select_tone")
            .appendQueryParameter("preview", "true") // let users hear demos before picking
        when (pending.target) {
            TargetSlot.AMP -> builder.appendQueryParameter("format", Tone.FORMAT_NAM)
            TargetSlot.CAB -> builder.appendQueryParameter("format", Tone.FORMAT_IR)
        }
    }
    return builder.build()
}

/** The result TONE3000 sends back to com.bcova.namplayer://callback. */
data class AuthCallback(
    val code: String?,
    val state: String?,
    val toneId: Long?,
    val canceled: Boolean,
    val error: String?,
) {
    companion object {
        fun isCallback(uri: Uri?): Boolean =
            uri != null && uri.scheme == "com.bcova.namplayer" && uri.host == "callback"

        fun parse(uri: Uri) = AuthCallback(
            code = uri.getQueryParameter("code"),
            state = uri.getQueryParameter("state"),
            toneId = uri.getQueryParameter("tone_id")?.toLongOrNull(),
            canceled = uri.getQueryParameter("canceled") == "true",
            error = uri.getQueryParameter("error"),
        )
    }
}
