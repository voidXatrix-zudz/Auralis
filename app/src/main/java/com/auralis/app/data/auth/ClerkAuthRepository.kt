package com.auralis.app.data.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** App-level lifecycle of one Google browser sign-in, independent of any Activity/ViewModel. */
sealed interface GoogleSignInState {
    data object Idle : GoogleSignInState
    data object Running : GoogleSignInState
    data object Completed : GoogleSignInState
    data class Failed(val message: String) : GoogleSignInState
}

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
    /**
     * Survives process death, unlike the SDK's in-memory pending flow. It records that a browser
     * sign-in was started, so a launch that finds it still set can explain the interrupted attempt
     * instead of silently showing a fresh login screen.
     */
    private val authPrefs = appContext.getSharedPreferences("clerk_auth", Context.MODE_PRIVATE)

    /**
     * Owns the browser await. Living in the application, not `viewModelScope`, means an Activity
     * recreation while Chrome is in front cannot cancel the flow before the session is activated.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _googleSignIn = MutableStateFlow<GoogleSignInState>(GoogleSignInState.Idle)
    private val signInGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** Observable progress of the browser sign-in, so a recreated UI can resume its spinner. */
    val googleSignIn: StateFlow<GoogleSignInState> = _googleSignIn.asStateFlow()

    /** False when no publishable key was supplied at build time; the app then skips the login gate. */
    val configured: Boolean get() = publishableKey.isNotBlank()

    /**
     * The native callback the SDK derives from the application id (`clerk://<package>.callback`).
     * Logged so that a mismatch with the Clerk instance's allowed redirect URLs is visible.
     */
    val redirectUrl: String get() = "clerk://${appContext.packageName}.callback"

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
                // The instance type and callback URL are logged so a dev/live mismatch, or a
                // callback the Clerk instance does not recognise, is visible in the Logs screen.
                val instance = runCatching { Clerk.instanceEnvironmentType }.getOrNull()
                AppLog.i(TAG, "Clerk initialized instance=$instance redirect=$redirectUrl")
            }
            .onFailure { AppLog.e(TAG, "Clerk initialization failed", it) }
    }

    /** True once the SDK finished loading the environment and any persisted session. */
    fun isReady(): Boolean = configured && runCatching { Clerk.isInitialized.value }.getOrDefault(false)

    /**
     * Nudges a stalled or failed initialization. A no-op (returns false) when Clerk is already
     * ready or was never configured, so it is safe to call on every resume.
     */
    fun reinitialize(): Boolean =
        if (!configured) false
        else runCatching { Clerk.reinitialize() }.getOrDefault(false)

    /** Hands the current Activity to Clerk so browser-based OAuth (Google) can return to the app. */
    fun attachActivity(activity: Activity) {
        if (!configured) return
        runCatching { Clerk.attachActivity(activity) }
            .onSuccess { AppLog.d(TAG, "Activity attached for OAuth callbacks") }
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
     * Completes a Clerk deep-link callback that reached the Activity. The SDK's own receiver
     * normally handles this; this is a defensive second path when the app is relaunched by the
     * redirect. Returns true when the URI belonged to a Clerk flow.
     */
    suspend fun handleCallback(uri: Uri): Boolean {
        if (!configured) return false
        val handled = runCatching { Clerk.auth.handle(uri) }.getOrDefault(false)
        AppLog.i(TAG, "Deep-link callback handled=$handled")
        return handled
    }

    /** PII-free labels for Clerk's auth events, so the Logs screen can show them without user data. */
    val eventLabels: Flow<String>
        get() = if (configured) {
            Clerk.auth.events.map { it::class.simpleName ?: "event" }
        } else {
            emptyFlow()
        }

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
     * Starts the browser sign-in on the application scope. Because the await is not tied to an
     * Activity, a recreation while Chrome is in front still ends with the session activated.
     * Each call supersedes the previous attempt; a stale attempt can never overwrite the state.
     */
    fun startGoogleSignIn() {
        if (!configured) {
            _googleSignIn.value = GoogleSignInState.Failed("Clerk is not configured")
            return
        }
        val generation = signInGeneration.incrementAndGet()
        // Set before the browser opens; cleared only by this generation, and left set on process
        // death so the next launch can report the interrupted attempt.
        authPrefs.edit().putBoolean(KEY_SIGN_IN_PENDING, true).apply()
        _googleSignIn.value = GoogleSignInState.Running
        AppLog.i(TAG, "Google sign-in started; opening the browser (redirect=$redirectUrl)")
        appScope.launch {
            try {
                val outcome = withTimeoutOrNull(GOOGLE_SIGN_IN_TIMEOUT_MS) { signInWithGoogle() }
                if (generation != signInGeneration.get()) return@launch
                val error = outcome?.exceptionOrNull()
                _googleSignIn.value = when {
                    // `call` folds a cancelled coroutine into a failure, so the ceiling and a real
                    // cancellation both land here.
                    outcome == null || error is CancellationException -> {
                        AppLog.w(TAG, "Google sign-in timed out after $GOOGLE_SIGN_IN_TIMEOUT_MS ms")
                        GoogleSignInState.Failed("Google sign-in timed out. Please try again.")
                    }
                    outcome.isSuccess -> {
                        AppLog.i(TAG, "Google sign-in completed; session activated")
                        GoogleSignInState.Completed
                    }
                    else -> {
                        val message = error?.localizedMessage ?: "Google sign-in failed"
                        AppLog.w(TAG, "Google sign-in failed: $message")
                        GoogleSignInState.Failed(message)
                    }
                }
            } catch (cancellation: CancellationException) {
                if (generation == signInGeneration.get()) {
                    AppLog.w(TAG, "Google sign-in was cancelled")
                    _googleSignIn.value = GoogleSignInState.Failed("Google sign-in was cancelled")
                }
                throw cancellation
            } catch (error: Exception) {
                if (generation == signInGeneration.get()) {
                    AppLog.e(TAG, "Google sign-in threw", error)
                    _googleSignIn.value = GoogleSignInState.Failed(
                        error.localizedMessage ?: "Google sign-in failed"
                    )
                }
            } finally {
                if (generation == signInGeneration.get()) clearSignInMarker()
            }
        }
    }

    /** Returns a finished sign-in state to Idle once the UI consumed it; never cuts a running flow. */
    fun acknowledgeSignInState() {
        if (_googleSignIn.value != GoogleSignInState.Running) {
            _googleSignIn.value = GoogleSignInState.Idle
        }
    }

    /** True once if a previous sign-in never reported back, e.g. the process died in the browser. */
    fun consumeInterruptedSignIn(): Boolean =
        authPrefs.getBoolean(KEY_SIGN_IN_PENDING, false).also { pending ->
            if (pending) clearSignInMarker()
        }

    /** Clears the "a sign-in is in flight" marker; safe to call at any time. */
    fun clearSignInMarker() {
        authPrefs.edit().putBoolean(KEY_SIGN_IN_PENDING, false).apply()
    }

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
        if (!sessionId.isNullOrBlank()) {
            AppLog.i(TAG, "OAuth callback accepted; activating the session")
            return activateSession(sessionId, "sign-in session")
        }

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
            AppLog.w(TAG, "Session activation skipped for $label: no session id")
            return Result.failure(
                IllegalStateException("Verified, but no session was created. Please try again.")
            )
        }
        val outcome = call(label) { Clerk.auth.setActive(sessionId) }.map { }
        outcome
            .onSuccess { AppLog.i(TAG, "Session activated ($label)") }
            .onFailure { AppLog.w(TAG, "Session activation failed ($label): ${it.localizedMessage}") }
        return outcome
    }

    /** Signs out the active session; [Clerk.userFlow] then emits null. */
    suspend fun signOut(): Result<Unit> =
        call("sign out") { Clerk.auth.signOut() }
            .map { }
            .onSuccess { AppLog.i(TAG, "Signed out of Clerk") }

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
        const val KEY_SIGN_IN_PENDING = "google_sign_in_pending"
        /** Absolute ceiling for one browser round-trip; the resume grace catches normal drops. */
        const val GOOGLE_SIGN_IN_TIMEOUT_MS = 600_000L
    }
}
