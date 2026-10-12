package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.fnv1a32
import com.murkio.identity.android.internal.obj
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private var time = 1_900_000_000_000L
    private lateinit var storage: MurkioMemoryStorage
    private val key = "murkio.${fnv1a32("pk_test")}.session"

    @Before
    fun setUp() {
        time = 1_900_000_000_000L
        storage = MurkioMemoryStorage()
    }

    @After
    fun tearDown() = closeClients()

    private suspend fun TestScope.start(api: FakeApi, on: MurkioStorage = storage, foreground: Boolean = false): MurkioClient =
        newClient(api, storage = on, now = { time }, foreground = foreground).also { it.start() }

    private suspend fun signIn(client: MurkioClient, keepLogged: Boolean = true, id: String = "session-1"): MurkioSession =
        client.sessions.setSession(tokenPair(sessionId = id, nowMs = time), keepLogged)

    private fun refreshing(): Pair<String, suspend (Call) -> Answer> = route("POST /v1/token/refresh") { Answer.data(tokens(nowMs = time)) }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (error: MurkioException) {
            assertEquals(code, error.code)
        }
    }

    @Test
    fun storesOneRecordNamedAfterThePublishableKey() = runTest {
        val client = start(FakeApi())
        val session = signIn(client)

        val record = Json.parse(storage.read(key)!!).obj()!!
        assertEquals(setOf("accessToken", "refreshToken", "expiresAt", "sessionId", "ephemeral"), record.keys)
        assertEquals(session.refreshToken, record["refreshToken"])
        assertEquals(false, record["ephemeral"])
        assertEquals((time + 900_000).toDouble(), record["expiresAt"])
        assertTrue(client.isSignedIn)
        assertEquals("user-1", session.userId)
        assertEquals(listOf("user"), session.roles)
    }

    @Test
    fun getTokenReturnsTheCurrentTokenWhileItIsFresh() = runTest {
        val api = FakeApi()
        val client = start(api)
        val session = signIn(client)

        time += 869_000
        assertEquals(session.accessToken, client.getToken())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun getTokenRefreshesATokenAboutToExpireAndKeepsTheChoice() = runTest {
        val api = FakeApi(mutableMapOf(refreshing()))
        val client = start(api)
        val first = signIn(client, keepLogged = false)

        time += 880_000
        val token = client.getToken()

        assertEquals(mapOf("refresh_token" to first.refreshToken), api.calls.single().body)
        assertNotEquals(first.accessToken, token)
        assertTrue(client.currentSession!!.ephemeral)
        assertEquals(client.currentSession!!.refreshToken, Json.parse(storage.read(key)!!).obj()!!["refreshToken"])
    }

    @Test
    fun callersThatAskAtTheSameTimeShareOneRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/token/refresh") {
                    gate.await()
                    Answer.data(tokens(nowMs = time))
                },
            ),
        )
        val client = start(api)
        signIn(client)
        time += 890_000

        val pending = List(3) { async { client.getToken() } }
        runCurrent()
        gate.complete(Unit)
        val results = pending.awaitAll()

        assertEquals(1, api.to("/v1/token/refresh").size)
        assertEquals(1, results.toSet().size)
    }

    @Test
    fun aCancelledCallerDoesNotCancelTheRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/token/refresh") {
                    gate.await()
                    Answer.data(tokens(nowMs = time))
                },
            ),
        )
        val client = start(api)
        val first = signIn(client)
        time += 890_000

        val impatient = async { client.getToken() }
        runCurrent()
        impatient.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(impatient.isCancelled)
        assertNotEquals(first.refreshToken, client.currentSession!!.refreshToken)
        assertEquals(client.currentSession!!.accessToken, client.getToken())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aSessionEndedByTheApiSignsTheUserOut() = runTest {
        for (code in listOf("rsb-02", "rsb-03", "rsb-05")) {
            storage = MurkioMemoryStorage()
            val api = FakeApi(mutableMapOf(route("POST /v1/token/refresh") { Answer.error(401, code) }))
            val client = start(api)
            signIn(client)
            time += 890_000

            assertNull(code, client.getToken())
            assertFalse(code, client.isSignedIn)
            assertTrue(code, storage.keys().isEmpty())
            assertNull(code, client.session.value)
        }
    }

    @Test
    fun aNetworkErrorKeepsTheSessionAndIsReported() = runTest {
        val api = FakeApi(mutableMapOf(route("POST /v1/token/refresh") { throw java.io.IOException("offline") }))
        val client = start(api)
        val session = signIn(client)
        time += 890_000

        expectError("network") { client.getToken() }
        assertEquals(session, client.currentSession)
        assertTrue(storage.keys().isNotEmpty())

        client.sessions.refreshIfDue()
        assertEquals(session, client.currentSession)
    }

    @Test
    fun anotherErrorOnRefreshKeepsTheSessionToo() = runTest {
        val api = FakeApi(mutableMapOf(route("POST /v1/token/refresh") { Answer.error(429, "rsb-01") }))
        val client = start(api)
        signIn(client)
        time += 890_000

        expectError("rsb-01") { client.getToken() }
        assertTrue(client.isSignedIn)
    }

    @Test
    fun aKeptSessionIsRestoredByTheNextRunOfTheApp() = runTest {
        val session = signIn(start(FakeApi()), id = "kept-session")

        val api = FakeApi()
        val second = start(api)
        assertEquals(session, second.currentSession)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun anEphemeralSessionLeftByAPreviousRunIsLoggedOutAndDeleted() = runTest {
        storage.write(
            key,
            Json.write(
                mapOf(
                    "accessToken" to "old-access",
                    "refreshToken" to "old-refresh",
                    "expiresAt" to time + 300_000,
                    "sessionId" to "session-of-a-previous-run",
                    "ephemeral" to true,
                ),
            ),
        )
        val api = FakeApi(mutableMapOf(route("POST /v1/logout") { Answer(204) }))

        val client = start(api)
        runCurrent()

        assertFalse(client.isSignedIn)
        assertTrue(storage.keys().isEmpty())
        assertEquals("/v1/logout", api.calls.single().path)
        assertEquals(mapOf("refresh_token" to "old-refresh"), api.calls.single().body)
    }

    @Test
    fun anEphemeralSessionOfThisRunSurvivesASecondClient() = runTest {
        val session = signIn(start(FakeApi()), keepLogged = false, id = "this-run")

        val api = FakeApi()
        val second = start(api)
        assertEquals(session, second.currentSession)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aRecordThatCannotBeReadMeansNobodyIsSignedIn() = runTest {
        storage.write(key, "{not json")
        assertFalse(start(FakeApi()).isSignedIn)

        storage.write(key, Json.write(mapOf("accessToken" to "only-this")))
        assertFalse(start(FakeApi()).isSignedIn)

        val broken = object : MurkioStorage {
            override fun read(key: String): String? = throw IllegalStateException("locked")
            override fun write(key: String, value: String) = throw IllegalStateException("locked")
            override fun delete(key: String) = throw IllegalStateException("locked")
        }
        val client = start(FakeApi(), on = broken)
        assertFalse(client.isSignedIn)
        signIn(client)
        assertTrue("works for this run of the app", client.isSignedIn)
    }

    @Test
    fun signingOutDeletesTheRecordBeforeCallingTheApi() = runTest {
        var storedDuringLogout = true
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/logout") {
                    storedDuringLogout = storage.keys().isNotEmpty()
                    Answer.error(500, "bss-00")
                },
            ),
        )
        val client = start(api)
        val session = signIn(client)

        client.signOut()

        assertFalse(storedDuringLogout)
        assertFalse(client.isSignedIn)
        assertEquals(mapOf("refresh_token" to session.refreshToken), api.calls.single().body)
    }

    @Test
    fun signingOutDuringARefreshDoesNotBringTheSessionBack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val renewed = tokens(nowMs = time)
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/token/refresh") {
                    gate.await()
                    Answer.data(renewed)
                },
                route("POST /v1/logout") { Answer(204) },
            ),
        )
        val client = start(api)
        signIn(client)
        time += 890_000

        val refreshing = async { client.getToken() }
        runCurrent()
        client.signOut()
        gate.complete(Unit)

        assertNull(refreshing.await())
        runCurrent()
        assertFalse(client.isSignedIn)
        assertTrue(storage.keys().isEmpty())
        assertTrue(api.to("/v1/logout").any { it.body.obj()!!["refresh_token"] == renewed["refresh_token"] })
    }

    @Test
    fun aTokenRenewedElsewhereIsAdoptedInsteadOfRefreshedAgain() = runTest {
        val api = FakeApi()
        val client = start(api)
        val session = signIn(client)
        time += 890_000

        // Another process of the app already used the refresh token.
        val renewed = MurkioSession("renewed-access", "renewed-refresh", time + 900_000, session.sessionId, false)
        storage.write(key, renewed.toJson())

        assertEquals("renewed-access", client.getToken())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun refreshIfDueOnlyCallsTheApiInsideTheMargin() = runTest {
        val api = FakeApi(mutableMapOf(refreshing()))
        val client = start(api)
        signIn(client)

        time += 800_000
        client.sessions.refreshIfDue()
        assertTrue(api.calls.isEmpty())

        time += 20_000
        client.sessions.refreshIfDue()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aRestoredSessionAboutToExpireIsRenewedOnStart() = runTest {
        signIn(start(FakeApi()), id = "old-but-kept")
        time += 20 * 60_000

        val api = FakeApi(
            mutableMapOf(route("POST /v1/token/refresh") { Answer.data(tokens(nowMs = time, sessionId = "old-but-kept")) }),
        )
        val second = start(api, foreground = true)
        runCurrent()

        assertEquals(1, api.calls.size)
        assertTrue(second.currentSession!!.expiresAt > time)
    }
}
