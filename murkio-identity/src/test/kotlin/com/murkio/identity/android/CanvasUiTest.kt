package com.murkio.identity.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.list
import com.murkio.identity.android.internal.obj
import com.murkio.identity.android.ui.MurkioIdentityCanvas
import com.murkio.identity.android.ui.MurkioScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The canvas on a (simulated) Android screen. The screen is wide and dense so
 * a box lands within a fraction of a dp of where the web puts it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1100dp-h2800dp-xxxhdpi")
class CanvasUiTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        closeClients()
        Murkio.use(null)
    }

    private val elements = SemanticsMatcher("a canvas element") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("murkio-el-") == true
    }

    private fun elementIds(): List<String> = compose.onAllNodes(elements).fetchSemanticsNodes().map {
        it.config[SemanticsProperties.TestTag].removePrefix("murkio-el-")
    }

    private fun show(client: MurkioClient, screen: MurkioScreen = MurkioScreen.SIGN_IN, width: Int = 400, events: Events = Events()) {
        compose.setContent {
            Box(Modifier.requiredWidth(width.dp), contentAlignment = Alignment.TopStart) {
                MurkioIdentityCanvas(
                    client = client,
                    screen = screen,
                    onLogin = { events.logins.add(it) },
                    onError = { events.errors.add(it) },
                    onStep = { events.steps.add(it) },
                    onLink = events.onLink,
                )
            }
        }
        compose.waitForIdle()
    }

    private class Events(val onLink: ((String) -> Unit)? = null) {
        val logins = mutableListOf<MurkioSession>()
        val errors = mutableListOf<MurkioException>()
        val steps = mutableListOf<String>()
    }

    /** The element with [id] shows [text] (the text sits in a node inside the element's box). */
    private fun elementSays(id: String, text: String) {
        compose.onNode(hasAnyAncestor(hasTestTag("murkio-el-$id")) and hasText(text)).assertExists()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextInput(text)
        compose.waitForIdle()
    }

    private fun tap(id: String) {
        compose.onNodeWithTag("murkio-el-$id").performClick()
        compose.waitForIdle()
    }

    private fun fillAndSubmit(action: String = "login", password: String = "S3nha!forte") {
        type("murkio-input-email", "  Ana@Example.com ")
        type("murkio-input-password", password)
        tap(action)
    }

    @Test
    fun everyElementIsDrawnWhereTheWebDrawsIt() {
        val cases = vectors["layout"].list()!!.map { it.obj()!! }
        var index by mutableStateOf(0)
        val clients = cases.map { entry ->
            if (entry["config"] == null) {
                null
            } else {
                uiClient(
                    canvasApi(document = rawDocumentAt(entry["document"] as String), config = entry["config"]),
                    oauthRedirectUrl = if (entry["oauth"] == true) "https://app.example.com/callback" else null,
                )
            }
        }
        compose.setContent {
            val entry = cases[index]
            key(index) {
                Box(Modifier.requiredWidth((entry["width"] as Double).toFloat().dp), contentAlignment = Alignment.TopStart) {
                    val screen = if (entry["screen"] == "sign_up") MurkioScreen.SIGN_UP else MurkioScreen.SIGN_IN
                    val client = clients[index]
                    if (client == null) {
                        MurkioIdentityCanvas(preview = Json.write(rawDocumentAt(entry["document"] as String)), screen = screen, locale = "en")
                    } else {
                        MurkioIdentityCanvas(client = client, screen = screen)
                    }
                }
            }
        }

        for ((position, entry) in cases.withIndex()) {
            index = position
            compose.waitForIdle()
            val at = "layout #$position (${entry["document"]} at ${entry["width"]})"
            val expected = entry["expect"].obj()!!
            val frame = compose.onNodeWithTag("murkio-frame").getUnclippedBoundsInRoot()
            val want = expected["frame"].obj()!!
            assertEquals(at, want["width"] as Double, (frame.right - frame.left).value.toDouble(), TOLERANCE)
            assertEquals(at, want["height"] as Double, (frame.bottom - frame.top).value.toDouble(), TOLERANCE)

            assertEquals("$at: bottom to top", expected["paint"], elementIds())
            for ((id, value) in expected["items"].obj()!!) {
                val item = value.obj()!!
                val box = compose.onNodeWithTag("murkio-el-$id").getUnclippedBoundsInRoot()
                assertEquals("$at $id.x", item["x"] as Double, (box.left - frame.left).value.toDouble(), TOLERANCE)
                assertEquals("$at $id.y", item["y"] as Double, (box.top - frame.top).value.toDouble(), TOLERANCE)
                assertEquals("$at $id.w", item["w"] as Double, (box.right - box.left).value.toDouble(), TOLERANCE)
                assertEquals("$at $id.h", item["h"] as Double, (box.bottom - box.top).value.toDouble(), TOLERANCE)
            }
        }
        assertTrue(cases.size >= 20)
    }

    @Test
    fun signsInWithTheTypedEmailAndPassword() {
        val api = canvasApi(route("POST /v1/login") { Answer.data(tokens()) })
        val client = uiClient(api)
        val events = Events()
        show(client, events = events)

        fillAndSubmit()

        assertEquals(mapOf("email" to "ana@example.com", "password" to "S3nha!forte"), api.to("/v1/login").single().body)
        assertEquals("session-1", events.logins.single().sessionId)
        assertTrue(client.isSignedIn)
        assertTrue("the default canvas has the box unchecked", client.currentSession!!.ephemeral)
    }

    @Test
    fun theKeyboardActionOfThePasswordFieldSubmits() {
        val api = canvasApi(route("POST /v1/login") { Answer.data(tokens()) })
        val client = uiClient(api)
        show(client)

        type("murkio-input-email", "ana@example.com")
        type("murkio-input-password", "S3nha!forte")
        compose.onNodeWithTag("murkio-input-password").performImeAction()
        compose.waitForIdle()

        assertEquals(1, api.to("/v1/login").size)
        assertTrue(client.isSignedIn)
    }

    @Test
    fun keepMeSignedInCheckedKeepsTheSession() {
        val client = uiClient(canvasApi(route("POST /v1/login") { Answer.data(tokens()) }))
        show(client)

        elementSays("keep_logged", "Keep me signed in")
        tap("keep_logged")
        fillAndSubmit()

        assertFalse(client.currentSession!!.ephemeral)
    }

    @Test
    fun aCanvasWithoutTheCheckboxKeepsTheSession() {
        val document = Json.parse(Json.write(readSpec("canvas/default.json"))).obj()!!
        val screen = document["screens"].obj()!!["sign_in"].obj()!!
        @Suppress("UNCHECKED_CAST")
        (screen["elements"] as MutableList<Any?>).removeAll { it.obj()!!["id"] == "keep_logged" }
        for (layout in screen["layouts"].obj()!!.values) {
            @Suppress("UNCHECKED_CAST")
            (layout.obj()!!["items"] as MutableMap<String, Any?>).remove("keep_logged")
        }
        val client = uiClient(canvasApi(route("POST /v1/login") { Answer.data(tokens()) }, document = document))
        show(client)

        fillAndSubmit()

        assertFalse(client.currentSession!!.ephemeral)
    }

    @Test
    fun showsTheTranslatedErrorAndReportsIt() {
        val client = uiClient(canvasApi(route("POST /v1/login") { Answer.error(401, "rsb-00") }), locale = "pt-BR")
        val events = Events()
        show(client, events = events)

        elementSays("login", "Entrar")
        fillAndSubmit(password = "errada")

        compose.onNodeWithTag("murkio-message").assertTextContains(translateError("rsb-00", "pt-BR"))
        assertEquals("rsb-00", events.errors.single().code)
        assertFalse(client.isSignedIn)
    }

    @Test
    fun buttonsDoNothingWhileARequestRuns() {
        val gate = CompletableDeferred<Unit>()
        val api = canvasApi(
            route("POST /v1/login") {
                gate.await()
                Answer.data(tokens())
            },
        )
        val client = uiClient(api)
        show(client)

        fillAndSubmit()
        tap("login")
        tap("register")
        assertEquals(1, api.calls.count { it.path != "/v1/client" })

        gate.complete(Unit)
        compose.waitForIdle()
        assertTrue(client.isSignedIn)
    }

    @Test
    fun theEyeShowsAndHidesThePassword() {
        show(uiClient(canvasApi()))
        type("murkio-input-password", "segredo")

        val toggle = compose.onNodeWithTag("murkio-password-toggle", useUnmergedTree = true)
        toggle.assertIsOff()
        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOn()
        compose.onNodeWithTag("murkio-input-password").assertTextContains("segredo")
    }

    @Test
    fun signupAsksForTheEmailCodeAndSignsIn() {
        val api = canvasApi(
            route("POST /v1/signup") { Answer(204) },
            route("POST /v1/email/verification") { Answer(204) },
            route("POST /v1/email/verification/confirm") { Answer.data(tokens()) },
        )
        val client = uiClient(api)
        val events = Events()
        show(client, events = events)

        fillAndSubmit("register")

        assertEquals(
            mapOf("email" to "ana@example.com", "password" to "S3nha!forte", "locale" to "en"),
            api.to("/v1/signup").single().body,
        )
        assertEquals(listOf("verify_email"), events.steps)
        compose.onNodeWithTag("murkio-frame").assertDoesNotExist()
        compose.onNodeWithText("Confirm your email").assertExists()
        compose.onNodeWithText("We sent a 6-digit code to ana@example.com.").assertExists()

        compose.onNodeWithTag("murkio-step-resend").performClick()
        compose.waitForIdle()
        assertEquals(1, api.to("/v1/email/verification").size)
        compose.onNodeWithTag("murkio-step-message").assertExists()

        type("murkio-step-code", " 123456 ")
        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()

        assertEquals(
            mapOf("email" to "ana@example.com", "code" to "123456", "password" to "S3nha!forte"),
            api.to("/v1/email/verification/confirm").single().body,
        )
        assertEquals(1, events.logins.size)
        compose.onNodeWithTag("murkio-frame").assertExists()
    }

    @Test
    fun twoStepVerificationWithAWrongCodeFirst() {
        var attempts = 0
        val api = canvasApi(
            route("POST /v1/login") { Answer.data(mapOf("mfa" to mapOf("token" to "mfa_1", "enrollment_required" to false))) },
            route("POST /v1/mfa/challenge/verify") { if (++attempts == 1) Answer.error(400, "rsb-09") else Answer.data(tokens()) },
        )
        val client = uiClient(api)
        show(client)

        fillAndSubmit()
        compose.onNodeWithText("Two-step verification").assertExists()

        type("murkio-step-code", "111111")
        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("murkio-step-message").assertTextContains(translateError("rsb-09"))

        compose.onNodeWithTag("murkio-step-code").performTextReplacement("654321")
        compose.onNodeWithTag("murkio-step-code").performImeAction()
        compose.waitForIdle()
        assertTrue(client.isSignedIn)
    }

    @Test
    fun forcedEnrollmentShowsTheKeyAndTheRecoveryCodes() {
        val api = canvasApi(
            route("POST /v1/login") { Answer.data(mapOf("mfa" to mapOf("token" to "mfa_2", "enrollment_required" to true))) },
            route("POST /v1/mfa/challenge/enroll") {
                Answer.data(mapOf("secret" to "JBSWY3DPEHPK3PXP", "otpauth_uri" to "otpauth://totp/App:ana?secret=JBSWY3DPEHPK3PXP"))
            },
            route("POST /v1/mfa/challenge/enroll/confirm") {
                Answer.data(mapOf("recovery_codes" to listOf("aaaa-bbbb", "cccc-dddd"), "session" to tokens()))
            },
        )
        val opened = mutableListOf<String>()
        val client = uiClient(api, opener = { url -> opened.add(url) })
        show(client)

        fillAndSubmit()
        compose.onNodeWithTag("murkio-step-secret").assertTextContains("JBSWY3DPEHPK3PXP", substring = true)

        compose.onNodeWithTag("murkio-step-open").performClick()
        compose.waitForIdle()
        assertTrue(opened.single().startsWith("otpauth://"))

        type("murkio-step-code", "123456")
        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("murkio-step-codes").assertTextContains("aaaa-bbbb", substring = true)
        compose.onNodeWithTag("murkio-step-back").assertDoesNotExist()
        assertFalse("not before the codes are saved", client.isSignedIn)

        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()
        assertTrue(client.isSignedIn)
    }

    @Test
    fun passwordResetReturnsToTheCanvasWithANotice() {
        val api = canvasApi(
            route("POST /v1/password/reset") { Answer(204) },
            route("POST /v1/password/reset/confirm") { Answer(204) },
        )
        show(uiClient(api))

        type("murkio-input-email", "Ana@Example.com")
        tap("forgot")
        compose.onNodeWithText("Reset your password").assertExists()
        compose.onNodeWithTag("murkio-step-email").assertTextContains("ana@example.com")

        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()
        assertEquals(mapOf("email" to "ana@example.com", "locale" to "en"), api.to("/v1/password/reset").single().body)

        type("murkio-step-code", "987654")
        type("murkio-step-password", "N0va!Senha#Forte")
        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()

        assertEquals(
            mapOf("email" to "ana@example.com", "code" to "987654", "password" to "N0va!Senha#Forte"),
            api.to("/v1/password/reset/confirm").single().body,
        )
        compose.onNodeWithTag("murkio-frame").assertExists()
        compose.onNodeWithTag("murkio-message").assertTextContains("Password changed. Sign in with the new one.")
    }

    @Test
    fun backReturnsToTheCanvas() {
        show(uiClient(canvasApi()))
        tap("forgot")
        compose.onNodeWithTag("murkio-step-forgot").assertExists()
        compose.onNodeWithTag("murkio-step-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("murkio-frame").assertExists()
    }

    private val withGoogle = "canvas/fixtures/unpublishable/oauth-provider-disabled.json#document"

    @Test
    fun socialButtonsNeedAReturnLink() {
        show(uiClient(canvasApi(document = rawDocumentAt(withGoogle), providers = listOf("google"))))
        compose.onNodeWithTag("murkio-el-google").assertDoesNotExist()
    }

    @Test
    fun aSocialButtonOpensTheProvider() {
        val api = canvasApi(
            route("POST /v1/oauth/google/authorize") { Answer.data(mapOf("authorization_url" to "https://accounts.example.com/auth")) },
            document = rawDocumentAt(withGoogle),
            providers = listOf("google"),
        )
        val opened = mutableListOf<String>()
        show(uiClient(api, oauthRedirectUrl = "https://app.example.com/callback", opener = { url -> opened.add(url) }))

        elementSays("google", "Continue with Google")
        tap("google")
        assertEquals(listOf("https://accounts.example.com/auth"), opened)
    }

    @Test
    fun withoutThePasswordMethodItsElementsAreGone() {
        val api = canvasApi(document = rawDocumentAt(withGoogle), password = false, providers = listOf("google"))
        show(uiClient(api, oauthRedirectUrl = "https://app.example.com/callback"))

        assertEquals(setOf("title", "error", "divider", "google"), elementIds().toSet())
        compose.onNodeWithTag("murkio-input-email").assertDoesNotExist()
    }

    @Test
    fun aPublishedCanvasThatCannotSignInGivesWayToTheDefault() {
        val document = rawDocumentAt("canvas/fixtures/unpublishable/missing-password-input.json#document")
        show(uiClient(canvasApi(document = document)))
        compose.onNodeWithTag("murkio-el-password").assertExists()
    }

    @Test
    fun withoutTheApiTheDefaultCanvasShowsWithAnError() {
        show(uiClient(FakeApi(mutableMapOf(route("GET /v1/client") { throw java.io.IOException("offline") }))))
        compose.onNodeWithTag("murkio-el-email").assertExists()
        compose.onNodeWithTag("murkio-message").assertTextContains(translateError("unknown"))
    }

    @Test
    fun theSignUpScreenRegisters() {
        val api = canvasApi(
            route("POST /v1/signup") { Answer(204) },
            document = rawDocumentAt("canvas/fixtures/valid/sign-up-screen.json#document"),
        )
        show(uiClient(api), screen = MurkioScreen.SIGN_UP)

        compose.onNodeWithTag("murkio-el-login").assertDoesNotExist()
        type("murkio-input-email", "ana@example.com")
        type("murkio-input-password", "S3nha!forte")
        compose.onNodeWithTag("murkio-input-password").performImeAction()
        compose.waitForIdle()
        assertEquals(1, api.to("/v1/signup").size)
    }

    @Test
    fun previewDrawsADocumentWithoutCallingAnything() {
        compose.setContent {
            Box(Modifier.requiredWidth(400.dp)) {
                MurkioIdentityCanvas(preview = Json.write(readSpec("canvas/default.json")), locale = "pt-BR")
            }
        }
        compose.waitForIdle()

        elementSays("register", "Criar conta")
        type("murkio-input-email", "ana@example.com")
        tap("login")
        tap("forgot")
        compose.onNodeWithTag("murkio-frame").assertExists()
    }

    @Test
    fun aNonsensePreviewFallsBackToTheDefaultCanvas() {
        compose.setContent {
            Box(Modifier.requiredWidth(400.dp)) { MurkioIdentityCanvas(preview = "{\"schema\":99}", locale = "en") }
        }
        compose.waitForIdle()
        elementSays("register", "Create account")
    }

    @Test
    fun withoutSetupTheCanvasSaysWhatIsMissing() {
        Murkio.use(null)
        var failure: Throwable? = null
        try {
            compose.setContent { MurkioIdentityCanvas() }
            compose.waitForIdle()
        } catch (error: Throwable) {
            failure = error
        }
        val message = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(message, message.contains(Murkio.META_PUBLISHABLE_KEY))
    }

    @Test
    fun linksGoToTheAppWhenItAsksElseHttpsOpensOutside() {
        val document = Json.parse(Json.write(readSpec("canvas/default.json"))).obj()!!
        val screen = document["screens"].obj()!!["sign_in"].obj()!!
        fun add(id: String, href: String, x: Int) {
            @Suppress("UNCHECKED_CAST")
            (screen["elements"] as MutableList<Any?>).add(
                mapOf("id" to id, "type" to "link", "props" to mapOf("text" to mapOf("default" to id), "href" to href), "style" to emptyMap<String, Any?>()),
            )
            for (layout in screen["layouts"].obj()!!.values) {
                @Suppress("UNCHECKED_CAST")
                (layout.obj()!!["items"] as MutableMap<String, Any?>)[id] = mapOf("x" to x, "y" to 456, "w" to 160, "h" to 40, "z" to 1)
            }
        }
        add("terms", "/terms", 32)
        add("site", "https://example.com/about", 200)
        val document2 = Json.parse(Json.write(document))

        val opened = mutableListOf<String>()
        val tapped = mutableListOf<String>()
        var handle by mutableStateOf(true)
        val client = uiClient(canvasApi(document = document2), opener = { url -> opened.add(url) })
        compose.setContent {
            Box(Modifier.requiredWidth(400.dp)) {
                MurkioIdentityCanvas(client = client, onLink = if (handle) ({ href: String -> tapped.add(href); Unit }) else null)
            }
        }
        compose.waitForIdle()

        tap("terms")
        tap("site")
        assertEquals(listOf("/terms", "https://example.com/about"), tapped)
        assertTrue(opened.isEmpty())

        handle = false
        compose.waitForIdle()
        tap("terms")
        tap("site")
        assertEquals(listOf("https://example.com/about"), opened)
    }

    private companion object {
        /** Compose lays out in whole pixels; at 4 px per dp a box is at most a quarter of a dp off. */
        const val TOLERANCE = 0.3
    }
}
