package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.obj
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The signed-in session: the tokens and when the access token expires. */
public class MurkioSession(
    public val accessToken: String,
    public val refreshToken: String,
    /** Milliseconds since the epoch. */
    public val expiresAt: Long,
    public val sessionId: String,
    /** "Keep me signed in" was off: the session does not survive the app. */
    public val ephemeral: Boolean,
) {
    /** The claims of the access token, read without checking its signature. */
    public val claims: AccessClaims? get() = decodeClaims(accessToken)

    public val userId: String? get() = claims?.subject

    public val roles: List<String> get() = claims?.roles ?: emptyList()

    internal fun toJson(): String = Json.write(
        linkedMapOf(
            "accessToken" to accessToken,
            "refreshToken" to refreshToken,
            "expiresAt" to expiresAt,
            "sessionId" to sessionId,
            "ephemeral" to ephemeral,
        ),
    )

    override fun equals(other: Any?): Boolean = other is MurkioSession &&
        other.accessToken == accessToken &&
        other.refreshToken == refreshToken &&
        other.expiresAt == expiresAt &&
        other.sessionId == sessionId &&
        other.ephemeral == ephemeral

    override fun hashCode(): Int = arrayOf<Any>(accessToken, refreshToken, expiresAt, sessionId, ephemeral).contentHashCode()

    internal companion object {
        fun fromJson(text: String?): MurkioSession? {
            val map = Json.parseOrNull(text).obj() ?: return null
            val access = map["accessToken"] as? String ?: return null
            val refresh = map["refreshToken"] as? String ?: return null
            val expires = map["expiresAt"] as? Double ?: return null
            val session = map["sessionId"] as? String ?: return null
            return MurkioSession(access, refresh, expires.toLong(), session, map["ephemeral"] == true)
        }
    }
}

/**
 * SessionManager keeps the user's tokens.
 *
 * - One record in the storage, named after the publishable key.
 * - Refreshes run one at a time: the refresh token rotates on every use and
 *   the API revokes the whole session when a used one shows up again
 *   (rsb-03), so concurrent callers share one request. That request runs in
 *   the SDK's own scope: a caller that is cancelled does not cancel it, or
 *   the rotated token would be lost with the answer.
 * - "Keep me signed in" off marks the record ephemeral: the next start of the
 *   app finds it, logs it out and deletes it.
 * - A network error keeps the session; only the API saying the session is
 *   over signs the user out.
 */
internal class SessionManager(
    private val api: ApiClient,
    private val storage: MurkioStorage,
    private val keyHash: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val isForeground: () -> Boolean,
) {
    private val state = MutableStateFlow<MurkioSession?>(null)
    private val lock = Any()
    private var inflight: Deferred<MurkioSession?>? = null
    private var timer: Job? = null
    private val key = "murkio.$keyHash.session"

    val session: StateFlow<MurkioSession?> = state.asStateFlow()

    val current: MurkioSession? get() = state.value

    /** Restores the session of a previous run and keeps it fresh. */
    suspend fun start() {
        val stored = read()
        if (stored != null && stored.ephemeral && !alive.contains("$keyHash:${stored.sessionId}")) {
            scope.launch { runCatching { api.logout(stored.refreshToken) } }
            delete()
            set(null)
            return
        }
        set(stored)
        if (isForeground()) scope.launch { refreshIfDue() }
    }

    suspend fun setSession(tokens: TokenPair, keepLogged: Boolean): MurkioSession {
        val session = MurkioSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = now() + tokens.expiresIn * 1000,
            sessionId = tokens.sessionId,
            ephemeral = !keepLogged,
        )
        alive.add("$keyHash:${session.sessionId}")
        set(session)
        write(session)
        return session
    }

    /**
     * A valid access token, refreshed first when it expires within
     * [minValidityMs]. Null when nobody is signed in or the session is over.
     */
    suspend fun getToken(minValidityMs: Long = 30_000): String? {
        val session = current ?: return null
        if (session.expiresAt - now() > minValidityMs) return session.accessToken
        return refresh()?.accessToken
    }

    /** Renews the tokens, once for every caller that asks at the same time. Network errors keep the session and throw. */
    suspend fun refresh(): MurkioSession? {
        val shared = synchronized(lock) {
            inflight ?: scope.async {
                try {
                    doRefresh()
                } finally {
                    synchronized(lock) { inflight = null }
                }
            }.also { inflight = it }
        }
        return shared.await()
    }

    private suspend fun doRefresh(): MurkioSession? {
        var before = current ?: return null

        // Another process of the app may already have used the refresh token.
        val stored = read()
        if (stored != null && stored.sessionId == before.sessionId && stored.refreshToken != before.refreshToken) {
            before = stored
            set(stored)
        }
        if (before.expiresAt - now() > REFRESH_MARGIN_MS) return before

        val pair = try {
            api.refresh(before.refreshToken)
        } catch (error: MurkioException) {
            if (error.code in ENDED_CODES) {
                clear()
                return null
            }
            throw error
        }

        // The user signed out while the request was on its way: the new tokens must not bring the session back.
        val latest = current
        if (latest == null) {
            scope.launch { runCatching { api.logout(pair.refreshToken) } }
            return null
        }
        return setSession(pair, keepLogged = !latest.ephemeral)
    }

    /** Renews the tokens when they are about to expire; errors are left for the next [getToken] to report. */
    suspend fun refreshIfDue() {
        val session = current ?: return
        if (session.expiresAt - now() > REFRESH_MARGIN_MS) return
        try {
            refresh()
        } catch (_: MurkioException) {
            // Offline: the session stays, and getToken() will try again.
        }
    }

    suspend fun signOut() {
        val session = current
        clear()
        if (session != null) {
            try {
                api.logout(session.refreshToken)
            } catch (_: MurkioException) {
                // The record is gone either way; the API expires the session itself.
            }
        }
    }

    suspend fun clear() {
        set(null)
        delete()
    }

    fun close() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
    }

    private fun set(session: MurkioSession?) {
        state.value = session
        synchronized(lock) {
            timer?.cancel()
            timer = if (session == null) {
                null
            } else {
                // Never sooner than a few seconds: with a token that lives less than the
                // margin, "refresh when due" would otherwise call the API in a loop.
                val wait = maxOf(session.expiresAt - REFRESH_MARGIN_MS - now(), MINIMUM_WAIT_MS)
                scope.launch {
                    delay(wait)
                    if (isForeground()) refreshIfDue()
                }
            }
        }
    }

    // A storage that cannot be read only means "no session to restore", and a
    // failed write still leaves a session that works for this run of the app.
    private suspend fun read(): MurkioSession? = withContext(io) {
        runCatching { MurkioSession.fromJson(storage.read(key)) }.getOrNull()
    }

    private suspend fun write(session: MurkioSession) {
        withContext(io) { runCatching { storage.write(key, session.toJson()) } }
    }

    private suspend fun delete() {
        withContext(io) { runCatching { storage.delete(key) } }
    }

    companion object {
        /** Refresh this long before the access token expires. */
        const val REFRESH_MARGIN_MS = 90_000L
        private const val MINIMUM_WAIT_MS = 10_000L

        /** A refresh answered with one of these means the session is over. */
        private val ENDED_CODES = setOf("rsb-02", "rsb-03", "rsb-05")

        /** Sessions stored by this process. An ephemeral record that is not here was left by a previous run. */
        private val alive: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
