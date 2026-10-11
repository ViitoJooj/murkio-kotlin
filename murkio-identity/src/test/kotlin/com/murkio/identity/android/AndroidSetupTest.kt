package com.murkio.identity.android

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Android side of the setup: the manifest, the app lifecycle, the browser and the storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSetupTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = Murkio.use(null)

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun initializeCreatesTheAppsClientOnce() {
        val api = FakeApi()
        val first = Murkio.initialize(application, "pk_android", apiUrl = "https://api.example.com", storage = MurkioMemoryStorage(), transport = api)

        assertTrue(Murkio.isInitialized)
        assertSame(first, Murkio.client)
        assertSame(first, Murkio.initialize(application, "pk_android"))
        assertEquals("https://api.example.com", first.api.apiUrl)

        val other = Murkio.initialize(application, "pk_other", storage = MurkioMemoryStorage(), transport = api)
        assertNotSame(first, other)
        assertSame(other, Murkio.client)
    }

    @Test
    fun theManifestStartsTheSdkWithoutCode() {
        val info = application.packageManager.getApplicationInfo(application.packageName, 0)
        info.metaData = Bundle().apply {
            putString(Murkio.META_PUBLISHABLE_KEY, "pk_manifest")
            putString(Murkio.META_API_URL, "https://identity.example.com/")
            putString(Murkio.META_OAUTH_REDIRECT_URL, "https://app.example.com/callback")
        }
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        packageInfo.applicationInfo = info
        shadowOf(application.packageManager).installPackage(packageInfo)

        Murkio.initializeFromManifest(application)

        val client = Murkio.client
        assertEquals("pk_manifest", client.publishableKey)
        assertEquals("https://identity.example.com", client.api.apiUrl)
        assertEquals("https://app.example.com/callback", client.oauthRedirectUrl)
    }

    @Test
    fun withoutTheKeyInTheManifestNothingStarts() {
        Murkio.initializeFromManifest(application)
        assertFalse(Murkio.isInitialized)
    }

    @Test
    fun comingToTheForegroundRenewsATokenThatIsDue() {
        val api = FakeApi(mutableMapOf(route("POST /v1/token/refresh") { Answer.data(tokens()) }))
        val client = Murkio.initialize(
            application, "pk_lifecycle", apiUrl = "https://api.example.com", storage = MurkioMemoryStorage(), transport = api,
        )
        val old = runBlocking {
            client.start()
            // Already inside the refresh margin: due as soon as the app is seen.
            client.sessions.setSession(tokenPair(expiresIn = 30), keepLogged = true)
        }
        assertTrue(api.calls.isEmpty())

        Robolectric.buildActivity(ComponentActivity::class.java).setup()

        waitFor("the refresh") { api.to("/v1/token/refresh").size == 1 && client.currentSession?.refreshToken != old.refreshToken }
        assertEquals(mapOf("refresh_token" to old.refreshToken), api.calls.single().body)
    }

    @Test
    fun linksOpenOutsideTheApp() {
        val client = Murkio.initialize(application, "pk_links", storage = MurkioMemoryStorage(), transport = FakeApi())

        assertTrue(client.openUrl("https://accounts.example.com/auth?x=1"))

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://accounts.example.com/auth?x=1", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theKeystoreStorageRoundTripsAndForgets() {
        val storage = KeystoreStorage(application)
        val available = runCatching { storage.write("murkio.probe", "x") }.isSuccess
        assumeTrue("this environment has no AndroidKeyStore; the storage only runs on a device", available)

        storage.write("murkio.test.session", "{\"refreshToken\":\"segredo\"}")
        assertEquals("{\"refreshToken\":\"segredo\"}", storage.read("murkio.test.session"))
        val raw = application.getSharedPreferences("com.murkio.identity", 0).getString("murkio.test.session", null)!!
        assertFalse("the value is not stored in the clear", raw.contains("segredo"))

        storage.delete("murkio.test.session")
        assertNull(storage.read("murkio.test.session"))
        assertNull(storage.read("murkio.never.written"))
    }
}
