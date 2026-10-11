package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.obj
import com.murkio.identity.android.internal.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A request the SDK sends to the identity API. */
public class MurkioHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    /** The JSON body, or null. */
    public val body: String?,
)

/** The answer to a [MurkioHttpRequest]. Header names are lowercase. */
public class MurkioHttpResponse(
    public val status: Int,
    public val headers: Map<String, String>,
    public val body: String,
)

/**
 * How the SDK reaches the network. The default uses `HttpURLConnection`; an
 * app (or a test) can pass its own. Throw any exception when the request got
 * no answer: the SDK reports it as the `network` error.
 */
public fun interface MurkioTransport {
    public suspend fun send(request: MurkioHttpRequest): MurkioHttpResponse
}

/** The secret and the link of a 2FA enrollment. */
public class MfaEnrollment(public val secret: String, public val otpauthUri: String)

/** The recovery codes and the session of a confirmed 2FA enrollment. */
public class MfaEnrollmentResult(public val recoveryCodes: List<String>, public val session: TokenPair)

internal class BootstrapResponse(val notModified: Boolean, val etag: String?, val data: ClientBootstrap?)

internal class ApiResponse(val status: Int, val data: Any?, val headers: Map<String, String>)

/**
 * ApiClient talks to the identity API with the publishable key. Every method
 * maps one route; errors come back as [MurkioException] with the API code.
 * It holds no state: tokens live in the session manager.
 */
public class ApiClient internal constructor(
    private val publishableKey: String,
    apiUrl: String?,
    private val transport: MurkioTransport,
) {
    /** The base URL of the identity API, without a trailing slash. */
    public val apiUrl: String = (apiUrl ?: DEFAULT_API_URL).trimEnd('/')

    internal suspend fun request(
        method: String,
        path: String,
        body: Map<String, Any?>? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val all = LinkedHashMap<String, String>()
        all["Authorization"] = "Bearer $publishableKey"
        all.putAll(headers)
        if (body != null) all["Content-Type"] = "application/json"

        val response = try {
            transport.send(MurkioHttpRequest(method, apiUrl + path, all, body?.let(Json::write)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: MurkioException) {
            throw error
        } catch (error: Exception) {
            // No answer at all (offline, DNS, TLS, timeout): the one error the app may want to retry.
            throw MurkioException("network", 0, message = error.message ?: error.toString(), cause = error)
        }

        val json = if (response.status == 204 || response.status == 304) null else Json.parseOrNull(response.body)
        if (response.status !in 200..299 && response.status != 304) {
            throw MurkioException.fromResponse(response.status, json)
        }
        return ApiResponse(response.status, json.obj()?.get("data"), response.headers)
    }

    internal suspend fun bootstrap(etag: String?): BootstrapResponse {
        val response = request("GET", "/v1/client", headers = if (etag == null) emptyMap() else mapOf("If-None-Match" to etag))
        return BootstrapResponse(
            notModified = response.status == 304,
            etag = response.headers["etag"],
            data = response.data?.let(ClientBootstrap::fromJson),
        )
    }

    public suspend fun signup(email: String, password: String, locale: String) {
        request("POST", "/v1/signup", mapOf("email" to email, "password" to password, "locale" to locale))
    }

    public suspend fun login(email: String, password: String): LoginResult =
        LoginResult.fromJson(request("POST", "/v1/login", mapOf("email" to email, "password" to password)).data)

    public suspend fun sendVerification(email: String, locale: String) {
        request("POST", "/v1/email/verification", mapOf("email" to email, "locale" to locale))
    }

    /**
     * Confirms the email. Returns the session (or the 2FA challenge), or null
     * when the application does not sign in on verify.
     */
    public suspend fun confirmVerification(email: String, code: String, password: String): LoginResult? {
        val response = request(
            "POST",
            "/v1/email/verification/confirm",
            mapOf("email" to email, "code" to code, "password" to password),
        )
        if (response.status == 204 || response.data == null) return null
        return LoginResult.fromJson(response.data)
    }

    public suspend fun requestPasswordReset(email: String, locale: String) {
        request("POST", "/v1/password/reset", mapOf("email" to email, "locale" to locale))
    }

    public suspend fun confirmPasswordReset(email: String, code: String, password: String) {
        request("POST", "/v1/password/reset/confirm", mapOf("email" to email, "code" to code, "password" to password))
    }

    public suspend fun refresh(refreshToken: String): TokenPair =
        TokenPair.fromJson(request("POST", "/v1/token/refresh", mapOf("refresh_token" to refreshToken)).data)

    public suspend fun logout(refreshToken: String) {
        request("POST", "/v1/logout", mapOf("refresh_token" to refreshToken))
    }

    public suspend fun me(accessToken: String): MurkioUser =
        MurkioUser.fromJson(request("POST", "/v1/me", mapOf("access_token" to accessToken)).data)

    public suspend fun verifyMfa(mfaToken: String, code: String): TokenPair =
        TokenPair.fromJson(request("POST", "/v1/mfa/challenge/verify", mapOf("mfa_token" to mfaToken, "code" to code)).data)

    public suspend fun startMfaEnrollment(mfaToken: String): MfaEnrollment {
        val data = request("POST", "/v1/mfa/challenge/enroll", mapOf("mfa_token" to mfaToken)).data.obj()
        val secret = data?.get("secret") as? String
        val uri = data?.get("otpauth_uri") as? String
        if (secret == null || uri == null) throw MurkioException("unknown", 0, message = "unexpected enrollment")
        return MfaEnrollment(secret, uri)
    }

    public suspend fun confirmMfaEnrollment(mfaToken: String, code: String): MfaEnrollmentResult {
        val data = request("POST", "/v1/mfa/challenge/enroll/confirm", mapOf("mfa_token" to mfaToken, "code" to code)).data.obj()
            ?: throw MurkioException("unknown", 0, message = "unexpected enrollment")
        return MfaEnrollmentResult(data["recovery_codes"].strings(), TokenPair.fromJson(data["session"]))
    }

    /** Asks where to send the user for a social sign-in. */
    public suspend fun authorizeOAuth(provider: String, redirectUrl: String, state: String, codeChallenge: String): String {
        val data = request(
            "POST",
            "/v1/oauth/${URLEncoder.encode(provider, "UTF-8")}/authorize",
            mapOf(
                "redirect_url" to redirectUrl,
                "state" to state,
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256",
            ),
        ).data.obj()
        return data?.get("authorization_url") as? String
            ?: throw MurkioException("unknown", 0, message = "unexpected authorization")
    }

    public suspend fun exchangeOAuthCode(code: String, codeVerifier: String): LoginResult =
        LoginResult.fromJson(request("POST", "/v1/oauth/token", mapOf("code" to code, "code_verifier" to codeVerifier)).data)

    public companion object {
        /** Where the identity API lives unless the app says otherwise. */
        public const val DEFAULT_API_URL: String = "https://api.mukio.com"
    }
}

/** The default transport: `HttpURLConnection` on the IO dispatcher. */
internal class UrlConnectionTransport : MurkioTransport {
    override suspend fun send(request: MurkioHttpRequest): MurkioHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            connection.setRequestProperty("Accept", "application/json")
            val body = request.body
            if (body != null) {
                // Written without streaming mode on purpose: in streaming mode a 401
                // answer cannot be read (the connection refuses to "retry" it), and
                // the wrong-password error would arrive without its code.
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = HashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values.isNotEmpty()) headers[name.lowercase()] = values.last()
            }
            MurkioHttpResponse(status, headers, text)
        } finally {
            connection.disconnect()
        }
    }
}
