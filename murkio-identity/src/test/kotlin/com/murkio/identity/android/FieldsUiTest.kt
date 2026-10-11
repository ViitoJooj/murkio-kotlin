package com.murkio.identity.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.murkio.identity.android.ui.MurkioIdentityEmailInput
import com.murkio.identity.android.ui.MurkioIdentityError
import com.murkio.identity.android.ui.MurkioIdentityForm
import com.murkio.identity.android.ui.MurkioIdentityKeepLogged
import com.murkio.identity.android.ui.MurkioIdentityLoginButton
import com.murkio.identity.android.ui.MurkioIdentityPasswordInput
import com.murkio.identity.android.ui.MurkioIdentityRegisterButton
import com.murkio.identity.android.ui.rememberMurkioSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sign-in form made of the standalone composables, the way an app writes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class FieldsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        closeClients()
        Murkio.use(null)
    }

    @Composable
    private fun Fields(keepLogged: Boolean) {
        Column {
            MurkioIdentityEmailInput(Modifier.testTag("email"))
            MurkioIdentityPasswordInput(Modifier.testTag("password"))
            if (keepLogged) {
                MurkioIdentityKeepLogged(Modifier.testTag("keep"), label = { BasicText("Stay signed in") })
            }
            MurkioIdentityError(Modifier.testTag("error"))
            MurkioIdentityLoginButton(Modifier.testTag("login")) { busy -> BasicText(if (busy) "Wait" else "Sign in") }
            MurkioIdentityRegisterButton(Modifier.testTag("register")) { BasicText("Create account") }
        }
    }

    private class Events {
        val logins = mutableListOf<MurkioSession>()
        val errors = mutableListOf<MurkioException>()
    }

    // The built-in screens are shown in place: under Robolectric a text field
    // inside a Dialog window never lets Compose go idle (a bare Dialog with a
    // BasicTextField does the same), so the default dialog cannot be driven here.
    private fun show(client: MurkioClient, keepLogged: Boolean = false, events: Events = Events()) {
        compose.setContent {
            // A column, so the built-in screen sits above the form instead of over it.
            Column {
                MurkioIdentityForm(
                    client,
                    onLogin = { events.logins.add(it) },
                    onError = { events.errors.add(it) },
                    stepContainer = { onDismiss, content ->
                        Column {
                            BasicText("dismiss", Modifier.testTag("dismiss").clickable(onClick = onDismiss))
                            content()
                        }
                    },
                ) {
                    Fields(keepLogged)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fill() {
        compose.onNodeWithTag("email").performTextInput(" Ana@Example.com")
        compose.onNodeWithTag("password").performTextInput("S3nha!forte")
        compose.waitForIdle()
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theLoginButtonSignsInWithWhatTheInputsHold() {
        val api = canvasApi(route("POST /v1/login") { Answer.data(tokens()) })
        val client = uiClient(api)
        val events = Events()
        show(client, events = events)

        fill()
        press("login")

        assertEquals(mapOf("email" to "ana@example.com", "password" to "S3nha!forte"), api.to("/v1/login").single().body)
        assertEquals(1, events.logins.size)
        assertFalse("no checkbox in the form: an app keeps the session", client.currentSession!!.ephemeral)
    }

    @Test
    fun theCheckboxDecidesWhetherTheSessionIsKept() {
        val client = uiClient(canvasApi(route("POST /v1/login") { Answer.data(tokens()) }))
        show(client, keepLogged = true)

        fill()
        press("login")
        assertTrue("left unchecked", client.currentSession!!.ephemeral)

        runBlocking { client.sessions.clear() }
        press("keep")
        fill()
        press("login")
        assertFalse(client.currentSession!!.ephemeral)
    }

    @Test
    fun buttonsAreDisabledWhileARequestRunsAndTheErrorShows() {
        val gate = CompletableDeferred<Unit>()
        val api = canvasApi(
            route("POST /v1/login") {
                gate.await()
                Answer.error(401, "rsb-00")
            },
        )
        val events = Events()
        show(uiClient(api), events = events)

        fill()
        press("login")
        compose.onNodeWithTag("login").assertIsNotEnabled()
        compose.onNodeWithTag("register").assertIsNotEnabled()
        compose.onNodeWithText("Wait").assertExists()

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(translateError("rsb-00")).assertExists()
        assertEquals("rsb-00", events.errors.single().code)
        compose.onNodeWithTag("login").assertIsEnabled()
    }

    @Test
    fun signupOpensTheEmailCodeScreenAndSignsIn() {
        val api = canvasApi(
            route("POST /v1/signup") { Answer(204) },
            route("POST /v1/email/verification/confirm") { Answer.data(tokens()) },
        )
        val events = Events()
        show(uiClient(api), events = events)

        fill()
        press("register")
        compose.onNodeWithText("Confirm your email").assertExists()

        compose.onNodeWithTag("murkio-step-code").performTextInput("123456")
        compose.onNodeWithTag("murkio-step-submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Confirm your email").assertDoesNotExist()
        assertEquals(1, events.logins.size)
        assertEquals(
            mapOf("email" to "ana@example.com", "code" to "123456", "password" to "S3nha!forte"),
            api.to("/v1/email/verification/confirm").single().body,
        )
    }

    @Test
    fun backAndDismissReturnToTheForm() {
        show(uiClient(canvasApi(route("POST /v1/signup") { Answer(204) })))

        fill()
        press("register")
        compose.onNodeWithTag("murkio-step-verify_email").assertExists()

        compose.onNodeWithTag("murkio-step-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("murkio-step-verify_email").assertDoesNotExist()

        press("register")
        compose.onNodeWithTag("murkio-step-verify_email").assertExists()
        press("dismiss")
        compose.onNodeWithTag("murkio-step-verify_email").assertDoesNotExist()
    }

    @Test
    fun thePasswordFieldSubmitsFromTheKeyboard() {
        val client = uiClient(canvasApi(route("POST /v1/login") { Answer.data(tokens()) }))
        show(client)

        fill()
        compose.onNodeWithTag("password").performImeAction()
        compose.waitForIdle()
        assertTrue(client.isSignedIn)
    }

    @Test
    fun composablesOutsideAFormShareTheDefaultOneOfTheApp() {
        val api = canvasApi(route("POST /v1/login") { Answer.data(tokens()) }, route("POST /v1/logout") { Answer(204) })
        val client = uiClient(api)
        Murkio.use(client)
        val logins = mutableListOf<MurkioSession>()
        compose.setContent {
            val session by rememberMurkioSession()
            Column {
                MurkioIdentityEmailInput(Modifier.testTag("email"))
                MurkioIdentityPasswordInput(Modifier.testTag("password"))
                MurkioIdentityLoginButton(Modifier.testTag("login"), onLogin = { logins.add(it) }) { BasicText("Enter") }
                BasicText(if (session == null) "signed out" else "signed in")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("signed out").assertExists()

        fill()
        press("login")

        assertEquals(1, logins.size)
        compose.onNodeWithText("signed in").assertExists()
        assertSame(client, Murkio.client)

        runBlocking { client.signOut() }
        compose.waitForIdle()
        compose.onNodeWithText("signed out").assertExists()
    }
}
