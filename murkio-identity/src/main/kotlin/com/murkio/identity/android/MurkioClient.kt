package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.codeChallenge
import com.murkio.identity.android.internal.fnv1a32
import com.murkio.identity.android.internal.normalizeLocale
import com.murkio.identity.android.internal.obj
import com.murkio.identity.android.internal.randomString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Opens a URL outside the app (the browser). Returns false when nothing could open it. */
public fun interface MurkioUrlOpener {
    public fun open(url: String): Boolean
}

/** A 2FA challenge left by a social sign-in return, for the sign-in screen to continue. */
internal class PendingMfa(val mfa: MfaChallenge, val keepLogged: Boolean)

/**
 * The client of one application: the session, the profile and the published
 * canvas. Apps get it from [Murkio.client] after [Murkio.initialize]; the
 * composables use that one unless they are given another.
 */
public class MurkioClient internal constructor(
    /** The application's publishable key. */
    public val publishableKey: String,
    apiUrl: String?,
    private val localeOption: String?,
    /** The https link a social sign-in comes back to, registered in the application's redirect URLs. */
    public val oauthRedirectUrl: String?,
    private val storage: MurkioStorage,
    transport: MurkioTransport,
    private val opener: MurkioUrlOpener,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    isForeground: () -> Boolean,
) {
    init {
        require(publishableKey.startsWith("pk_")) {
            "publishableKey must be a publishable key (pk_...). Never put a secret key (sk_) in an app."
        }
    }

    /** The hash of the publishable key that names the storage records. */
    public val keyHash: String = fnv1a32(publishableKey)

    /** The API client, for calls the SDK does not wrap. */
    public val api: ApiClient = ApiClient(publishableKey, apiUrl, transport)

    internal val sessions: SessionManager = SessionManager(api, storage, keyHash, scope, io, now, isForeground)

    private val lock = Any()
    private var starting: Deferred<Unit>? = null
    private var bootstrap: CachedBootstrap? = null
    private var bootstrapping: Deferred<ClientBootstrap>? = null
    private var user: CachedUser? = null
    private var pendingMfa: PendingMfa? = null
    private var oauthError: MurkioException? = null
    private val oauthSignal = MutableStateFlow(0)

    /** The signed-in session, or null. Collect it to follow sign-ins and sign-outs. */
    public val session: StateFlow<MurkioSession?> get() = sessions.session

    public val currentSession: MurkioSession? get() = sessions.current

    public val isSignedIn: Boolean get() = sessions.current != null

    /** True when the app configured where a social sign-in comes back to. */
    public val oauthEnabled: Boolean get() = oauthRedirectUrl != null

    /** The locale option given at creation, if any. */
    internal val configuredLocale: String? get() = localeOption

    /** The language the SDK speaks to the API (emails) and, unless a composable says otherwise, on the built-in screens. */
    public val locale: String get() = normalizeLocale(localeOption ?: Locale.getDefault().toLanguageTag())

    /** Changes when a social sign-in came back and left something for the sign-in screen. */
    internal val oauthReturn: StateFlow<Int> = oauthSignal.asStateFlow()

    /** Restores the session of the last run. Everything that needs the session waits for it. */
    public suspend fun start() {
        val shared = synchronized(lock) {
            starting ?: scope.async { sessions.start() }.also { starting = it }
        }
        shared.await()
    }

    /** A valid access token for calling your own API, or null. Refreshes it when needed. */
    public suspend fun getToken(): String? {
        start()
        return sessions.getToken()
    }

    /** The signed-in user's profile (email, roles, 2FA), cached for a minute per session. */
    public suspend fun getUser(): MurkioUser? {
        val token = getToken() ?: return null
        val sessionId = currentSession?.sessionId ?: return null
        val entry = synchronized(lock) {
            val cached = user
            if (cached != null && cached.sessionId == sessionId && now() - cached.at <= USER_TTL_MS) {
                cached
            } else {
                CachedUser(sessionId, now(), scope.async { api.me(token) }).also { user = it }
            }
        }
        try {
            return entry.value.await()
        } catch (error: MurkioException) {
            synchronized(lock) { if (user === entry) user = null }
            throw error
        }
    }

    public suspend fun signOut() {
        start()
        synchronized(lock) { user = null }
        sessions.signOut()
    }

    /**
     * The application id, its public config and the published canvas. It is
     * fetched once, kept for a minute and then revalidated with its ETag, so
     * most calls cost a 304 at most.
     */
    public suspend fun bootstrap(): ClientBootstrap {
        val shared = synchronized(lock) {
            val cached = bootstrap
            if (cached != null && now() - cached.at < BOOTSTRAP_TTL_MS) return cached.data
            bootstrapping ?: scope.async {
                try {
                    loadBootstrap(cached)
                } finally {
                    synchronized(lock) { bootstrapping = null }
                }
            }.also { bootstrapping = it }
        }
        return shared.await()
    }

    private suspend fun loadBootstrap(cached: CachedBootstrap?): ClientBootstrap {
        try {
            val response = api.bootstrap(cached?.etag)
            val data = (if (response.notModified && cached != null) cached.data else response.data)
                ?: throw MurkioException("unknown", 0, message = "empty bootstrap")
            synchronized(lock) { bootstrap = CachedBootstrap(response.etag ?: cached?.etag, data, now()) }
            return data
        } catch (error: MurkioException) {
            if (cached != null) return cached.data
            throw error
        }
    }

    /**
     * Starts a social sign-in: the browser opens the provider, which comes
     * back to [oauthRedirectUrl]. Give that link to [handleRedirect].
     */
    public suspend fun startOAuth(provider: String, keepLogged: Boolean) {
        val redirect = oauthRedirectUrl
            ?: throw MurkioException("oauth.provider_disabled", 0, message = "oauthRedirectUrl is not configured")
        val verifier = randomString(64)
        val state = randomString(32)
        val url = api.authorizeOAuth(provider, redirect, state, codeChallenge(verifier))
        if (!url.startsWith("https://")) {
            throw MurkioException("oauth.server_error", 0, message = "unexpected authorization url")
        }
        val attempt = Json.write(linkedMapOf("verifier" to verifier, "state" to state, "keepLogged" to keepLogged))
        withContext(io) { storage.write(oauthKey, attempt) }
        if (!runCatching { opener.open(url) }.getOrDefault(false)) {
            withContext(io) { runCatching { storage.delete(oauthKey) } }
            throw MurkioException("oauth.server_error", 0, message = "could not open the browser")
        }
    }

    /**
     * Finishes a social sign-in with the link the app was opened with (the
     * `data` of the activity's intent). Returns false when the link is not the
     * answer to a sign-in this app started, so the app can handle it as any
     * other deep link.
     */
    public suspend fun handleRedirect(link: String): Boolean {
        val query = queryOf(link)
        val state = query["state"]
        if (state.isNullOrEmpty()) return false

        val attempt = withContext(io) { runCatching { Json.parseOrNull(storage.read(oauthKey)) }.getOrNull() }.obj()
        val verifier = attempt?.get("verifier") as? String
        if (attempt == null || verifier == null || attempt["state"] != state) return false
        withContext(io) { runCatching { storage.delete(oauthKey) } }
        val keepLogged = attempt["keepLogged"] == true

        try {
            val error = query["error"]
            val code = query["code"]
            if (error != null || code == null) throw MurkioException("oauth.${error ?: "server_error"}", 0)
            when (val result = api.exchangeOAuthCode(code, verifier)) {
                is LoginResult.Session -> sessions.setSession(result.tokens, keepLogged)
                is LoginResult.Mfa -> {
                    synchronized(lock) { pendingMfa = PendingMfa(result.mfa, keepLogged) }
                    oauthSignal.value++
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            synchronized(lock) { oauthError = MurkioException.from(error) }
            oauthSignal.value++
        }
        return true
    }

    /** The 2FA challenge left by a social sign-in return, once. */
    internal fun takePendingMfa(): PendingMfa? = synchronized(lock) { pendingMfa.also { pendingMfa = null } }

    /** The error of a social sign-in return, once. */
    internal fun takeOAuthError(): MurkioException? = synchronized(lock) { oauthError.also { oauthError = null } }

    /** Opens a link outside the app (the browser, or the authenticator app for an `otpauth://` link). */
    public fun openUrl(url: String): Boolean = runCatching { opener.open(url) }.getOrDefault(false)

    /** Stops the timers and requests of this client. For a client that is being replaced. */
    internal fun close() {
        sessions.close()
        scope.cancel()
    }

    /** Called when the app comes to the foreground. */
    internal fun resumed() {
        scope.launch { runCatching { sessions.refreshIfDue() } }
    }

    private val oauthKey: String get() = "murkio.$keyHash.oauth"

    private class CachedBootstrap(val etag: String?, val data: ClientBootstrap, val at: Long)

    private class CachedUser(val sessionId: String, val at: Long, val value: Deferred<MurkioUser>)

    private companion object {
        const val BOOTSTRAP_TTL_MS = 60_000L
        const val USER_TTL_MS = 60_000L
    }
}

/** The query parameters of a link, percent-decoded. Written by hand so it behaves the same in a unit test and on a phone. */
internal fun queryOf(link: String): Map<String, String> {
    val start = link.indexOf('?')
    if (start < 0) return emptyMap()
    val end = link.indexOf('#', start).let { if (it < 0) link.length else it }
    val result = LinkedHashMap<String, String>()
    for (pair in link.substring(start + 1, end).split('&')) {
        if (pair.isEmpty()) continue
        val separator = pair.indexOf('=')
        val name = if (separator < 0) pair else pair.substring(0, separator)
        val value = if (separator < 0) "" else pair.substring(separator + 1)
        result[percentDecode(name)] = percentDecode(value)
    }
    return result
}

private fun percentDecode(text: String): String =
    runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
