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

    /**
     * In-app full catalog search uses TONE3000 endpoints reserved for approved/commercial apps.
     * OK for personal development and testing; turn OFF (gradle.properties) before sharing the
     * app unless TONE3000 has approved it in writing.
     */
    val FULL_CATALOG_SEARCH: Boolean = BuildConfig.T3K_FULL_CATALOG_SEARCH
}

/** Why a sign-in was started, so we know what to do when the user comes back. */
enum class AuthPurpose { SIGN_IN, SELECT_TONE }

class PendingAuth(
    val verifier: String,
    val state: String,
    val purpose: AuthPurpose,
    val target: BlockTarget,
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
        putLong(KEY_P_BLOCK, p.target.blockId)
        putString(KEY_P_KIND, p.target.kind.name)
    }

    fun takePending(): PendingAuth? {
        val verifier = prefs.getString(KEY_P_VERIFIER, null) ?: return null
        val pending = PendingAuth(
            verifier = verifier,
            state = prefs.getString(KEY_P_STATE, "") ?: "",
            purpose = runCatching { AuthPurpose.valueOf(prefs.getString(KEY_P_PURPOSE, "")!!) }
                .getOrDefault(AuthPurpose.SIGN_IN),
            target = BlockTarget(
                blockId = prefs.getLong(KEY_P_BLOCK, -1L),
                kind = runCatching { BlockKind.valueOf(prefs.getString(KEY_P_KIND, "")!!) }.getOrDefault(BlockKind.ANY),
            ),
        )
        prefs.edit {
            remove(KEY_P_VERIFIER)
            remove(KEY_P_STATE)
            remove(KEY_P_PURPOSE)
            remove(KEY_P_BLOCK)
            remove(KEY_P_KIND)
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
        const val KEY_P_BLOCK = "pending_block"
        const val KEY_P_KIND = "pending_kind"
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
        // Show tones that fit the block being filled (e.g. only pedals for a Pedal block).
        val kind = pending.target.kind
        kind.format?.let { builder.appendQueryParameter("format", it) }
        if (kind.gears.isNotEmpty()) builder.appendQueryParameter("gears", kind.gears.sorted().joinToString("_"))
    }
    return builder.build()
}

/** The result TONE3000 sends back to com.bcova.namplayer://callback. */
data class AuthCallback(
    val code: String?,
    val state: String?,
    val toneId: String?,
    val canceled: Boolean,
    val error: String?,
) {
    companion object {
        fun isCallback(uri: Uri?): Boolean =
            uri != null && uri.scheme == "com.bcova.namplayer" && uri.host == "callback"

        fun parse(uri: Uri) = AuthCallback(
            code = uri.getQueryParameter("code"),
            state = uri.getQueryParameter("state"),
            toneId = uri.getQueryParameter("tone_id")?.takeIf { it.isNotBlank() },
            canceled = uri.getQueryParameter("canceled") == "true",
            error = uri.getQueryParameter("error"),
        )
    }
}
