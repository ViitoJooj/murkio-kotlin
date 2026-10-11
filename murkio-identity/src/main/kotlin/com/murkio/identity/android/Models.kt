package com.murkio.identity.android

import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.base64Decode
import com.murkio.identity.android.internal.obj
import com.murkio.identity.android.internal.strings

/** The tokens of a session, as the API returns them. */
public class TokenPair(
    public val accessToken: String,
    public val refreshToken: String,
    /** Seconds until the access token expires. */
    public val expiresIn: Long,
    public val sessionId: String,
) {
    internal companion object {
        /** Reads a token pair; anything else is an `unknown` error. */
        fun fromJson(json: Any?): TokenPair {
            val map = json.obj()
            val access = map?.get("access_token") as? String
            val refresh = map?.get("refresh_token") as? String
            val expires = map?.get("expires_in") as? Double
            val session = map?.get("session_id") as? String
            if (access == null || refresh == null || expires == null || session == null) {
                throw MurkioException("unknown", 0, message = "unexpected token response")
            }
            return TokenPair(access, refresh, expires.toLong(), session)
        }
    }
}

/** The two-step verification the API asks for before giving a session. */
public class MfaChallenge(
    public val token: String,
    /** The application requires 2FA and the user has no authenticator yet. */
    public val enrollmentRequired: Boolean,
)

/** What a sign-in returns: a session, or the 2FA challenge to pass first. */
public sealed class LoginResult {
    public class Session(public val tokens: TokenPair) : LoginResult()

    public class Mfa(public val mfa: MfaChallenge) : LoginResult()

    internal companion object {
        fun fromJson(json: Any?): LoginResult {
            val mfa = json.obj()?.get("mfa").obj()
            val token = mfa?.get("token") as? String
            if (mfa != null && token != null) {
                return Mfa(MfaChallenge(token, mfa["enrollment_required"] == true))
            }
            return Session(TokenPair.fromJson(json))
        }
    }
}

/** The signed-in user's profile (`POST /v1/me`). */
public class MurkioUser(
    public val id: String,
    public val sessionId: String,
    /** Null for a user that only has a social sign-in without an email. */
    public val email: String?,
    public val emailVerified: Boolean,
    public val roles: List<String>,
    public val mfaEnabled: Boolean,
    /** RFC 3339, as the API sends it. */
    public val createdAt: String?,
) {
    internal companion object {
        fun fromJson(json: Any?): MurkioUser {
            val map = json.obj()
            val id = map?.get("id") as? String
                ?: throw MurkioException("unknown", 0, message = "unexpected profile")
            return MurkioUser(
                id = id,
                sessionId = map["session_id"] as? String ?: "",
                email = map["email"] as? String,
                emailVerified = map["email_verified"] == true,
                roles = map["roles"].strings(),
                mfaEnabled = map["mfa_enabled"] == true,
                createdAt = map["created_at"] as? String,
            )
        }
    }
}

/** What the application turned on, as the login screen needs to know. */
public class PublicConfig(
    public val passwordEnabled: Boolean = true,
    /** `off`, `optional` or `required`. */
    public val mfaMode: String = "optional",
    public val oauthProviders: List<String> = emptyList(),
    public val loginOnVerify: Boolean = true,
) {
    internal companion object {
        fun fromJson(json: Any?): PublicConfig {
            val map = json.obj() ?: return PublicConfig()
            return PublicConfig(
                passwordEnabled = map["methods"].obj()?.get("password") != false,
                mfaMode = map["mfa"].obj()?.get("mode") as? String ?: "off",
                oauthProviders = map["oauth_providers"].strings(),
                loginOnVerify = map["signup"].obj()?.get("login_on_verify") != false,
            )
        }
    }
}

/** The answer of `GET /v1/client`: the application, its public config and the published canvas. */
public class ClientBootstrap internal constructor(
    public val applicationId: String,
    public val config: PublicConfig,
    /** 0 is the default canvas: the application never published one. */
    public val canvasVersion: Int,
    /** The canvas as it came from the API (parsed JSON). It is validated again before it is drawn. */
    internal val canvasDocument: Any?,
) {
    internal companion object {
        fun fromJson(json: Any?): ClientBootstrap {
            val map = json.obj()
            val id = map?.get("application_id") as? String
            val canvas = map?.get("canvas").obj()
            if (map == null || id == null || canvas == null) {
                throw MurkioException("unknown", 0, message = "unexpected bootstrap")
            }
            return ClientBootstrap(
                applicationId = id,
                config = PublicConfig.fromJson(map["config"]),
                canvasVersion = (canvas["version"] as? Double)?.toInt() ?: 0,
                canvasDocument = canvas["document"],
            )
        }
    }
}

/** The claims of an access token the app may want to read. */
public class AccessClaims(
    /** The user id. */
    public val subject: String,
    public val sessionId: String,
    /** Seconds since the epoch. */
    public val expiresAt: Long,
    public val roles: List<String>,
)

/**
 * Reads the claims of an access token **without checking its signature**. The
 * app only uses them for display and timing; every decision that matters is
 * taken by the API or by a backend that verifies the token.
 */
public fun decodeClaims(token: String): AccessClaims? {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload = base64Decode(parts[1]) ?: return null
    val claims = Json.parseOrNull(String(payload, Charsets.UTF_8)).obj() ?: return null
    val exp = claims["exp"] as? Double ?: return null
    val sub = claims["sub"] as? String ?: return null
    return AccessClaims(sub, claims["sid"] as? String ?: "", exp.toLong(), claims["roles"].strings())
}
