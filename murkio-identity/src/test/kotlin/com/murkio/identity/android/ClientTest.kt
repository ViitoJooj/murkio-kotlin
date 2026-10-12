package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.codeChallenge
import com.murkio.identity.android.internal.fnv1a32
import com.murkio.identity.android.internal.obj
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientTest {
    private var time = 1_900_000_000_000L

    @Before
    fun setUp() {
        time = 1_900_000_000_000L
    }

    @After
    fun tearDown() = closeClients()

    private fun bootstrapRoute(etag: String? = null): Pair<String, suspend (Call) -> Answer> = route("GET /v1/client") { call ->
        if (etag != null && call.headers["If-None-Match"] == etag) {
            Answer(304)
        } else {
            Answer.data(bootstrapJson(), headers = if (etag == null) emptyMap() else mapOf("etag" to etag))
        }
    }

    @Test
    fun onlyAPublishableKeyIsAccepted() = runTest {
        val error = assertThrows(IllegalArgumentException::class.java) { newClient(FakeApi(), key = "sk_live_secret") }
        assertFalse(error.message!!.contains("sk_live_secret"))
        assertThrows(IllegalArgumentException::class.java) { newClient(FakeApi(), key = "") }
    }

    @Test
    fun murkioClientExplainsWhatIsMissing() {
        Murkio.use(null)
        val error = assertThrows(IllegalStateException::class.java) { Murkio.client }
        assertTrue(error.message!!.contains(Murkio.META_PUBLISHABLE_KEY))
        assertTrue(error.message!!.contains("Murkio.initialize"))
    }

    @Test
    fun requestsCarryTheKeyAndReachTheConfiguredApi() = runTest {
        val api = FakeApi(mutableMapOf(bootstrapRoute()))
        val client = newClient(api)
        val bootstrap = client.bootstrap()

        assertEquals("app-1", bootstrap.applicationId)
        assertEquals(3, bootstrap.canvasVersion)
        assertTrue(bootstrap.config.passwordEnabled)
        assertEquals("Bearer pk_test", api.calls.single().headers["Authorization"])
        assertEquals("https://api.example.com", client.api.apiUrl)
        assertEquals(fnv1a32("pk_test"), client.keyHash)
    }

    @Test
    fun theBootstrapIsKeptForAMinuteThenRevalidatedByItsEtag() = runTest {
        val api = FakeApi(mutableMapOf(bootstrapRoute("W/\"v1\"")))
        val client = newClient(api, now = { time })

        val first = client.bootstrap()
        assertSame(first, client.bootstrap())
        assertEquals(1, api.calls.size)

        time += 61_000
        assertSame(first, client.bootstrap())
        assertEquals(2, api.calls.size)
        assertEquals("W/\"v1\"", api.calls.last().headers["If-None-Match"])

        time += 30_000
        client.bootstrap()
        assertEquals("a 304 renews the minute", 2, api.calls.size)
    }

    @Test
    fun concurrentBootstrapsShareOneRequest() = runTest {
        val api = FakeApi(mutableMapOf(bootstrapRoute()))
        val client = newClient(api)
        List(2) { async { client.bootstrap() } }.awaitAll()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aFailedRevalidationFallsBackToTheLastBootstrap() = runTest {
        var online = true
        val api = FakeApi(
            mutableMapOf(
                route("GET /v1/client") { if (online) Answer.data(bootstrapJson()) else throw java.io.IOException("offline") },
            ),
        )
        val client = newClient(api, now = { time })
        val first = client.bootstrap()

        online = false
        time += 5 * 60_000
        assertSame(first, client.bootstrap())
    }

    @Test
    fun withoutAnyBootstrapTheErrorReachesTheCaller() = runTest {
        val client = newClient(FakeApi(mutableMapOf(route("GET /v1/client") { Answer.error(401, "bss-01") })))
        try {
            client.bootstrap()
            fail("expected bss-01")
        } catch (error: MurkioException) {
            assertEquals("bss-01", error.code)
            assertEquals(401, error.status)
            assertTrue(error.localized("pt-BR").isNotEmpty())
        }
    }

    @Test
    fun theProfileIsCachedPerSessionForAMinute() = runTest {
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/me") {
                    Answer.data(
                        mapOf(
                            "id" to "user-1",
                            "session_id" to "session-1",
                            "email" to "ana@example.com",
                            "email_verified" to true,
                            "roles" to listOf("user", "admin"),
                            "mfa_enabled" to false,
                            "created_at" to "2029-05-01T10:00:00Z",
                        ),
                    )
                },
            ),
        )
        val client = newClient(api, now = { time })
        assertNull(client.getUser())

        val session = client.sessions.setSession(tokenPair(nowMs = time), keepLogged = true)
        val user = client.getUser()!!
        assertEquals("ana@example.com", user.email)
        assertEquals(listOf("user", "admin"), user.roles)
        assertEquals("2029-05-01T10:00:00Z", user.createdAt)
        assertEquals(mapOf("access_token" to session.accessToken), api.calls.single().body)

        client.getUser()
        assertEquals(1, api.calls.size)

        time += 61_000
        client.getUser()
        assertEquals(2, api.calls.size)

        client.sessions.setSession(tokenPair(nowMs = time, sessionId = "session-2"), keepLogged = true)
        client.getUser()
        assertEquals("another session, another profile", 3, api.calls.size)
    }

    @Test
    fun aFailedProfileIsNotCached() = runTest {
        var fail = true
        val api = FakeApi(
            mutableMapOf(
                route("POST /v1/me") { if (fail) Answer.error(500, "bss-00") else Answer.data(mapOf("id" to "user-1")) },
            ),
        )
        val client = newClient(api)
        client.sessions.setSession(tokenPair(), keepLogged = true)
        try {
            client.getUser()
            fail("expected bss-00")
        } catch (error: MurkioException) {
            assertEquals("bss-00", error.code)
        }
        fail = false
        assertEquals("user-1", client.getUser()!!.id)
    }

    @Test
    fun theQueryOfALinkIsReadByHand() {
        assertEquals(mapOf("code" to "a b", "state" to "x/y"), queryOf("https://app.example.com/cb?code=a%20b&state=x%2Fy#frag"))
        assertEquals(emptyMap<String, String>(), queryOf("https://app.example.com/cb"))
        assertEquals(mapOf("flag" to "", "a" to "1"), queryOf("https://app.example.com/?flag&a=1&"))
    }

    // Social sign-in.

    private val redirect = "https://app.example.com/auth/callback"
    private val opened = mutableListOf<String>()
    private val oauthStorage = MurkioMemoryStorage()

    private fun oauthApi(): FakeApi = FakeApi(
        mutableMapOf(
            route("POST /v1/oauth/google/authorize") {
                Answer.data(mapOf("authorization_url" to "https://accounts.example.com/o/auth?x=1"))
            },
        ),
    )

    private fun TestScope.oauthClient(api: FakeApi, redirectUrl: String? = redirect, opens: Boolean = true): MurkioClient =
        newClient(api, storage = oauthStorage, oauthRedirectUrl = redirectUrl, opener = { url ->
            opened.add(url)
            opens
        })

    private fun attempt(client: MurkioClient): Map<String, Any?> = Json.parse(oauthStorage.read("murkio.${client.keyHash}.oauth")!!).obj()!!

    @Test
    fun socialSignInStartsWithPkceAndOpensTheProvider() = runTest {
        val api = oauthApi()
        val client = oauthClient(api)
        assertTrue(client.oauthEnabled)
        client.startOAuth("google", keepLogged = true)

        val body = api.calls.single().body.obj()!!
        val stored = attempt(client)
        assertEquals(redirect, body["redirect_url"])
        assertEquals("S256", body["code_challenge_method"])
        assertEquals(codeChallenge(stored["verifier"] as String), body["code_challenge"])
        assertEquals(stored["state"], body["state"])
        assertEquals(64, (stored["verifier"] as String).length)
        assertEquals(32, (stored["state"] as String).length)
        assertEquals(true, stored["keepLogged"])
        assertEquals(listOf("https://accounts.example.com/o/auth?x=1"), opened)
    }

    @Test
    fun socialSignInIsRefusedWithoutAReturnLinkOrABrowser() = runTest {
        val api = oauthApi()
        val none = oauthClient(api, redirectUrl = null)
        assertFalse(none.oauthEnabled)
        try {
            none.startOAuth("google", keepLogged = true)
            fail("expected an error")
        } catch (_: MurkioException) {
        }
        assertTrue(api.calls.isEmpty())

        val closed = oauthClient(api, opens = false)
        try {
            closed.startOAuth("google", keepLogged = true)
            fail("expected an error")
        } catch (_: MurkioException) {
        }
        assertTrue(oauthStorage.keys().isEmpty())
    }

    @Test
    fun anAuthorizationLinkThatIsNotHttpsIsNeverOpened() = runTest {
        val api = oauthApi()
        api.on("POST /v1/oauth/google/authorize") { Answer.data(mapOf("authorization_url" to "intent://evil")) }
        val client = oauthClient(api)
        try {
            client.startOAuth("google", keepLogged = true)
            fail("expected an error")
        } catch (_: MurkioException) {
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun theReturnWithTheCodeBecomesASession() = runTest {
        val api = oauthApi()
        api.on("POST /v1/oauth/token") { Answer.data(tokens()) }
        val client = oauthClient(api)
        client.startOAuth("google", keepLogged = false)
        val stored = attempt(client)

        assertTrue(client.handleRedirect("$redirect?code=oac_1&state=${stored["state"]}"))

        assertEquals(mapOf("code" to "oac_1", "code_verifier" to stored["verifier"]), api.calls.last().body)
        assertTrue(client.isSignedIn)
        assertTrue(client.currentSession!!.ephemeral)
        assertFalse(oauthStorage.keys().any { it.endsWith(".oauth") })
    }

    @Test
    fun aLinkWithAnotherStateIsNotOurs() = runTest {
        val api = oauthApi()
        val client = oauthClient(api)
        client.startOAuth("google", keepLogged = true)

        assertFalse(client.handleRedirect("$redirect?code=x&state=other"))
        assertFalse(client.handleRedirect("$redirect?code=x"))
        assertFalse(client.handleRedirect("https://app.example.com/"))
        assertEquals(1, api.calls.size)
        assertTrue("the attempt is still open", oauthStorage.keys().isNotEmpty())
    }

    @Test
    fun anErrorReturnReachesTheSignInScreenOnce() = runTest {
        val client = oauthClient(oauthApi())
        client.startOAuth("google", keepLogged = true)
        val flow = FlowController(client)
        val before = client.oauthReturn.value

        client.handleRedirect("$redirect?error=access_denied&state=${attempt(client)["state"]}")
        assertEquals(before + 1, client.oauthReturn.value)
        flow.takeOAuthReturn()

        assertEquals("oauth.access_denied", flow.state.value.error?.code)
        assertNull(client.takeOAuthError())
        assertFalse(client.isSignedIn)
    }

    @Test
    fun aTwoFactorChallengeContinuesOnTheSignInScreen() = runTest {
        val api = oauthApi()
        api.on("POST /v1/oauth/token") {
            Answer.data(mapOf("mfa" to mapOf("token" to "mfa_9", "enrollment_required" to false)))
        }
        api.on("POST /v1/mfa/challenge/verify") { Answer.data(tokens()) }
        val client = oauthClient(api)
        client.startOAuth("google", keepLogged = false)
        val flow = FlowController(client)

        client.handleRedirect("$redirect?code=oac_2&state=${attempt(client)["state"]}")
        flow.takeOAuthReturn()
        runCurrent()

        assertTrue(flow.state.value.step is FlowStep.Mfa)
        flow.verifyMfa("123456")
        assertTrue(flow.state.value.step is FlowStep.Done)
        assertTrue(client.currentSession!!.ephemeral)
    }
}
