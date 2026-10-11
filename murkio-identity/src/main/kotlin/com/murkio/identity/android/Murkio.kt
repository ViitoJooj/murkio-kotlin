package com.murkio.identity.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The entry point of the SDK. The app only declares its publishable key:
 *
 * ```xml
 * <meta-data android:name="com.murkio.identity.PUBLISHABLE_KEY" android:value="pk_..." />
 * ```
 *
 * and the SDK starts itself with the app. To set it up in code instead (or to
 * pass more options), call [initialize] in `Application.onCreate`.
 */
public object Murkio {
    /** The manifest `<meta-data>` names the SDK reads at startup. */
    public const val META_PUBLISHABLE_KEY: String = "com.murkio.identity.PUBLISHABLE_KEY"
    public const val META_API_URL: String = "com.murkio.identity.API_URL"
    public const val META_OAUTH_REDIRECT_URL: String = "com.murkio.identity.OAUTH_REDIRECT_URL"

    @Volatile
    private var instance: MurkioClient? = null
    private var watcher: Foreground? = null

    /** The app's client. Throws until the SDK is initialized. */
    @JvmStatic
    public val client: MurkioClient
        get() = instance ?: throw IllegalStateException(
            "Murkio is not initialized. Add <meta-data android:name=\"$META_PUBLISHABLE_KEY\" " +
                "android:value=\"pk_...\" /> to the <application> of your manifest, or call " +
                "Murkio.initialize(context, \"pk_...\") in Application.onCreate.",
        )

    @JvmStatic
    public val isInitialized: Boolean get() = instance != null

    /**
     * Creates the app's client and restores the session of the last run.
     * Calling it again with the same key returns the same client.
     *
     * @param publishableKey the application's `pk_...` key, the only required setting.
     * @param locale language of the built-in screens and error messages. Defaults to the device's.
     * @param oauthRedirectUrl the https App Link the social sign-in comes back to. Without it the
     *   social buttons are hidden.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        publishableKey: String,
        apiUrl: String? = null,
        locale: String? = null,
        oauthRedirectUrl: String? = null,
        storage: MurkioStorage? = null,
        transport: MurkioTransport? = null,
    ): MurkioClient {
        synchronized(this) {
            return create(context, publishableKey, apiUrl, locale, oauthRedirectUrl, storage, transport)
        }
    }

    private fun create(
        context: Context,
        publishableKey: String,
        apiUrl: String?,
        locale: String?,
        oauthRedirectUrl: String?,
        storage: MurkioStorage?,
        transport: MurkioTransport?,
    ): MurkioClient {
        instance?.let { if (it.publishableKey == publishableKey) return it }

        // Another key replaces the client: the old one stops watching the app and its timers end.
        val application = context.applicationContext
        watcher?.let { (application as? Application)?.unregisterActivityLifecycleCallbacks(it) }
        instance?.close()
        val foreground = Foreground()
        val created = MurkioClient(
            publishableKey = publishableKey,
            apiUrl = apiUrl,
            localeOption = locale,
            oauthRedirectUrl = oauthRedirectUrl,
            storage = storage ?: KeystoreStorage(application),
            transport = transport ?: UrlConnectionTransport(),
            opener = { url -> openOutside(application, url) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.IO,
            now = System::currentTimeMillis,
            isForeground = foreground::visible,
        )
        foreground.onResume = created::resumed
        (application as? Application)?.registerActivityLifecycleCallbacks(foreground)
        watcher = foreground
        instance = created
        CoroutineScope(Dispatchers.Default).launch { runCatching { created.start() } }
        return created
    }

    /** Makes [client] the app's client. For tests and previews. */
    public fun use(client: MurkioClient?) {
        instance = client
    }

    /** Reads the manifest and initializes the SDK when the key is declared there. */
    internal fun initializeFromManifest(context: Context) {
        val metadata = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull() ?: return
        val key = metadata.getString(META_PUBLISHABLE_KEY) ?: return
        initialize(
            context,
            key,
            apiUrl = metadata.getString(META_API_URL),
            oauthRedirectUrl = metadata.getString(META_OAUTH_REDIRECT_URL),
        )
    }

    private fun openOutside(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Counts the started activities: the app is in the foreground while there is one. */
    private class Foreground : Application.ActivityLifecycleCallbacks {
        @Volatile
        private var started = 0

        @Volatile
        private var seen = false
        var onResume: () -> Unit = {}

        /** True until the first activity is seen: a refresh at startup is not held back. */
        fun visible(): Boolean = !seen || started > 0

        override fun onActivityStarted(activity: Activity) {
            seen = true
            if (started++ == 0) onResume()
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {}
    }
}
