package com.auralis.app.data.auth

import android.app.Activity
import android.content.Context
import com.auralis.app.BuildConfig
import com.auralis.app.logging.AppLog
import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.errorMessage
import com.clerk.api.network.serialization.onFailure
import com.clerk.api.network.serialization.onSuccess
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.verifyCode
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.sendEmailCode
import com.clerk.api.signup.verifyCode as verifySignUpCode
import com.clerk.api.sso.OAuthProvider
import com.clerk.api.sso.OAuthResult
import com.clerk.api.user.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A light, UI-facing snapshot of the signed-in Clerk user. */
data class ClerkAccount(
    val userId: String,
    val displayName: String,
    val email: String,
    val imageUrl: String?,
)

/** Maps the Clerk [User] onto the small shape the UI needs (name, email, avatar). */
fun User.toAccount(): ClerkAccount {
    val fullName = listOfNotNull(
        firstName?.takeIf(String::isNotBlank),
        lastName?.takeIf(String::isNotBlank),
    ).joinToString(" ")
    val displayName = fullName
        .ifBlank { username?.takeIf(String::isNotBlank).orEmpty() }
        .ifBlank { primaryEmailAddress?.emailAddress?.substringBefore('@').orEmpty() }
        .ifBlank { "AURALIS listener" }
    return ClerkAccount(
        userId = id,
        displayName = displayName,
        email = primaryEmailAddress?.emailAddress.orEmpty(),
        imageUrl = imageUrl.takeIf { hasImage },
    )
}

/**
 * A pending email-code verification. It belongs to a sign-in when the address already had an
 * account, or to a sign-up when the code was sent to create one. Opaque to the UI: it is only
 * carried from [ClerkAuthRepository.requestEmailCode] back into
 * [ClerkAuthRepository.verifyEmailCode].
 */
class EmailCodeChallenge internal constructor(
    internal val signIn: SignIn? = null,
    internal val signUp: SignUp? = null,
)

/**
 * Thin wrapper over the Clerk native SDK, owned by [com.auralis.app.AppContainer].
 *
 * Clerk needs a publishable key that only the app owner can create (Clerk has no accountless keys
 * for Android), so it is injected at build time via `CLERK_PUBLISHABLE_KEY`. When that is blank
 * [configured] is false and every accessor degrades to a safe "signed-out, but do nothing" value,
 * which lets the rest of the app keep working exactly as it did before authentication existed.
 *
 * This is deliberately the *only* file that touches the Clerk API, so the dependency stays easy to
 * swap or remove.
 */
class ClerkAuthRepository(context: Context) {
    private val appContext = context.applicationContext
    private val publishableKey: String = BuildConfig.CLERK_PUBLISHABLE_KEY

    /** False when no publishable key was supplied at build time; the app then skips the login gate. */
    val configured: Boolean get() = publishableKey.isNotBlank()

    @Volatile
    private var initialized = false

    /** Called once from Application.onCreate. A no-op when Clerk is not configured. */
    fun initialize() {
        if (!configured) {
            AppLog.i(TAG, "Clerk disabled: no CLERK_PUBLISHABLE_KEY configured")
            return
        }
        if (initialized) return
        runCatching { Clerk.initialize(appContext, publishableKey) }
            .onSuccess {
                initialized = true
                AppLog.i(TAG, "Clerk initialized")
            }
            .onFailure { AppLog.e(TAG, "Clerk initialization failed", it) }
    }

    /** Hands the current Activity to Clerk so browser-based OAuth (Google) can return to the app. */
    fun attachActivity(activity: Activity) {
        if (!configured) return
        runCatching { Clerk.attachActivity(activity) }
            .onFailure { AppLog.w(TAG, "Clerk.attachActivity skipped", it) }
    }

    /** Emits true once the SDK finished loading the environment and any persisted session. */
    val isInitialized: Flow<Boolean>
        get() = if (configured) Clerk.isInitialized else flowOf(false)

    /** The signed-in user, or null. Empty when Clerk is not configured. */
    val user: Flow<User?>
        get() = if (configured) Clerk.userFlow else flowOf(null)

    /** Non-null when initialization could not complete (e.g. offline first launch). */
    val initializationError: Flow<Throwable?>
        get() = if (configured) Clerk.initializationError else flowOf(null)

    /**
     * Starts the Google OAuth flow. Clerk opens the browser and suspends until the redirect comes
     * back; when the Google account is new to the instance the SDK turns the flow into a sign-up.
     * Either way the session it produced is activated here, because the SDK only *returns* it.
     */
    suspend fun signInWithGoogle(): Result<Unit> =
        call("Google sign-in") { Clerk.auth.signInWithOAuth(OAuthProvider.GOOGLE) }
            .fold(
                onSuccess = { activateOAuthResult(it) },
                onFailure = { Result.failure(it) },
            )

    /**
     * Emails a one-time code and returns the in-progress challenge to verify against. An address
     * with no account yet is signed up instead, because Clerk's email-code sign-in can only sign
     * in users that already exist.
     */
    suspend fun requestEmailCode(emailAddress: String): Result<EmailCodeChallenge> {
        if (!configured) return Result.failure(IllegalStateException("Clerk is not configured"))
        return runCatching {
            when (val signIn = Clerk.auth.signInWithOtp { email = emailAddress }) {
                is ClerkResult.Success -> EmailCodeChallenge(signIn = signIn.value)
                is ClerkResult.Failure ->
                    if (signIn.error.saysIdentifierNotFound()) {
                        AppLog.i(TAG, "No account for $emailAddress; starting an email sign-up")
                        startEmailCodeSignUp(emailAddress)
                    } else {
                        AppLog.w(TAG, "email code request failed: ${signIn.errorMessage}")
                        throw IllegalStateException(signIn.errorMessage)
                    }
            }
        }
    }

    /** Verifies the emailed code and activates the session it creates. */
    suspend fun verifyEmailCode(challenge: EmailCodeChallenge, code: String): Result<Unit> {
        val signIn = challenge.signIn
        val signUp = challenge.signUp
        val sessionId = when {
            signIn != null ->
                call("email code verification") { signIn.verifyCode(code) }
                    .fold({ it.createdSessionId }, { return Result.failure(it) })
            signUp != null ->
                call("email code verification") {
                    signUp.verifySignUpCode(code, VerificationType.EMAIL)
                }.fold({ it.createdSessionId }, { return Result.failure(it) })
            else -> return Result.failure(IllegalStateException("No email code request is pending"))
        }
        return activateSession(sessionId, "email code sign-in")
    }

    /** Creates an email-only sign-up and sends its verification code. */
    private suspend fun startEmailCodeSignUp(emailAddress: String): EmailCodeChallenge {
        val created = when (val result = Clerk.auth.signUp { email = emailAddress }) {
            is ClerkResult.Success -> result.value
            is ClerkResult.Failure -> {
                AppLog.w(TAG, "sign-up create failed: ${result.errorMessage}")
                throw IllegalStateException(result.errorMessage)
            }
        }
        val prepared = when (val result = created.sendEmailCode()) {
            is ClerkResult.Success -> result.value
            is ClerkResult.Failure -> {
                AppLog.w(TAG, "sign-up code send failed: ${result.errorMessage}")
                throw IllegalStateException(result.errorMessage)
            }
        }
        return EmailCodeChallenge(signUp = prepared)
    }

    /**
     * Activates the session an OAuth round-trip produced. The SDK does not start the session for
     * the caller, so without this the app would stay on the login gate even after a successful
     * sign-in.
     */
    private suspend fun activateOAuthResult(result: OAuthResult): Result<Unit> {
        val sessionId = when (val outcome = result.outcome) {
            is OAuthResult.Outcome.SignIn -> outcome.signIn.createdSessionId
            is OAuthResult.Outcome.SignUp -> outcome.signUp.createdSessionId
            OAuthResult.Outcome.Empty -> null
        }
        if (!sessionId.isNullOrBlank()) return activateSession(sessionId, "sign-in session")

        // No session normally means the sign-up still needs fields the login screen does not collect.
        val missing = (result.outcome as? OAuthResult.Outcome.SignUp)
            ?.signUp?.missingFields?.filter(String::isNotBlank).orEmpty()
        AppLog.w(TAG, "OAuth returned no session; missing=$missing")
        val message = if (missing.isEmpty()) {
            "Google sign-in didn't complete. Please try again."
        } else {
            "Your account still needs: ${missing.joinToString(", ")}"
        }
        return Result.failure(IllegalStateException(message))
    }

    /** Starts the Clerk session so [user] begins emitting the signed-in account. */
    private suspend fun activateSession(sessionId: String?, label: String): Result<Unit> {
        if (sessionId.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Verified, but no session was created. Please try again.")
            )
        }
        return call(label) { Clerk.auth.setActive(sessionId) }.map { }
    }

    /** Signs out the active session; [Clerk.userFlow] then emits null. */
    suspend fun signOut(): Result<Unit> = call("sign out") { Clerk.auth.signOut() }.map { }

    /**
     * Runs one Clerk call and folds its [ClerkResult] into a Kotlin [Result], so callers only deal
     * with success/failure plus a readable message.
     */
    private suspend fun <T : Any> call(
        label: String,
        block: suspend () -> ClerkResult<T, ClerkErrorResponse>,
    ): Result<T> {
        if (!configured) return Result.failure(IllegalStateException("Clerk is not configured"))
        var outcome: Result<T> = Result.failure(IllegalStateException("Clerk call did not complete"))
        runCatching { block() }
            .onSuccess { result ->
                result
                    .onSuccess { outcome = Result.success(it) }
                    .onFailure {
                        AppLog.w(TAG, "$label failed: ${it.errorMessage}")
                        outcome = Result.failure(IllegalStateException(it.errorMessage))
                    }
            }
            .onFailure { error ->
                AppLog.e(TAG, "$label threw", error)
                outcome = Result.failure(error)
            }
        return outcome
    }

    /**
     * True when the API reported that no account matches the given identifier, which is the signal
     * to switch the email-code flow from sign-in to sign-up.
     */
    private fun ClerkErrorResponse?.saysIdentifierNotFound(): Boolean =
        this?.errors.orEmpty().any {
            it.code == "form_identifier_not_found" || it.code == "identifier_not_found"
        }

    private companion object {
        const val TAG = "Clerk"
    }
}
