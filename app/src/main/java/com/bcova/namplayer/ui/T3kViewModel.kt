package com.bcova.namplayer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bcova.namplayer.t3k.AuthCallback
import com.bcova.namplayer.t3k.AuthPurpose
import com.bcova.namplayer.t3k.NamedCount
import com.bcova.namplayer.t3k.SearchFilters
import com.bcova.namplayer.t3k.PendingAuth
import com.bcova.namplayer.t3k.Pkce
import com.bcova.namplayer.t3k.SignedOutException
import com.bcova.namplayer.t3k.T3kClient
import com.bcova.namplayer.t3k.T3kUser
import com.bcova.namplayer.t3k.TargetSlot
import com.bcova.namplayer.t3k.TokenStore
import com.bcova.namplayer.t3k.Tone
import com.bcova.namplayer.t3k.ToneModel
import com.bcova.namplayer.t3k.buildAuthorizeUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Screens of the app. The player is always at the bottom of the stack. */
sealed interface Screen {
    data object Player : Screen
    data class Partnership(val target: TargetSlot) : Screen
    data class Library(val target: TargetSlot) : Screen
    data class ToneDetail(val toneId: String, val target: TargetSlot) : Screen
    data class Search(val target: TargetSlot) : Screen
}

/** Which list the tag/make picker is showing. */
enum class SuggestionKind { TAGS, MAKES }

enum class LibraryTab(val label: String) {
    FAVORITES("Favorites"),
    DOWNLOADED("Downloaded"),
    CREATED("Created"),
    TRENDING("Trending"),
    LATEST("Latest"),
}

/** State of the tone detail screen. */
class ToneDetailState {
    var tone by mutableStateOf<Tone?>(null)
    var models by mutableStateOf<List<ToneModel>>(emptyList())
    var selectedModel by mutableStateOf<ToneModel?>(null)
    var loading by mutableStateOf(true)
    var installing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Everything about TONE3000: sign-in, tone lists, tone details, and navigation between screens. */
class T3kViewModel(app: Application) : AndroidViewModel(app) {

    private val tokenStore = TokenStore(app)
    private val client = T3kClient(tokenStore)

    // ---- Navigation
    val backStack = mutableStateListOf<Screen>(Screen.Player)
    val screen: Screen get() = backStack.last()

    fun push(s: Screen) {
        backStack.add(s)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    private fun replaceTop(s: Screen) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(s)
    }

    private fun popToPlayer() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // ---- Account
    var signedIn by mutableStateOf(tokenStore.isSignedIn)
        private set
    var user by mutableStateOf<T3kUser?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun dismissMessage() {
        message = null
    }

    // ---- Library
    var tab by mutableStateOf(LibraryTab.FAVORITES)
        private set
    val tones = mutableStateListOf<Tone>()
    var listLoading by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    private var page = 1
    private var totalPages = 1
    private var listJob: Job? = null

    val canLoadMore: Boolean get() = tab.isPaginated && page < totalPages && !listLoading

    private val LibraryTab.isPaginated get() = this != LibraryTab.TRENDING && this != LibraryTab.LATEST

    // ---- Detail
    var detail by mutableStateOf(ToneDetailState())
        private set

    /** Entry point from a signal-chain block: "add or change the tone in this slot". */
    fun openBrowser(target: TargetSlot) {
        if (signedIn) {
            push(Screen.Library(target))
            refreshList()
            loadUser()
        } else {
            push(Screen.Partnership(target))
        }
    }

    /** Opens TONE3000 in a browser tab. SELECT_TONE also signs the user in if needed. */
    fun launchTone3000(context: Context, purpose: AuthPurpose, target: TargetSlot) {
        val pending = PendingAuth(Pkce.newVerifier(), Pkce.newState(), purpose, target)
        tokenStore.savePending(pending)
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, buildAuthorizeUrl(pending))
    }

    /** Called by MainActivity when TONE3000 redirects back to the app. */
    fun handleCallback(uri: Uri) {
        val callback = AuthCallback.parse(uri)
        val pending = tokenStore.takePending()
        if (pending == null || callback.state != pending.state) {
            message = "Sign-in link didn't match. Please try again."
            return
        }
        if (callback.error != null) {
            if (callback.error != "access_denied") message = "TONE3000 sign-in failed: ${callback.error}"
            return
        }
        val code = callback.code ?: return // closed before signing in: nothing to do
        viewModelScope.launch {
            try {
                client.exchangeCode(code, pending.verifier)
                signedIn = true
                loadUser()
                val library = Screen.Library(pending.target)
                // After sign-in, the tone list is the first view (TONE3000 design rule 4).
                if (screen is Screen.Partnership) replaceTop(library)
                else if (screen !is Screen.Library) push(library)
                if (callback.toneId != null && !callback.canceled) {
                    push(Screen.ToneDetail(callback.toneId, pending.target))
                    openDetail(callback.toneId)
                }
                refreshList()
            } catch (e: Exception) {
                message = e.message ?: "Sign-in failed."
            }
        }
    }

    fun signOut() {
        client.signOut()
        signedIn = false
        user = null
        tones.clear()
        popToPlayer()
    }

    private fun loadUser() {
        if (user != null) return
        viewModelScope.launch {
            user = runCatching { client.currentUser() }.getOrNull()
        }
    }

    fun selectTab(newTab: LibraryTab) {
        if (tab == newTab) return
        tab = newTab
        refreshList()
    }

    fun refreshList() {
        page = 1
        totalPages = 1
        tones.clear()
        fetchPage()
    }

    fun loadMore() {
        if (!canLoadMore) return
        page++
        fetchPage()
    }

    private fun fetchPage() {
        listJob?.cancel()
        val forTab = tab
        listJob = viewModelScope.launch {
            listLoading = true
            listError = null
            try {
                val newTones = when (forTab) {
                    LibraryTab.TRENDING -> client.trending()
                    LibraryTab.LATEST -> client.latest()
                    else -> {
                        val result = when (forTab) {
                            LibraryTab.FAVORITES -> client.favorited(page)
                            LibraryTab.DOWNLOADED -> client.downloaded(page)
                            else -> client.created(page)
                        }
                        totalPages = result.totalPages
                        result.data
                    }
                }
                tones.addAll(newTones.filter { new -> tones.none { it.id == new.id } })
            } catch (e: SignedOutException) {
                handleSignedOut(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // switched tabs: not an error
            } catch (e: Exception) {
                listError = e.message ?: "Couldn't load tones."
            } finally {
                listLoading = false
            }
        }
    }

    fun openTone(tone: Tone, target: TargetSlot) {
        push(Screen.ToneDetail(tone.id, target))
        openDetail(tone.id, tone)
    }

    private fun openDetail(toneId: String, known: Tone? = null) {
        val state = ToneDetailState().apply { tone = known }
        detail = state
        viewModelScope.launch {
            try {
                val tone = known ?: client.tone(toneId)
                state.tone = tone
                if (!tone.isSupported) {
                    state.error = "${tone.formatLabel} tones aren't supported yet. This app plays NAM and IR tones."
                } else {
                    val models = client.models(tone)
                    state.models = models
                    state.selectedModel = models.firstOrNull()
                    if (models.isEmpty()) state.error = "This tone has no downloadable models."
                }
            } catch (e: SignedOutException) {
                handleSignedOut(e)
            } catch (e: Exception) {
                state.error = e.message ?: "Couldn't load this tone."
            } finally {
                state.loading = false
            }
        }
    }

    fun retryDetail() {
        val s = screen
        if (s is Screen.ToneDetail) openDetail(s.toneId)
    }

    /**
     * Downloads the chosen model and loads it into the engine via [install].
     * [install] returns null on success or an error message.
     */
    fun installSelected(install: suspend (file: File, tone: Tone, model: ToneModel) -> String?) {
        val state = detail
        val tone = state.tone ?: return
        val model = state.selectedModel ?: return
        viewModelScope.launch {
            state.installing = true
            state.error = null
            try {
                val ext = if (tone.format == Tone.FORMAT_IR) "wav" else "nam"
                val file = File(getApplication<Application>().cacheDir, "t3k/${model.id}.$ext")
                if (!file.exists()) client.download(model, file)
                val error = install(file, tone, model)
                if (error == null) popToPlayer() else state.error = error
            } catch (e: SignedOutException) {
                handleSignedOut(e)
            } catch (e: Exception) {
                state.error = e.message ?: "Download failed."
            } finally {
                state.installing = false
            }
        }
    }

    // ---- Full-catalog search (only when T3kConfig.FULL_CATALOG_SEARCH is on)

    var filters by mutableStateOf(SearchFilters())
        private set
    val results = mutableStateListOf<Tone>()
    var resultsTotal by mutableIntStateOf(0)
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchPage = 1
    private var searchTotalPages = 1
    private var searchJob: Job? = null

    val canSearchMore: Boolean get() = searchPage < searchTotalPages && !searchLoading

    fun openSearch(target: TargetSlot) {
        push(Screen.Search(target))
        val wanted = if (target == TargetSlot.CAB) Tone.FORMAT_IR else Tone.FORMAT_NAM
        // Start with the right format for the block being filled; keep earlier searches otherwise.
        if (results.isEmpty() || filters.format != wanted) updateFilters(filters.copy(format = wanted))
    }

    /** Typing: wait until the user pauses, to stay well under the 100 requests/minute limit. */
    fun setQuery(query: String) = updateFilters(filters.copy(query = query), debounceMs = 450)

    fun updateFilters(newFilters: SearchFilters, debounceMs: Long = 0) {
        filters = newFilters
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            searchPage = 1
            searchTotalPages = 1
            results.clear()
            fetchSearchPage()
        }
    }

    fun clearFilters() = updateFilters(SearchFilters(query = filters.query, sort = filters.sort, format = filters.format))

    fun searchMore() {
        if (!canSearchMore) return
        searchPage++
        searchJob = viewModelScope.launch { fetchSearchPage() }
    }

    fun retrySearch() = updateFilters(filters)

    private suspend fun fetchSearchPage() {
        searchLoading = true
        searchError = null
        try {
            val page = client.search(filters, searchPage)
            searchTotalPages = page.totalPages
            resultsTotal = page.total
            results.addAll(page.data.filter { new -> results.none { it.id == new.id } })
        } catch (e: SignedOutException) {
            handleSignedOut(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: "Search failed."
        } finally {
            searchLoading = false
        }
    }

    // Tag / make picker suggestions
    val suggestions = mutableStateListOf<NamedCount>()
    var suggestionsLoading by mutableStateOf(false)
        private set
    private var suggestionJob: Job? = null

    fun loadSuggestions(kind: SuggestionKind, query: String) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(300)
            suggestionsLoading = true
            try {
                val list = if (kind == SuggestionKind.TAGS) client.tags(query) else client.makes(query)
                suggestions.clear()
                suggestions.addAll(list)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                suggestions.clear()
            } finally {
                suggestionsLoading = false
            }
        }
    }

    private fun handleSignedOut(e: SignedOutException) {
        signedIn = false
        user = null
        message = e.message
        val target = when (val s = screen) {
            is Screen.Library -> s.target
            is Screen.ToneDetail -> s.target
            is Screen.Search -> s.target
            else -> TargetSlot.AMP
        }
        popToPlayer()
        push(Screen.Partnership(target))
    }
}
