package com.murkio.identity.android

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.murkio.identity.android.ui.MurkioIdentityCanvas
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The oldest Android the library supports (its minSdk). The canvas takes its
 * language from the device when nothing else says it, and the call that reads
 * it only exists from Android 7.0 on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "pt-rBR-w400dp-h900dp-xhdpi")
class OldAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        closeClients()
        Murkio.use(null)
    }

    @Test
    fun theCanvasSpeaksTheLanguageOfTheDevice() {
        compose.setContent { MurkioIdentityCanvas(client = uiClient(canvasApi(), locale = null)) }
        compose.waitForIdle()
        // English is the fallback, so Portuguese here can only come from the device.
        compose.onNode(hasAnyAncestor(hasTestTag("murkio-el-title")) and hasText("Entrar")).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("murkio-el-register")) and hasText("Criar conta")).assertExists()
    }
}
