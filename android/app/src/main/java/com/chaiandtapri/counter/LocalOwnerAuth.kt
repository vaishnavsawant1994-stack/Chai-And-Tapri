package com.chaiandtapri.counter

import android.content.Context
import android.app.Activity
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Device-local owner gate. It is not cloud identity or staff authorization. */
object LocalSessionState {
    @Volatile var ownerDisplayName: String? = null
        private set
    @Volatile var actorId: String? = null
        private set
    @Volatile var role: StaffRole? = null
        private set
    val authenticated: Boolean get() = ownerDisplayName != null
    val isOwner: Boolean get() = authenticated && role == null
    val isManager: Boolean get() = isOwner || (authenticated && role == StaffRole.MANAGER)
    fun establish(displayName: String) { actorId = "local-owner"; role = null; ownerDisplayName = displayName }
    fun establishStaff(staffId: String, displayName: String, staffRole: StaffRole) { actorId = staffId; role = staffRole; ownerDisplayName = displayName }
    fun clear() { ownerDisplayName = null; actorId = null; role = null }
}

object SessionAccessGate {
    fun requireOwner(activity: Activity): Boolean {
        if (LocalSessionState.isOwner) return true
        activity.startActivity(Intent(activity, LocalAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        activity.finish()
        return false
    }

    fun requireManager(activity: Activity): Boolean = requireRole(activity, LocalSessionState.isManager)
    fun requireCounterAccess(activity: Activity): Boolean = requireRole(activity, LocalSessionState.authenticated)
    private fun requireRole(activity: Activity, allowed: Boolean): Boolean {
        if (allowed) return true
        activity.startActivity(Intent(activity, LocalAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        activity.finish()
        return false
    }
}

/** Defense in depth for financial mutations; screen visibility is not the authorization boundary. */
internal object LocalBusinessMutationPolicy {
    fun requireAllowed(
        authenticated: Boolean,
        activeActorId: String?,
        activeRole: StaffRole?,
        recordedActorId: String?,
        auditEvent: String
    ) {
        // Isolated model/persistence fixtures intentionally run without an app session.
        if (!authenticated) return
        if (activeActorId.isNullOrBlank() || recordedActorId != activeActorId) {
            throw SecurityException("Business mutation actor does not match the authenticated local operator.")
        }
        if (activeRole == StaffRole.CASHIER && managerOnlyEvent(auditEvent, activeActorId)) {
            throw SecurityException("Manager access is required for this business mutation.")
        }
    }

    private fun managerOnlyEvent(event: String, actorId: String?): Boolean {
        if (event.startsWith("staff_shift.started") && event.contains("staff_actor_id=$actorId")) return false
        if (event.startsWith("staff_shift.ended") && event.contains("staff_actor_id=$actorId")) return false
        return event.startsWith("remove #") ||
            event.startsWith("order.cancelled") ||
            event.startsWith("sale.refund") ||
            event.startsWith("purchase.") ||
            event.startsWith("purchase_order.") ||
            event.startsWith("supplier.") ||
            event.startsWith("expense.") ||
            event.startsWith("cash_drawer.") ||
            event.startsWith("cash_closing.") ||
            event.startsWith("staff_shift.") ||
            event.startsWith("busy_counter.estimate") ||
            event.startsWith("customer.credit_repaid") ||
            event.startsWith("stock.physical_count") ||
            event.startsWith("stock.manual_adjustment")
    }
}

object LocalOwnerAuth {
    private const val ITERATIONS = 600_000
    private const val LOCK_MS = 60_000L
    private val random = SecureRandom()
    private val dummySalt = ByteArray(16) { it.toByte() }
    private val dummyVerifier = ByteArray(32)

    fun hasOwner(context: Context): Boolean = SqliteCounterPersistence(context).use { it.localOwnerExists() }

    fun enroll(context: Context, usernameInput: String, displayNameInput: String, secret: CharArray): Boolean {
        var salt: ByteArray? = null
        var verifier: ByteArray? = null
        return try {
            val username = normalizeUsername(usernameInput)
            val displayName = displayNameInput.trim()
            require(username.length in 3..48) { "Enter an owner username with 3–48 characters." }
            require(displayName.length in 1..80) { "Enter the owner's display name." }
            require(secret.size >= 12) { "Use an owner passphrase with at least 12 characters." }
            salt = ByteArray(16).also(random::nextBytes)
            verifier = derive(secret, salt, ITERATIONS)
            SqliteCounterPersistence(context).use { db ->
                db.createLocalOwnerCredential(LocalOwnerCredential(username, displayName, salt, verifier, ITERATIONS, 0, 0, true), UUID.randomUUID().toString())
            }
        } finally { secret.fill('\u0000'); verifier?.fill(0); salt?.fill(0) }
    }

    fun authenticate(context: Context, usernameInput: String, secret: CharArray, nowMs: Long = System.currentTimeMillis()): AuthResult {
        val username = normalizeUsername(usernameInput)
        return try {
            SqliteCounterPersistence(context).use { db ->
                val credential = db.loadLocalOwnerCredential(username)
                if (credential == null) {
                    val fake = derive(secret, dummySalt, ITERATIONS); MessageDigest.isEqual(fake, dummyVerifier); fake.fill(0)
                    return AuthResult.REJECTED
                }
                if (credential.lockedUntilMs > nowMs) return AuthResult.TEMPORARILY_LOCKED
                val candidate = derive(secret, credential.salt, credential.iterations)
                val matches = credential.enabled && MessageDigest.isEqual(candidate, credential.verifier)
                candidate.fill(0)
                val recorded = db.recordLocalOwnerLogin(username, matches, UUID.randomUUID().toString(), nowMs)
                if (!recorded) return AuthResult.TEMPORARILY_LOCKED
                if (matches) {
                    if (credential.iterations < ITERATIONS) {
                        val strongerSalt = ByteArray(16).also(random::nextBytes)
                        val strongerVerifier = derive(secret, strongerSalt, ITERATIONS)
                        try { db.upgradeLocalOwnerVerifier(username, strongerSalt, strongerVerifier, ITERATIONS, UUID.randomUUID().toString()) }
                        finally { strongerSalt.fill(0); strongerVerifier.fill(0) }
                    }
                    LocalSessionState.establish(credential.displayName)
                    AuthResult.ACCEPTED
                } else {
                    val refreshed = db.loadLocalOwnerCredential(username)
                    if (refreshed?.lockedUntilMs?.let { it > nowMs } == true) AuthResult.TEMPORARILY_LOCKED else AuthResult.REJECTED
                }
            }
        } finally { secret.fill('\u0000') }
    }

    private fun normalizeUsername(input: String) = input.trim().lowercase(java.util.Locale.ROOT)
    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

enum class AuthResult { ACCEPTED, REJECTED, TEMPORARILY_LOCKED }

/** Device-local PIN authentication for named staff. It does not create a cloud identity. */
object LocalStaffAuth {
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()
    private val dummySalt = ByteArray(16) { (it + 31).toByte() }
    private val dummyVerifier = ByteArray(32)

    fun create(context: Context, staffIdInput: String, displayNameInput: String, role: StaffRole, pin: CharArray): Boolean {
        var salt: ByteArray? = null
        var verifier: ByteArray? = null
        return try {
            check(LocalSessionState.isOwner) { "Owner access is required to manage staff." }
            val staffId = staffIdInput.trim().lowercase(java.util.Locale.ROOT)
            val name = displayNameInput.trim()
            require(staffId.length in 3..40 && staffId.all { it.isLetterOrDigit() || it in "._-" }) { "Use a staff ID with 3–40 letters, numbers, dot, dash or underscore." }
            require(name.length in 1..80) { "Enter the staff member's name." }
            require(pin.size == 6 && pin.all(Char::isDigit)) { "Use a six-digit staff PIN." }
            salt = ByteArray(16).also(random::nextBytes)
            verifier = derive(pin, salt, ITERATIONS)
            SqliteCounterPersistence(context).use { db -> db.createStaffCredential(StaffCredential(StaffAccount(staffId, name, role, true, System.currentTimeMillis()), salt, verifier, ITERATIONS, 0, 0), java.util.UUID.randomUUID().toString()) }
        } finally { pin.fill('\u0000'); verifier?.fill(0); salt?.fill(0) }
    }

    fun authenticate(context: Context, staffIdInput: String, pin: CharArray, nowMs: Long = System.currentTimeMillis()): AuthResult {
        val staffId = staffIdInput.trim().lowercase(java.util.Locale.ROOT)
        return try {
            SqliteCounterPersistence(context).use { db ->
                val credential = db.loadStaffCredential(staffId)
                if (credential == null) { val fake = derive(pin, dummySalt, ITERATIONS); MessageDigest.isEqual(fake, dummyVerifier); fake.fill(0); return AuthResult.REJECTED }
                if (!credential.account.enabled) return AuthResult.REJECTED
                if (credential.lockedUntilMs > nowMs) return AuthResult.TEMPORARILY_LOCKED
                val candidate = derive(pin, credential.salt, credential.iterations)
                val matches = MessageDigest.isEqual(candidate, credential.verifier)
                candidate.fill(0)
                if (!db.recordStaffLogin(staffId, matches, UUID.randomUUID().toString(), nowMs)) return AuthResult.TEMPORARILY_LOCKED
                if (matches) { LocalSessionState.establishStaff(credential.account.staffId, credential.account.displayName, credential.account.role); AuthResult.ACCEPTED }
                else if (db.loadStaffCredential(staffId)?.lockedUntilMs?.let { it > nowMs } == true) AuthResult.TEMPORARILY_LOCKED else AuthResult.REJECTED
            }
        } finally { pin.fill('\u0000') }
    }

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
