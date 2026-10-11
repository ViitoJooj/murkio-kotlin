package com.murkio.identity.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.murkio.identity.android.canvas.pickCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real identity API, through the transport the SDK ships with,
 * when these are set: MURKIO_LIVE_API, MURKIO_LIVE_PK, MURKIO_LIVE_EMAIL,
 * MURKIO_LIVE_PASSWORD (an application with a verified user). Skipped otherwise.
 */
class LiveTest {
    private val apiUrl = System.getenv("MURKIO_LIVE_API").orEmpty()
    private val key = System.getenv("MURKIO_LIVE_PK").orEmpty()
    private val email = System.getenv("MURKIO_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("MURKIO_LIVE_PASSWORD").orEmpty()

    private fun create(storage: MurkioStorage, scope: CoroutineScope, aheadMs: Long = 0): MurkioClient = MurkioClient(
        publishableKey = key,
        apiUrl = apiUrl,
        localeOption = "en",
        oauthRedirectUrl = null,
        storage = storage,
        transport = UrlConnectionTransport(),
        opener = { false },
        scope = scope,
        io = Dispatchers.IO,
        now = { System.currentTimeMillis() + aheadMs },
        isForeground = { false },
    )

    @Test
    fun bootstrapLoginProfileRefreshAndLogoutAgainstTheApi() = runBlocking {
        assumeTrue("set MURKIO_LIVE_* to run against a real API", listOf(apiUrl, key, email, password).none { it.isEmpty() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = MurkioMemoryStorage()
            val client = create(storage, scope)
            client.start()

            val bootstrap = client.bootstrap()
            assertTrue(bootstrap.applicationId.isNotEmpty())
            assertTrue(pickCanvas(bootstrap.canvasDocument, bootstrap.config).signIn.elements.isNotEmpty())

            val flow = FlowController(client)
            flow.login(MurkioCredentials(email, "definitely-wrong", keepLogged = true))
            assertEquals("rsb-00", flow.state.value.error?.code)

            flow.login(MurkioCredentials("  ${email.uppercase()} ", password, keepLogged = true))
            assertNull(flow.state.value.error)
            assertTrue(flow.state.value.step is FlowStep.Done)
            val first = client.currentSession!!
            assertEquals(first.sessionId, first.claims!!.sessionId)

            val user = client.getUser()!!
            assertEquals(email, user.email)
            assertEquals(first.sessionId, user.sessionId)

            // A second client on the same storage is "the next run of the app",
            // with a clock far enough ahead to make the token due.
            val later = create(storage, scope, aheadMs = 20 * 60_000)
            later.start()
            val tokens = List(2) { async { later.getToken() } }.awaitAll()
            assertNotNull(tokens[0])
            assertEquals(tokens[0], tokens[1])
            assertNotEquals(first.accessToken, tokens[0])
            assertEquals(first.sessionId, later.currentSession!!.sessionId)
            assertEquals(user.id, later.getUser()!!.id)

            val refreshToken = later.currentSession!!.refreshToken
            later.signOut()
            assertFalse(later.isSignedIn)
            try {
                later.api.refresh(refreshToken)
                fail("the refresh token of a signed-out session must be refused")
            } catch (error: MurkioException) {
                assertTrue(error.code, error.code in setOf("rsb-02", "rsb-03", "rsb-05"))
            }
        } finally {
            scope.cancel()
        }
    }
}
