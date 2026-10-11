package com.murkio.identity.android

import com.murkio.identity.android.canvas.CanvasDocument
import com.murkio.identity.android.canvas.sanitizeParsed
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.base64UrlEncode
import com.murkio.identity.android.internal.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import java.io.File

/** libs/spec, shared with the service and the other SDKs. */
internal val specDir: File = File(System.getProperty("murkio.spec") ?: "../../../spec")

internal fun readSpec(path: String): Any? = Json.parse(File(specDir, path).readText())

/** A raw document from a vector reference (`file.json` or `file.json#field`). */
internal fun rawDocumentAt(ref: String): Any? {
    val parts = ref.split('#')
    val value = readSpec(parts[0])
    return if (parts.size > 1) value.obj()!![parts[1]] else value
}

/** The same document after the SDK's own validation. */
internal fun documentAt(ref: String): CanvasDocument {
    val result = sanitizeParsed(rawDocumentAt(ref))
    assertEquals(ref, emptyList<String>(), result.problems)
    return result.document!!
}

internal val vectors: Map<String, Any?> by lazy { readSpec("client/vectors.json").obj()!! }

internal fun configFrom(json: Any?): PublicConfig? = json?.let(PublicConfig::fromJson)

/** One request the fake API received. */
internal class Call(val method: String, val path: String, val body: Any?, val headers: Map<String, String>)

/** What the fake API answers. */
internal class Answer(val status: Int, val body: Any? = null, val headers: Map<String, String> = emptyMap()) {
    companion object {
        /** An API error with one detail. */
        fun error(status: Int, code: String): Answer =
            Answer(status, mapOf("details" to listOf(mapOf("code" to code, "message" to code))))

        /** A `{"data": ...}` answer. */
        fun data(data: Any?, status: Int = 200, headers: Map<String, String> = emptyMap()): Answer =
            Answer(status, mapOf("data" to data), headers)
    }
}

/** A transport that answers from a table of "METHOD /path" handlers and records the calls. A handler may throw. */
internal class FakeApi(val routes: MutableMap<String, suspend (Call) -> Answer> = mutableMapOf()) : MurkioTransport {
    val calls: MutableList<Call> = mutableListOf()

    fun to(path: String): List<Call> = calls.filter { it.path == path }

    /** Adds or replaces a route. */
    fun on(key: String, handler: suspend (Call) -> Answer) {
        routes[key] = handler
    }

    override suspend fun send(request: MurkioHttpRequest): MurkioHttpResponse {
        val path = request.url.removePrefix("https://api.example.com")
        val call = Call(request.method, path, request.body?.let(Json::parse), request.headers)
        calls.add(call)
        val handler = routes["${call.method} ${call.path}"]
            ?: return MurkioHttpResponse(404, emptyMap(), Json.write(mapOf("details" to listOf(mapOf("code" to "test-404", "message" to path)))))
        val answer = handler(call)
        val empty = answer.status == 204 || answer.status == 304 || answer.body == null
        return MurkioHttpResponse(answer.status, answer.headers, if (empty) "" else Json.write(answer.body))
    }
}

/** A route of the fake API. The helper gives the lambda its suspend type, which `to` alone does not. */
internal fun route(key: String, handler: suspend (Call) -> Answer): Pair<String, suspend (Call) -> Answer> = key to handler

internal fun jwt(claims: Map<String, Any?>): String {
    fun encode(value: Any?): String = base64UrlEncode(Json.write(value).toByteArray(Charsets.UTF_8))
    return "${encode(mapOf("alg" to "EdDSA", "typ" to "JWT"))}.${encode(claims)}.signature"
}

private var counter = 0

/** A fresh token pair as the API returns it. */
internal fun tokens(expiresIn: Int = 900, sessionId: String = "session-1", nowMs: Long = System.currentTimeMillis()): Map<String, Any?> {
    counter++
    val seconds = nowMs / 1000
    return mapOf(
        "access_token" to jwt(
            mapOf(
                "iss" to "enterprise-auth", "sub" to "user-1", "aud" to "app-1", "sid" to sessionId,
                "roles" to listOf("user"), "iat" to seconds, "exp" to seconds + expiresIn, "jti" to "j$counter",
            ),
        ),
        "token_type" to "Bearer",
        "expires_in" to expiresIn,
        "refresh_token" to "rt_$counter",
        "session_id" to sessionId,
    )
}

internal fun tokenPair(expiresIn: Int = 900, sessionId: String = "session-1", nowMs: Long = System.currentTimeMillis()): TokenPair =
    TokenPair.fromJson(Json.parse(Json.write(tokens(expiresIn, sessionId, nowMs))))

internal fun bootstrapJson(
    document: Any? = readSpec("canvas/default.json"),
    password: Boolean = true,
    providers: List<String> = emptyList(),
    version: Int = 3,
): Map<String, Any?> = mapOf(
    "application_id" to "app-1",
    "issuer" to "enterprise-auth",
    "jwks_url" to "/v1/applications/app-1/jwks.json",
    "config" to mapOf(
        "methods" to mapOf("password" to password),
        "mfa" to mapOf("mode" to "optional"),
        "oauth_providers" to providers,
        "signup" to mapOf("login_on_verify" to true),
    ),
    "canvas" to mapOf("version" to version, "document" to document),
)

/**
 * A client for interface tests: everything runs inline on the calling thread,
 * so an action on the screen has its answer by the time Compose is idle.
 */
internal fun uiClient(
    api: MurkioTransport,
    locale: String? = "en",
    oauthRedirectUrl: String? = null,
    opener: MurkioUrlOpener = MurkioUrlOpener { false },
    key: String = "pk_test",
): MurkioClient {
    val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    scopes.add(scope)
    return MurkioClient(
        publishableKey = key,
        apiUrl = "https://api.example.com",
        localeOption = locale,
        oauthRedirectUrl = oauthRedirectUrl,
        storage = MurkioMemoryStorage(),
        transport = api,
        opener = opener,
        scope = scope,
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        now = System::currentTimeMillis,
        isForeground = { false },
    )
}

/** A fake API that serves the bootstrap plus [routes]. */
internal fun canvasApi(
    vararg routes: Pair<String, suspend (Call) -> Answer>,
    document: Any? = readSpec("canvas/default.json"),
    password: Boolean = true,
    providers: List<String> = emptyList(),
    config: Any? = null,
): FakeApi = FakeApi(
    mutableMapOf(
        route("GET /v1/client") {
            val bootstrap = bootstrapJson(document, password, providers)
            Answer.data(if (config == null) bootstrap else bootstrap + ("config" to config))
        },
        *routes,
    ),
)

/** The scopes of the clients a test created; [closeClients] cancels them. */
private val scopes = mutableListOf<CoroutineScope>()

internal fun closeClients() {
    scopes.forEach { it.cancel() }
    scopes.clear()
}

/**
 * A client that talks to [api] on the test's scheduler, keeps the session in
 * memory and takes the time from [now]. [foreground] false keeps the refresh
 * timer from calling the API behind the test's back.
 */
internal fun TestScope.newClient(
    api: MurkioTransport,
    storage: MurkioStorage = MurkioMemoryStorage(),
    locale: String? = "en",
    oauthRedirectUrl: String? = null,
    opener: MurkioUrlOpener = MurkioUrlOpener { false },
    now: () -> Long = System::currentTimeMillis,
    foreground: Boolean = false,
    key: String = "pk_test",
): MurkioClient {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    scopes.add(scope)
    return MurkioClient(
        publishableKey = key,
        apiUrl = "https://api.example.com",
        localeOption = locale,
        oauthRedirectUrl = oauthRedirectUrl,
        storage = storage,
        transport = api,
        opener = opener,
        scope = scope,
        io = dispatcher,
        now = now,
        isForeground = { foreground },
    )
}
