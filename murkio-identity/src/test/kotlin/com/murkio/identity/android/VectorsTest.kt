package com.murkio.identity.android

import com.murkio.identity.android.canvas.LocalizedText
import com.murkio.identity.android.canvas.defaultCanvas
import com.murkio.identity.android.canvas.isUsable
import com.murkio.identity.android.canvas.isVisible
import com.murkio.identity.android.canvas.parseColor
import com.murkio.identity.android.canvas.pickCanvas
import com.murkio.identity.android.canvas.place
import com.murkio.identity.android.canvas.readingOrder
import com.murkio.identity.android.canvas.sanitizeParsed
import com.murkio.identity.android.canvas.submitAction
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.codeChallenge
import com.murkio.identity.android.internal.fnv1a32
import com.murkio.identity.android.internal.list
import com.murkio.identity.android.internal.murkioString
import com.murkio.identity.android.internal.obj
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * libs/spec/client/vectors.json is run by every client SDK. The @murkio/html
 * tests run it against the browser code, so passing here means this SDK
 * answers like the web does.
 */
class VectorsTest {
    @After
    fun tearDown() = closeClients()

    private fun cases(name: String): List<Map<String, Any?>> = vectors[name].list()!!.map { it.obj()!! }

    @Test
    fun hashesThePublishableKey() {
        for ((value, hash) in vectors["key_hash"].obj()!!) assertEquals(value, hash, fnv1a32(value))
    }

    @Test
    fun derivesThePkceChallenge() {
        for (entry in cases("pkce")) assertEquals(entry["challenge"], codeChallenge(entry["verifier"] as String))
    }

    @Test
    fun readsTheClaimsOfAnAccessToken() {
        val entry = vectors["claims"].obj()!!
        val expected = entry["expect"].obj()!!
        val claims = decodeClaims(entry["token"] as String)!!
        assertEquals(expected["sub"], claims.subject)
        assertEquals(expected["sid"], claims.sessionId)
        assertEquals((expected["exp"] as Double).toLong(), claims.expiresAt)
        assertEquals(expected["roles"], claims.roles)
        assertNull(decodeClaims("not-a-token"))
        assertNull(decodeClaims("a.b.c"))
    }

    @Test
    fun parsesEveryColorForm() {
        for (entry in cases("colors")) {
            val argb = parseColor(entry["color"])!!
            val rgba = listOf((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff, (argb ushr 24) and 0xff)
            assertEquals(entry["color"] as String, entry["rgba"].list()!!.map { (it as Double).toInt() }, rgba)
        }
        for (bad in listOf("#12", "red", "#gggggg", "#12345", 7, null)) assertNull(parseColor(bad))
    }

    @Test
    fun picksTheTextOfALocale() {
        for (entry in cases("text")) {
            val raw = entry["text"].obj()!!
            val text = LocalizedText(raw["default"] as String, raw["i18n"].obj()!!.mapValues { it.value as String })
            assertEquals(entry["locale"] as String, entry["expect"], text.pick(entry["locale"] as String))
        }
        assertEquals("y", LocalizedText("x", mapOf("pt-BR" to "y")).pick("pt_BR"))
    }

    @Test
    fun speaksTheSameBuiltInStrings() {
        for (entry in cases("strings")) {
            assertEquals(entry["expect"], murkioString(entry["locale"] as String, entry["key"] as String))
        }
        val params = mapOf("email" to "{email}", "provider" to "{provider}")
        for (locale in listOf("en", "pt-BR")) {
            for ((key, text) in readSpec("client/strings/$locale.json").obj()!!) {
                assertEquals(key, text, murkioString(locale, key, params))
            }
        }
    }

    @Test
    fun translatesTheErrorCodes() {
        for (entry in cases("errors")) {
            assertEquals(
                "${entry["locale"]} ${entry["code"]}",
                entry["expect"],
                translateError(entry["code"] as String, entry["locale"] as String),
            )
        }
    }

    @Test
    fun laysTheCanvasOutLikeTheWeb() {
        val layouts = cases("layout")
        assertTrue(layouts.size >= 20)
        for ((index, entry) in layouts.withIndex()) {
            val document = documentAt(entry["document"] as String)
            val screen = if (entry["screen"] == "sign_up") document.signUp!! else document.signIn
            val expected = entry["expect"].obj()!!
            val at = "layout #$index (${entry["document"]} at ${entry["width"]})"
            val placement = place(screen, (entry["width"] as Double).toFloat(), configFrom(entry["config"]), entry["oauth"] as Boolean)

            assertEquals(at, expected["layout"], placement.layout.wire)
            val frame = expected["frame"].obj()!!
            assertEquals(at, frame["width"] as Double, placement.frameWidth.toDouble(), 0.01)
            assertEquals(at, frame["height"] as Double, placement.frameHeight.toDouble(), 0.01)
            assertEquals(at, expected["order"], placement.order.map { it.id })
            assertEquals(at, expected["paint"], placement.paint.map { it.id })

            val items = expected["items"].obj()!!
            assertEquals(at, items.keys, placement.rects.keys)
            for ((id, value) in items) {
                val want = value.obj()!!
                val rect = placement.rects.getValue(id)
                assertEquals("$at $id.x", want["x"] as Double, rect.x.toDouble(), 0.01)
                assertEquals("$at $id.y", want["y"] as Double, rect.y.toDouble(), 0.01)
                assertEquals("$at $id.w", want["w"] as Double, rect.w.toDouble(), 0.01)
                assertEquals("$at $id.h", want["h"] as Double, rect.h.toDouble(), 0.01)
            }
        }
    }

    @Test
    fun showsWhatTheApplicationTurnedOn() {
        for (entry in cases("visibility")) {
            val document = documentAt(entry["document"] as String)
            val signUp = entry["screen"] == "sign_up"
            val screen = if (signUp) document.signUp!! else document.signIn
            val config = configFrom(entry["config"])
            val oauth = entry["oauth"] as Boolean
            val expected = entry["expect"].obj()!!
            val at = "${entry["document"]} ${entry["screen"]} oauth=$oauth"
            assertEquals(at, expected["visible"], readingOrder(screen).filter { isVisible(it, config, oauth) }.map { it.id })
            assertEquals(at, expected["submit"], submitAction(screen, signUp, config, oauth))
        }
    }

    @Test
    fun fallsBackToTheDefaultCanvasWhenThePublishedOneCannotSignIn() {
        for (entry in cases("pick_canvas")) {
            val raw = rawDocumentAt(entry["document"] as String)
            val config = configFrom(entry["config"])
            val own = sanitizeParsed(raw).document!!
            val at = "${entry["document"]} ${Json.write(entry["config"])}"
            assertEquals(at, entry["expect"], if (isUsable(own.signIn, config)) "document" else "default")
            assertEquals(at, !isUsable(own.signIn, config), pickCanvas(raw, config) === defaultCanvas())
        }
        assertSame(defaultCanvas(), pickCanvas(null, null))
        assertSame(defaultCanvas(), pickCanvas("nonsense", null))
    }

    @Test
    fun walksEveryFlowLikeTheWeb() {
        val flows = vectors["flows"].obj()!!
        val locale = flows["locale"] as String
        val refreshToken = flows["tokens"].obj()!!["refresh_token"]
        val scenarios = flows["cases"].list()!!.map { it.obj()!! }
        assertTrue(scenarios.size >= 12)

        for (scenario in scenarios) runTest {
            val name = scenario["name"] as String
            val queue = ArrayDeque<Map<String, Any?>>()
            val transport = MurkioTransport { request ->
                val step = queue.removeFirstOrNull() ?: throw AssertionError("$name: unexpected ${request.method} ${request.url}")
                assertEquals(name, "${step["method"]} ${step["path"]}", "${request.method} ${request.url.removePrefix("https://api.example.com")}")
                assertEquals(name, step["body"], request.body?.let(Json::parse))
                assertEquals(name, "Bearer pk_vectors", request.headers["Authorization"])
                val status = (step["status"] as Double).toInt()
                if (status == 0) throw java.io.IOException("network down")
                MurkioHttpResponse(status, emptyMap(), if (status == 204 || step["response"] == null) "" else Json.write(step["response"]))
            }
            val client = newClient(transport, locale = locale, key = "pk_vectors")
            client.start()
            val flow = FlowController(client)

            for (step in scenario["steps"].list()!!.map { it.obj()!! }) {
                queue.addAll(step["http"].list()!!.map { it.obj()!! })
                val args = step["args"].obj()!!
                val credentials = MurkioCredentials(
                    email = args["email"] as? String ?: "",
                    password = args["password"] as? String ?: "",
                    keepLogged = args["keep_logged"] == true,
                )
                val call = step["call"] as String
                when (call) {
                    "login" -> flow.login(credentials)
                    "register" -> flow.register(credentials)
                    "confirm_email" -> flow.confirmEmail(args["code"] as String, args["password"] as? String)
                    "resend_email" -> flow.resendEmail()
                    "verify_mfa" -> flow.verifyMfa(args["code"] as String)
                    "confirm_enrollment" -> flow.confirmEnrollment(args["code"] as String)
                    "finish_recovery" -> flow.finishRecovery()
                    "forgot" -> flow.forgot(args["email"] as String)
                    "request_reset" -> flow.requestReset(args["email"] as String)
                    "reset_password" -> flow.resetPassword(args["code"] as String, args["password"] as String)
                    "back" -> flow.back()
                    else -> fail("unknown call $call")
                }
                val at = "$name / $call"
                assertTrue("$at left requests unsent", queue.isEmpty())

                val state = flow.state.value
                val expected = step["expect"].obj()!!
                assertFalse(at, state.busy)
                assertEquals(at, expected["step"], state.step.name)
                assertEquals(at, expected["error"], state.error?.code)
                assertEquals(at, expected["notice"], state.notice)
                assertEquals(at, expected["signed_in"], client.isSignedIn)
                if (expected["signed_in"] == true) {
                    val session = client.currentSession!!
                    assertEquals(at, expected["ephemeral"], session.ephemeral)
                    assertEquals(at, expected["session_id"], session.sessionId)
                    assertEquals(at, refreshToken, session.refreshToken)
                }
                if ("email" in expected) assertEquals(at, expected["email"], flow.currentEmail)
                when (val current = state.step) {
                    is FlowStep.VerifyEmail -> {
                        assertEquals(at, expected["email"], current.email)
                        assertEquals(at, expected["needs_password"], current.needsPassword)
                    }
                    is FlowStep.Reset -> assertEquals(at, expected["email"], current.email)
                    is FlowStep.MfaEnroll -> {
                        assertEquals(at, expected["secret"], current.secret)
                        assertEquals(at, expected["otpauth_uri"], current.otpauthUri)
                    }
                    is FlowStep.RecoveryCodes -> assertEquals(at, expected["codes"], current.codes)
                    else -> Unit
                }
            }
            closeClients()
        }
    }
}
