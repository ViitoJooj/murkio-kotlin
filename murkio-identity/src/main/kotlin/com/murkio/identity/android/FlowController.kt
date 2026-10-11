package com.murkio.identity.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the user is in the sign-in. [name] is the same in every Murkio SDK. */
public sealed class FlowStep(public val name: String) {
    /** The sign-in (or sign-up) screen: the canvas, or the app's own form. */
    public object SignIn : FlowStep("sign_in")

    /** The code sent to the email. [needsPassword] is true when the SDK no longer has the password the confirmation needs. */
    public class VerifyEmail(public val email: String, public val needsPassword: Boolean = false) : FlowStep("verify_email")

    /** The code of the authenticator app (or a recovery code). */
    public class Mfa internal constructor(internal val token: String) : FlowStep("mfa")

    /** The application requires 2FA: the user adds [secret] to an authenticator. */
    public class MfaEnroll internal constructor(
        internal val token: String,
        public val secret: String,
        public val otpauthUri: String,
    ) : FlowStep("mfa_enroll")

    /** The recovery codes to save. The session starts after the user confirms. */
    public class RecoveryCodes(public val codes: List<String>) : FlowStep("recovery_codes")

    /** "Forgot password": asks for the email. */
    public object Forgot : FlowStep("forgot")

    /** The code sent to [email] and the new password. */
    public class Reset(public val email: String) : FlowStep("reset")

    /** The session is stored: the user is signed in. */
    public object Done : FlowStep("done")
}

/**
 * What a sign-in screen draws: the step, whether a request is running, the
 * error of the last action and a notice (the key of a built-in string).
 */
public class FlowState(
    public val step: FlowStep = FlowStep.SignIn,
    public val busy: Boolean = false,
    public val error: MurkioException? = null,
    public val notice: String? = null,
) {
    /** True for the steps a built-in screen draws (everything between the sign-in screen and the session). */
    public val inStep: Boolean get() = step !is FlowStep.SignIn && step !is FlowStep.Done
}

/** What the user typed on the sign-in screen. */
public class MurkioCredentials(
    public val email: String,
    public val password: String,
    public val keepLogged: Boolean = false,
)

private fun normalizeEmail(email: String): String = email.trim().lowercase()

/**
 * FlowController walks a user through signing in: the password login, the
 * signup and its email code, the 2FA challenge (and the forced enrollment),
 * the password reset and the social sign-in. The canvas and the standalone
 * composables draw its [state]; it holds the password only in memory, between
 * the signup and the email code, because the confirmation needs it.
 */
public class FlowController(private val client: MurkioClient) {
    private val current = MutableStateFlow(FlowState())
    private var email = ""
    private var password = ""
    private var keepLogged = false
    private var pendingSession: TokenPair? = null

    public val state: StateFlow<FlowState> = current.asStateFlow()

    /** The email of the attempt in course, normalized. */
    public val currentEmail: String get() = email

    public suspend fun login(credentials: MurkioCredentials): Unit = perform {
        remember(credentials)
        try {
            handle(client.api.login(email, password))
        } catch (error: MurkioException) {
            if (error.code != "rsb-07") throw error
            client.api.sendVerification(email, client.locale)
            go(FlowStep.VerifyEmail(email))
        }
    }

    public suspend fun register(credentials: MurkioCredentials): Unit = perform {
        remember(credentials)
        client.api.signup(email, password, client.locale)
        go(FlowStep.VerifyEmail(email))
    }

    public suspend fun confirmEmail(code: String, password: String? = null): Unit = perform {
        if (!password.isNullOrEmpty()) this.password = password
        if (this.password.isEmpty()) {
            go(FlowStep.VerifyEmail(email, needsPassword = true))
            return@perform
        }
        val result = client.api.confirmVerification(email, code.trim(), this.password)
        if (result != null) {
            handle(result)
            return@perform
        }
        this.password = ""
        go(FlowStep.SignIn, notice = "verified.signin")
    }

    public suspend fun resendEmail(): Unit = perform {
        client.api.sendVerification(email, client.locale)
        update(notice = "verify.resent")
    }

    public suspend fun verifyMfa(code: String): Unit = perform {
        val step = current.value.step as? FlowStep.Mfa ?: return@perform
        complete(client.api.verifyMfa(step.token, code.trim()))
    }

    public suspend fun confirmEnrollment(code: String): Unit = perform {
        val step = current.value.step as? FlowStep.MfaEnroll ?: return@perform
        val result = client.api.confirmMfaEnrollment(step.token, code.trim())
        pendingSession = result.session
        go(FlowStep.RecoveryCodes(result.recoveryCodes))
    }

    /** The user saved the recovery codes: the session starts. */
    public suspend fun finishRecovery(): Unit = perform {
        pendingSession?.let { complete(it) }
    }

    public fun forgot(email: String = "") {
        if (current.value.busy) return
        if (email.isNotBlank()) this.email = normalizeEmail(email)
        go(FlowStep.Forgot)
    }

    public suspend fun requestReset(email: String): Unit = perform {
        this.email = normalizeEmail(email)
        client.api.requestPasswordReset(this.email, client.locale)
        go(FlowStep.Reset(this.email))
    }

    public suspend fun resetPassword(code: String, password: String): Unit = perform {
        client.api.confirmPasswordReset(email, code.trim(), password)
        go(FlowStep.SignIn, notice = "reset.done")
    }

    /** Starts a social sign-in: the browser opens the provider. */
    public suspend fun oauth(provider: String, keepLogged: Boolean): Unit = perform {
        client.startOAuth(provider, keepLogged)
    }

    public fun back() {
        if (current.value.busy) return
        password = ""
        pendingSession = null
        go(FlowStep.SignIn)
    }

    /**
     * A social sign-in came back to the app: it either left a 2FA challenge
     * for this screen to continue, or an error to show.
     */
    public suspend fun takeOAuthReturn() {
        val pending = client.takePendingMfa()
        if (pending != null) {
            keepLogged = pending.keepLogged
            perform { challenge(pending.mfa) }
            return
        }
        client.takeOAuthError()?.let { update(error = it) }
    }

    private fun remember(credentials: MurkioCredentials) {
        email = normalizeEmail(credentials.email)
        password = credentials.password
        keepLogged = credentials.keepLogged
    }

    private suspend fun handle(result: LoginResult) {
        when (result) {
            is LoginResult.Session -> complete(result.tokens)
            is LoginResult.Mfa -> challenge(result.mfa)
        }
    }

    private suspend fun challenge(mfa: MfaChallenge) {
        if (!mfa.enrollmentRequired) {
            go(FlowStep.Mfa(mfa.token))
            return
        }
        val enrollment = client.api.startMfaEnrollment(mfa.token)
        go(FlowStep.MfaEnroll(mfa.token, enrollment.secret, enrollment.otpauthUri))
    }

    private suspend fun complete(tokens: TokenPair) {
        client.sessions.setSession(tokens, keepLogged)
        password = ""
        pendingSession = null
        go(FlowStep.Done)
    }

    private fun go(step: FlowStep, notice: String? = null) {
        current.value = FlowState(step, current.value.busy, null, notice)
    }

    private fun update(error: MurkioException? = null, notice: String? = null) {
        current.value = FlowState(current.value.step, current.value.busy, error, notice)
    }

    private suspend fun perform(task: suspend () -> Unit) {
        if (current.value.busy) return
        current.value = FlowState(current.value.step, busy = true)
        try {
            task()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current.value = FlowState(current.value.step, busy = true, error = MurkioException.from(error))
        } finally {
            val last = current.value
            current.value = FlowState(last.step, busy = false, error = last.error, notice = last.notice)
        }
    }
}
