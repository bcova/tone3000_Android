package com.bcova.namplayer.t3k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** JSON settings for TONE3000 responses: tolerant of extra fields and nulls. */
val T3kJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

/** A problem talking to TONE3000, with a message that's safe to show the user. */
class T3kException(message: String, val status: Int = 0) : IOException(message)

/** Signed out (refresh token expired or revoked): the user has to sign in again. */
class SignedOutException : IOException("Please sign in to TONE3000 again.")

/**
 * Talks to the TONE3000 API.
 *
 * Uses the OAuth prompt flows, the bounded lists (favorited, downloaded, created, trending,
 * latest), tone/model lookups, and full-catalog search (/tones/search, /tags, /makes), which
 * TONE3000 approved for this free app by email (Nov 2026). See README.
 */
class T3kClient(private val tokens: TokenStore) {

    private val json = T3kJson
    private val refreshLock = Mutex()

    // ---- Auth

    suspend fun exchangeCode(code: String, verifier: String) {
        val session: Session = postForm(
            "/api/v1/oauth/token",
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to T3kConfig.REDIRECT_URI,
                "client_id" to T3kConfig.CLIENT_ID,
            ),
        )
        tokens.save(session)
    }

    fun signOut() = tokens.clearTokens()

    // ---- Endpoints

    suspend fun currentUser(): T3kUser = get("/api/v1/user")

    suspend fun favorited(page: Int = 1): Paginated<Tone> = get("/api/v1/tones/favorited?page=$page&page_size=25")
    suspend fun downloaded(page: Int = 1): Paginated<Tone> = get("/api/v1/tones/downloaded?page=$page&page_size=25")
    suspend fun created(page: Int = 1): Paginated<Tone> = get("/api/v1/tones/created?page=$page&page_size=25")
    suspend fun trending(): List<Tone> = get<DataList<Tone>>("/api/v1/tones/trending").data
    suspend fun latest(): List<Tone> = get<DataList<Tone>>("/api/v1/tones/latest").data

    suspend fun tone(id: String): Tone = get("/api/v1/tones/$id")

    // ---- Full-catalog search (approved for this app by TONE3000 by email, Nov 2026; see README).

    suspend fun search(filters: SearchFilters, page: Int): Paginated<Tone> {
        requireFullSearch()
        return get("/api/v1/tones/search?" + filters.toQuery(page))
    }

    suspend fun tags(query: String): List<NamedCount> {
        requireFullSearch()
        return get<Paginated<NamedCount>>("/api/v1/tags?page_size=25&query=${enc(query)}").data
    }

    suspend fun makes(query: String): List<NamedCount> {
        requireFullSearch()
        return get<Paginated<NamedCount>>("/api/v1/makes?page_size=25&query=${enc(query)}").data
    }

    private fun requireFullSearch() {
        if (!T3kConfig.FULL_CATALOG_SEARCH) throw T3kException("Full catalog search is turned off in this build.")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * All playable models of a tone. TONE3000 hides newer "A2" models unless asked, and
     * this app supports them (they're also lighter on the CPU), so fetch both and merge.
     */
    suspend fun models(tone: Tone): List<ToneModel> {
        val legacy = get<Paginated<ToneModel>>("/api/v1/models?tone_id=${tone.id}&page_size=300").data
        if (tone.format != Tone.FORMAT_NAM || tone.a2ModelsCount == 0) return legacy
        val a2 = get<Paginated<ToneModel>>("/api/v1/models?tone_id=${tone.id}&page_size=300&architecture=2").data
        return (a2 + legacy).distinctBy { it.id }
    }

    /** Downloads a model file (needs the access token). */
    suspend fun download(model: ToneModel, dest: File) = withContext(Dispatchers.IO) {
        val conn = open(model.modelUrl, authorized = true)
        try {
            checkStatus(conn)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw T3kException("Could not save the downloaded file.")
        } finally {
            conn.disconnect()
        }
    }

    // ---- Plumbing

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        var conn = open(T3kConfig.BASE_URL + path, authorized = true)
        try {
            if (conn.responseCode == 401) {
                // Token may have been revoked early: refresh once and retry.
                conn.disconnect()
                refresh(force = true)
                conn = open(T3kConfig.BASE_URL + path, authorized = true)
            }
            checkStatus(conn)
            decode<T>(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Parses a response; turns format surprises into a readable message (details go to Logcat). */
    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        Log.e("T3kClient", "Unexpected TONE3000 response", e)
        throw T3kException("TONE3000 sent data this app didn't understand. Please update the app or try again later.")
    }

    private suspend inline fun <reified T> postForm(path: String, form: Map<String, String>): T =
        withContext(Dispatchers.IO) {
            val conn = (URL(T3kConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = form.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                checkStatus(conn)
                decode<T>(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun open(url: String, authorized: Boolean): HttpURLConnection {
        val token = if (authorized) validAccessToken() else null
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private suspend fun validAccessToken(): String {
        val token = tokens.accessToken
        if (token != null && System.currentTimeMillis() < tokens.expiresAtMillis) return token
        refresh(force = false)
        return tokens.accessToken ?: throw SignedOutException()
    }

    private suspend fun refresh(force: Boolean) = refreshLock.withLock {
        // Another request may have refreshed while we waited for the lock.
        if (!force && tokens.accessToken != null && System.currentTimeMillis() < tokens.expiresAtMillis) {
            return@withLock
        }
        val refreshToken = tokens.refreshToken ?: throw SignedOutException()
        try {
            val session: Session = postForm(
                "/api/v1/oauth/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to T3kConfig.CLIENT_ID,
                ),
            )
            tokens.save(session)
        } catch (e: T3kException) {
            if (e.status == 400 || e.status == 401) {
                tokens.clearTokens() // refresh token expired: sign in again
                throw SignedOutException()
            }
            throw e
        }
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val message = when (code) {
            401 -> "TONE3000 sign-in expired."
            403 -> "TONE3000 didn't allow this request."
            404 -> "That tone isn't available (it may be private or deleted)."
            429 -> "Too many requests to TONE3000. Wait a minute and try again."
            in 500..599 -> "TONE3000 is having trouble right now. Try again later."
            else -> "TONE3000 request failed (HTTP $code)."
        }
        throw T3kException(message, code)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}
