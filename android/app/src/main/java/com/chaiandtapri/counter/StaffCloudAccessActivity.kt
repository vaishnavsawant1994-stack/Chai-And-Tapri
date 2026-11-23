package com.chaiandtapri.counter

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executors

/** Links an already-authenticated local staff PIN to a matching invited server membership. */
class StaffCloudAccessActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tapri-staff-cloud-link").apply { isDaemon = true } }
    private lateinit var content: LinearLayout
    private lateinit var endpoint: EditText
    private lateinit var invitationToken: EditText
    private lateinit var displayName: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private var busy = false
    private val actorId: String? get() = LocalSessionState.actorId
    private val staffRole: StaffRole? get() = LocalSessionState.role

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireStaff()) return
        render()
    }

    private fun requireStaff(): Boolean {
        if (LocalSessionState.authenticated && !LocalSessionState.isOwner && LocalSessionState.role != null) return true
        startActivity(android.content.Intent(this, LocalAccessActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        return false
    }

    private fun render(message: String? = null) {
        val actor = actorId ?: return
        val role = staffRole ?: return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        content.addView(label("Staff Cloud Access", 25f))
        content.addView(label("Local account: $actor · ${role.name.lowercase()}", 15f))
        content.addView(label("Accept only an invitation for the same role shown above. Your cloud credentials are sent over HTTPS and the resulting session is stored encrypted for this local staff ID. This screen does not change local permissions.", 14f))
        endpoint = field("Backend HTTPS URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).also(content::addView)
        invitationToken = field("One-time staff invitation secret", password = true).also(content::addView)
        displayName = field("Display name for a new cloud account").also(content::addView)
        email = field("Cloud account email (used for later sign-in)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also(content::addView)
        password = field("Cloud account password (12+ characters)", password = true).also(content::addView)
        status = label(message ?: sessionStatus(actor), 14f).also(content::addView)
        content.addView(button("Accept invitation and link this staff ID") { accept(actor, role) })
        content.addView(button("Sign in to linked staff account") { login(actor, role) })
        content.addView(button("Sync this staff member's queued actions") { sync(actor, role) })
        content.addView(button("Disconnect linked cloud session") { disconnect(actor) })
        content.addView(button("Back to counter") { finish() })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun sessionStatus(actor: String): String {
        val session = runCatching { SyncSessionVault(this, actor).load() }.getOrNull()
        return when {
            session == null -> "No linked staff session on this device."
            !SyncStaffPolicy.mayUpload(session, actor) -> "Saved session needs a matching local staff role and identity. No records will be uploaded."
            else -> "Linked cloud role: ${session.role?.wireValue}. Session is encrypted; queued records may sync when network access is available."
        }
    }

    private fun accept(actor: String, localRole: StaffRole) {
        if (busy) return
        val base = endpoint.text.toString().trim()
        val invite = invitationToken.text.toString().trim()
        val name = displayName.text.toString().trim()
        val typedPassword = password.text.toString()
        val deviceId = UUID.randomUUID().toString()
        invitationToken.text.clear()
        password.text.clear()
        setBusy(true, "Accepting the one-time invitation over HTTPS…")
        worker.execute {
            val outcome = runCatching {
                val session = SyncAccountClient(HttpsSyncHttpTransport()).acceptStaffInvitation(
                    base,
                    StaffInvitationAcceptance(invite, name, typedPassword, deviceId, Build.MODEL.take(80).ifBlank { "Android staff device" })
                )
                require(SyncStaffPolicy.mayBindCloudSession(session, actor, localRole)) {
                    "Invitation role does not match this local staff account. Ask the owner to correct the invitation/local role before linking."
                }
                check(LocalSessionState.actorId == actor && LocalSessionState.role == localRole && !LocalSessionState.isOwner) {
                    "Local staff session changed while linking. Sign in again; no session was saved."
                }
                val bound = session.copy(localActorId = actor)
                SyncDeviceIdentityStore(this, actor).save(bound)
                SyncSessionVault(this, actor).save(bound)
                SyncBackgroundScheduler.enqueueOneTime(this)
                bound
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { session -> render("Linked to the server ${session.role?.wireValue} membership. No transaction data was uploaded by invitation acceptance.") },
                    { error -> render(safeMessage(error)) }
                )
                setBusy(false)
            }
        }
    }

    private fun login(actor: String, localRole: StaffRole) {
        if (busy) return
        val identity = SyncDeviceIdentityStore(this, actor).load()
        if (identity == null) {
            status.text = "No linked shop/device identity. Accept the owner's staff invitation first."
            return
        }
        val base = endpoint.text.toString().trim().ifBlank { identity.endpointBase }
        val accountEmail = email.text.toString().trim()
        val typedPassword = password.text.toString()
        password.text.clear()
        setBusy(true, "Signing in to the linked staff account over HTTPS…")
        worker.execute {
            val outcome = runCatching {
                val session = SyncAccountClient(HttpsSyncHttpTransport()).login(
                    base, SyncLogin(accountEmail, typedPassword, identity.identity.shopId, identity.identity.deviceId)
                )
                require(session.identity.businessId == identity.identity.businessId) { "Cloud business identity changed." }
                require(SyncStaffPolicy.mayBindCloudSession(session, actor, localRole)) { "Cloud account role does not match this local staff account. No session was saved." }
                check(LocalSessionState.actorId == actor && LocalSessionState.role == localRole && !LocalSessionState.isOwner) {
                    "Local staff session changed while signing in. Sign in again; no session was saved."
                }
                session.copy(localActorId = actor).also {
                    SyncDeviceIdentityStore(this, actor).save(it)
                    SyncSessionVault(this, actor).save(it)
                    SyncBackgroundScheduler.enqueueOneTime(this)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { session -> render("Signed in as the invited ${session.role?.wireValue}. Session encrypted; no transactions were uploaded yet.") },
                    { error -> render(safeMessage(error)) }
                )
                setBusy(false)
            }
        }
    }

    private fun sync(actor: String, localRole: StaffRole) {
        if (busy) return
        setBusy(true, "Checking this operator's queued actions…")
        worker.execute {
            val outcome = runCatching {
                val permit = BusinessWriteBarrier.tryBeginBusinessOperation() ?: throw BusinessMaintenanceException()
                try {
                    SyncOperationLock.withLock {
                        check(LocalSessionState.actorId == actor && LocalSessionState.role == localRole) { "Local staff session changed. Sign in again before syncing." }
                        val session = SyncSessionVault(this, actor).load() ?: error("No linked staff session. Accept an invitation or sign in first.")
                        check(SyncStaffPolicy.mayUpload(session, actor) && session.role == SyncStaffPolicy.roleForLocal(localRole)) {
                            "Saved cloud identity does not match the active local staff account. No records were uploaded."
                        }
                        SqliteCounterPersistence(this).use { persistence ->
                            val journal = ActorScopedSyncJournal(PersistenceSyncJournal(persistence), actor)
                            val result = SyncRunner(journal, HttpsSyncHttpTransport()).push(
                                session.endpointBase, session.identity, session.bearerToken
                            )
                            if (journal.pending(1).isEmpty() && journal.blockedByActor) result.copy(blockedByActor = true) else result
                        }
                    }
                } finally { permit.close() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { result -> render(when {
                        result.conflictEventId != null -> "Sync stopped at a server revision conflict. Later events remain queued."
                        result.blockedByActor -> "Sync paused: another or unattributed operator is at the global queue head. Nothing was skipped or uploaded under this account."
                        else -> "Sync complete: ${result.acknowledged} event(s) acknowledged. Server revision ${result.serverRevision}."
                    }) },
                    { error -> render(safeMessage(error)) }
                )
                setBusy(false)
            }
        }
    }

    private fun disconnect(actor: String) {
        if (busy) return
        setBusy(true, "Revoking the linked server session…")
        worker.execute {
            val outcome = runCatching {
                val vault = SyncSessionVault(this, actor)
                val session = vault.load() ?: return@runCatching false
                check(SyncStaffPolicy.mayUpload(session, actor)) { "Saved session identity does not match this staff account." }
                SyncAccountClient(HttpsSyncHttpTransport()).logout(session)
                vault.clear()
                true
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { revoked -> render(if (revoked) "Cloud session revoked and removed from this device. Local records are unchanged." else "No linked cloud session was saved.") },
                    { error -> render(safeMessage(error)) }
                )
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        if (::status.isInitialized && message != null) status.text = message
        if (::content.isInitialized) setDescendantsEnabled(content, !value)
    }

    private fun setDescendantsEnabled(group: ViewGroup, enabled: Boolean) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            child.isEnabled = enabled
            if (child is ViewGroup) setDescendantsEnabled(child, enabled)
        }
    }

    private fun safeMessage(error: Throwable): String = when (error) {
        is SyncAuthenticationException -> "Staff cloud request failed (HTTP ${error.statusCode}${error.serverCode?.let { ": $it" } ?: ""}). Check the invitation/account and try again."
        is SyncRequestException -> "Sync request failed (HTTP ${error.statusCode}); local records remain queued."
        is BusinessMaintenanceException -> "Backup or recovery is active. Try again after it finishes."
        else -> error.message?.takeIf { it.length <= 180 } ?: "Staff cloud action failed; local records were preserved."
    }

    private fun field(hintText: String, inputType: Int = InputType.TYPE_CLASS_TEXT, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        this.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else inputType
        if (password) { isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 60; setOnClickListener { action() } }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
