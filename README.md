# Murkio identity for Android

Murkio identity for Android apps written with Jetpack Compose. With **only the publishable
key** your app gets:

- `MurkioIdentityCanvas`: the login screen designed in the Murkio dashboard, drawn with
  native Compose, running the whole sign-in (signup, email code, two-step verification,
  password reset, social sign-in);
- `MurkioIdentityEmailInput`, `MurkioIdentityPasswordInput`, `MurkioIdentityKeepLogged`,
  `MurkioIdentityLoginButton` and `MurkioIdentityRegisterButton`: the same sign-in with
  your own layout and style;
- the session: encrypted with a Keystore key, refreshed on its own, and an access token
  ready for your API.

It needs Android 6.0 (API 23) and depends only on Compose (`ui`, `foundation`) and
`kotlinx-coroutines`: no Material, no HTTP or JSON library comes along.

## Install

```kotlin
dependencies {
    implementation("com.murkio:murkio-identity-android:0.1.0")
}
```

## Quick start

Declare the key in the `<application>` of your `AndroidManifest.xml`:

```xml
<meta-data
    android:name="com.murkio.identity.PUBLISHABLE_KEY"
    android:value="pk_..." />
```

and draw the canvas:

```kotlin
@Composable
fun App() {
    val session by rememberMurkioSession()
    if (session == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            MurkioIdentityCanvas()
        }
    } else {
        HomeScreen()
    }
}
```

That is the whole integration. The SDK starts with the app (it reads the key from the
manifest before the first screen), restores the session of the last run, and the canvas
is whatever was published in the dashboard (the default one until then).

To set it up in code instead, or to pass more options, call it in `Application.onCreate`:

```kotlin
Murkio.initialize(this, "pk_...", locale = "pt-BR")
```

## The canvas

```kotlin
MurkioIdentityCanvas(
    screen = MurkioScreen.SIGN_IN,   // or SIGN_UP
    locale = "pt-BR",                // default: the device's language
    fontFamily = myFontFamily,       // used when the canvas theme says "inherit"
    onLogin = { session -> },        // the user is signed in
    onError = { error -> },          // error.code is the API code (rsb-00...)
    onStep = { step -> },            // verify_email, mfa, mfa_enroll, recovery_codes, forgot, reset
    onLink = { href -> },            // a link of the canvas was tapped
)
```

- It takes the width it is given and is as tall as its layout: put it in a scrolling
  column with `imePadding()` so the keyboard never covers it.
- The layout follows the **width of its container**, exactly like the web SDK: the
  desktop layout down to 75% of its width, then the mobile layout, then a stacked column.
  One canvas pixel is one dp; boxes scale with the frame and fonts keep the size chosen
  in the editor (the device's font size setting applies up to 1.3×).
- The canvas is validated again on the device before it is drawn. A published canvas that
  can no longer sign anybody in gives way to the default one.
- `preview = json` draws a canvas document without the API (editors, screenshots).
- A link to a site path (`/terms`) has no site to open in an app: handle it in `onLink`.
  Without `onLink`, https links open in the browser.

The email code, two-step verification, authenticator setup, recovery codes and password
reset screens are built in and follow the canvas colors and font.

## Your own form

```kotlin
Column {
    MurkioIdentityEmailInput(Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(12.dp))
    MurkioIdentityPasswordInput(Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(12.dp))
    MurkioIdentityKeepLogged(label = { Text("Keep me signed in") })
    MurkioIdentityError()
    MurkioIdentityLoginButton(Modifier.background(Color.Black).padding(16.dp)) { busy ->
        Text(if (busy) "Signing in…" else "Sign in", color = Color.White)
    }
    MurkioIdentityRegisterButton(Modifier.padding(16.dp)) { Text("Create account") }
}
```

The inputs are the unstyled foundation text field: `modifier`, `textStyle` and
`decorationBox` give them your look. The buttons only add the click; you draw them. The
composables of a screen share one form, so they can sit anywhere in your layout.

With Material, use your own fields and keep the SDK's flow:

```kotlin
val form = currentMurkioForm()
val scope = rememberCoroutineScope()
OutlinedTextField(form.email, { form.email = it }, label = { Text("Email") })
OutlinedTextField(form.password, { form.password = it }, label = { Text("Password") })
Button(onClick = { scope.launch { form.login() } }) { Text("Sign in") }
MurkioIdentityError()   // also hosts the built-in screens
```

The built-in screens (email code, 2FA, …) open in a dialog painted with the canvas theme.
`MurkioIdentityForm` gives a group its own form and lets you choose where they show:

```kotlin
MurkioIdentityForm(
    onLogin = { session -> },
    stepContainer = { onDismiss, content ->
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    },
) { form -> /* your fields */ }
```

## The session

```kotlin
val client = Murkio.client

client.isSignedIn                 // right now
client.session                    // StateFlow<MurkioSession?>: sign-ins, sign-outs and refreshes
val token = client.getToken()     // suspend: a valid access token, or null
val user = client.getUser()       // suspend: email, roles, 2FA (cached for a minute)
client.signOut()                  // suspend
```

Send the token to your API as `Authorization: Bearer <token>`; your backend verifies it
with a Murkio server SDK.

- The tokens are stored in the app's private preferences, encrypted (AES-256-GCM) with a
  key that never leaves the Android Keystore, one record per publishable key. A backup
  restored on another phone cannot read it: the user just signs in again.
- The access token is renewed 90 seconds before it expires while the app is in the
  foreground, when the app comes back to it, and by `getToken()` whenever needed. Calls
  that happen at the same time share one request, and that request is not cancelled with
  its caller: the refresh token rotates on every use, and losing the answer would end the
  session.
- Offline, the session is kept and `getToken()` throws a `MurkioException` with the code
  `network`. Only the API saying the session is over signs the user out.
- **"Keep me signed in" unchecked** means the session ends with the app: the next start
  logs it out. A canvas (or form) **without** that checkbox keeps the session, which is
  what people expect from an app.

## Social sign-in

A provider sends the user back to an **https** link registered in the application's
redirect URLs. In an app that link is an
[App Link](https://developer.android.com/training/app-links) that opens your activity:

```xml
<meta-data
    android:name="com.murkio.identity.OAUTH_REDIRECT_URL"
    android:value="https://app.example.com/auth/callback" />

<activity android:name=".MainActivity" android:launchMode="singleTask" android:exported="true">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="app.example.com" android:path="/auth/callback" />
    </intent-filter>
</activity>
```

and the activity gives the link to the SDK:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.dataString?.let { link -> lifecycleScope.launch { Murkio.client.handleRedirect(link) } }
}
```

(do the same with `intent` in `onCreate`, for when the app was not running).
`handleRedirect` returns `false` for a link that is not the answer to a sign-in this app
started. Without the redirect URL the canvas hides the social buttons: a button with
nowhere to come back to is not shown.

## Errors and languages

Every failure is a `MurkioException`: `code` is the API code (`rsb-00`, `rag-06`…),
`network` when there was no answer, `unknown` otherwise. `error.localized("pt-BR")` is the
message for the user; the canvas and `MurkioIdentityError` already show it. English and
Brazilian Portuguese are built in; any other language falls back to English, and the texts
of the canvas itself come translated from the dashboard.

## Options

| Manifest `<meta-data>` | `Murkio.initialize` | |
|---|---|---|
| `com.murkio.identity.PUBLISHABLE_KEY` | `publishableKey` | the application's `pk_...` key (required) |
| `com.murkio.identity.API_URL` | `apiUrl` | the identity API, when it is not the default |
| `com.murkio.identity.OAUTH_REDIRECT_URL` | `oauthRedirectUrl` | the App Link of the social sign-in |
| | `locale` | language of the built-in screens |
| | `storage` | a `MurkioStorage` of your own |
| | `transport` | a `MurkioTransport` of your own (OkHttp, a test double…) |

## Developing

```sh
./gradlew :murkio-identity:testDebugUnitTest :murkio-identity:assembleRelease
```

The tests run on the JVM (Robolectric for the Compose ones). They run the vectors and
fixtures in `libs/spec` that every Murkio client SDK shares (the web SDK runs the same
ones), so the canvas is laid out and the flows behave like on the web.
`internal/Spec.kt` comes from `node libs/scripts/sync-client-spec.mjs`. Set
`MURKIO_LIVE_API`, `MURKIO_LIVE_PK`, `MURKIO_LIVE_EMAIL` and `MURKIO_LIVE_PASSWORD` to also
run `LiveTest` against a real API, and `-Dmurkio.screenshots=<dir>` to render the canvas
to a PNG.
