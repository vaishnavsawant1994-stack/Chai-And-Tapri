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

/** CAT-052 owner-only account setup and manual sync surface. Network work is explicit and HTTPS-only. */
class CloudSyncActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tapri-cloud-sync").apply { isDaemon = true } }
    private lateinit var endpoint: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var ownerName: EditText
    private lateinit var businessName: EditText
    private lateinit var shopName: EditText
    private lateinit var status: TextView
    private lateinit var dashboardIds: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireOwner(this)) return
        buildScreen()
        showSavedSessionState()
    }

    private fun buildScreen() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 32) }
        column.addView(label("Cloud Sync · CAT-052", 27f))
        column.addView(label("Prototype account setup. Use a trusted HTTPS backend. This sends the entered account details to that server. Sync uploads only the allow-listed outbox contract and downloads the accepted product catalogue for this shop. Stock quantities are not downloaded; each device must establish its own physical stock.", 15f))
        endpoint = field("Backend HTTPS URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).also(column::addView)
        email = field("Owner email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also(column::addView)
        password = field("Account password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, secure = true).also(column::addView)
        ownerName = field("Owner display name").also(column::addView)
        businessName = field("Business name").also(column::addView)
        shopName = field("Shop name").also(column::addView)
        status = label("No sync session loaded.", 15f).also(column::addView)
        dashboardIds = label("Owner dashboard sign-in IDs will appear here after a shop is registered on this device.", 13f).apply {
            setTextIsSelectable(true)
        }.also(column::addView)
        column.addView(button("Create server account and save session") { register() })
        column.addView(button("Sign back into this registered shop and device") { login() })
        column.addView(button("Sync now") { syncNow() })
        column.addView(button("Queue one background sync") { queueBackgroundSync() })
        column.addView(button("Enable automatic sync every six hours") { enableBackgroundSync() })
        column.addView(button("Pause background sync") { pauseBackgroundSync() })
        column.addView(button("Revoke server session and disconnect") { logout() })
        column.addView(button("Back") { finish() })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun showSavedSessionState() {
        val savedIdentity = runCatching { SyncDeviceIdentityStore(this).load() }.getOrNull()
        savedIdentity?.let {
            endpoint.setText(it.endpointBase)
            showDashboardIds(it.identity)
        }
        setBusy(true, "Checking this device's encrypted sync session…")
        worker.execute {
            val saved = runCatching { SyncSessionVault(this).load() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saved.fold(
                    { session ->
                        if (session != null) {
                            endpoint.setText(session.endpointBase)
                            showDashboardIds(session.identity)
                            status.text = "Sync session saved for this device and shop. Token stays hidden and encrypted on this device." + backgroundStatusSuffix()
                        } else {
                            status.text = "No sync session loaded."
                        }
                    },
                    { status.text = "Saved sync credentials are unavailable; encrypted data was preserved. Resolve this device's sync session before replacing it." }
                )
                setBusy(false)
            }
        }
    }

    private fun register() {
        if (busy) return
        val operatorAtStart = LocalSessionState.actorId
        if (!LocalSessionState.isOwner || !SyncOwnerPolicy.isLocalOwner(operatorAtStart)) {
            status.text = "Only the authenticated local owner can register this device for cloud sync."
            return
        }
        val base = endpoint.text.toString().trim()
        val emailValue = email.text.toString().trim()
        val passwordValue = password.text.toString()
        val displayValue = ownerName.text.toString()
        val businessValue = businessName.text.toString()
        val shopValue = shopName.text.toString()
        password.text.clear()
        val deviceId = deviceId()
        setBusy(true, "Creating account over HTTPS…")
        worker.execute {
            val outcome = runCatching {
                SyncOperationLock.withLock {
                    SyncAccountClient(HttpsSyncHttpTransport()).register(base, SyncRegistration(
                        emailValue, passwordValue, displayValue, businessValue, shopValue, deviceId, Build.MODEL.take(80).ifBlank { "Android device" }
                    )).also { session ->
                        check(SyncOwnerPolicy.mayBindCloudSession(session, operatorAtStart)) { "Cloud registration did not return an owner account." }
                        check(LocalSessionState.isOwner && LocalSessionState.actorId == operatorAtStart) { "Local owner session changed during account registration. Sign in again; no session was saved." }
                        val bound = session.copy(localActorId = operatorAtStart)
                        SyncSessionVault(this).save(bound)
                        SyncDeviceIdentityStore(this).save(bound)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { session ->
                        showDashboardIds(session.identity)
                        status.text = "Server account created. Session saved for this shop and device. No sales were synced yet."
                    },
                    { error -> status.text = safeMessage(error) }
                )
                setBusy(false)
            }
        }
    }

    private fun login() {
        if (busy) return
        val operatorAtStart = LocalSessionState.actorId
        if (!LocalSessionState.isOwner || !SyncOwnerPolicy.isLocalOwner(operatorAtStart)) {
            status.text = "Only the authenticated local owner can connect this device to cloud sync."
            return
        }
        val identity = SyncDeviceIdentityStore(this).load()
        if (identity == null) {
            status.text = "This device has no saved shop identity. An existing shop must authorize/register this device before it can sign in."
            return
        }
        val base = endpoint.text.toString().trim().ifBlank { identity.endpointBase }
        val emailValue = email.text.toString().trim()
        val passwordValue = password.text.toString()
        password.text.clear()
        setBusy(true, "Signing in to the previously registered shop over HTTPS…")
        worker.execute {
            val outcome = runCatching {
                SyncOperationLock.withLock {
                    SyncAccountClient(HttpsSyncHttpTransport()).login(
                        base,
                        SyncLogin(emailValue, passwordValue, identity.identity.shopId, identity.identity.deviceId)
                    ).also { session ->
                        require(session.identity.businessId == identity.identity.businessId) { "The returned business identity changed." }
                        require(SyncOwnerPolicy.mayBindCloudSession(session, operatorAtStart)) { "This cloud account is not an owner. No owner sync session was saved." }
                        check(LocalSessionState.isOwner && LocalSessionState.actorId == operatorAtStart) { "Local owner session changed during sign-in. Sign in again; no session was saved." }
                        val bound = session.copy(localActorId = operatorAtStart)
                        SyncSessionVault(this).save(bound)
                        SyncDeviceIdentityStore(this).save(bound)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { session ->
                        showDashboardIds(session.identity)
                        status.text = "Signed in. Session encrypted for this device; no sales were synced yet."
                    },
                    { error -> status.text = safeMessage(error) }
                )
                setBusy(false)
            }
        }
    }

    private fun syncNow() {
        if (busy) return
        setBusy(true, "Syncing allowed local events over HTTPS…")
        worker.execute {
            val outcome: Result<Triple<SyncRunResult, Int, Long>> = runCatching {
                SyncOperationLock.withLock {
                    val session = SyncSessionVault(this).load() ?: error("No saved session. Create an account first.")
                    check(SyncOwnerPolicy.mayUpload(session)) {
                        "The saved cloud session is not bound to the local owner. Owner sign-in is required before sync."
                    }
                    val actorId = session.localActorId ?: error("This saved session has no local operator binding. Sign in again while authenticated as the local owner before syncing.")
                    SqliteCounterPersistence(this).use { persistence ->
                        val journal = ActorScopedSyncJournal(PersistenceSyncJournal(persistence), actorId)
                        val transport = HttpsSyncHttpTransport()
                        val result = SyncRunner(journal, transport).push(
                            session.endpointBase, session.identity, session.bearerToken
                        )
                        val catalogue = SyncCatalogueClient(transport).fetch(session.endpointBase, session.identity, session.bearerToken)
                        check(persistence.applyRemoteCatalogueSnapshot(catalogue)) {
                            "The server catalogue was older than local accepted data or belonged to another shop. Local records were preserved."
                        }
                        val effectiveResult = if (journal.pending(1).isEmpty() && journal.blockedByActor) result.copy(blockedByActor = true) else result
                        Triple(effectiveResult, catalogue.products.size, catalogue.serverRevision)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { (result, catalogueCount, catalogueRevision) -> status.text = (when {
                        result.conflictEventId != null -> "Sync stopped at a revision conflict for event ${result.conflictEventId}. Later events remain queued."
                        result.blockedByActor -> "Sync paused: the next queued action belongs to another or unlinked local operator. It was not uploaded under this account."
                        else -> "Sync finished. ${result.acknowledged} event(s) acknowledged. Server revision ${result.serverRevision}."
                    }) + " Accepted shop catalogue refreshed: $catalogueCount product(s), server revision $catalogueRevision." + backgroundStatusSuffix() },
                    { error -> status.text = safeMessage(error) }
                )
                setBusy(false)
            }
        }
    }

    private fun queueBackgroundSync() {
        if (busy) return
        val session = runCatching { SyncSessionVault(this).load() }.getOrNull()
        if (session == null) {
            status.text = "Sign in before scheduling background sync."
            return
        }
        SyncBackgroundScheduler.enqueueOneTime(this)
        status.text = "One-time sync queued. It will run when Android has a connected network." + backgroundStatusSuffix()
    }

    private fun enableBackgroundSync() {
        if (busy) return
        val session = runCatching { SyncSessionVault(this).load() }.getOrNull()
        if (session == null) {
            status.text = "Sign in before enabling automatic sync."
            return
        }
        SyncBackgroundScheduler.enablePeriodic(this)
        status.text = "Automatic sync enabled. Android will run it periodically when a network is available." + backgroundStatusSuffix()
    }

    private fun pauseBackgroundSync() {
        if (busy) return
        setBusy(true, "Pausing queued background sync…")
        worker.execute {
            val outcome = runCatching {
                SyncBackgroundScheduler.pause(this).forEach { it.result.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                SyncOperationLock.withLock { Unit }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = outcome.fold(
                    { "Background sync paused after any in-flight request finished. Local outbox data remains on this device." },
                    { "Could not confirm background sync pause. Local outbox data remains on this device." }
                )
                setBusy(false)
            }
        }
    }

    private fun backgroundStatusSuffix(): String = SyncBackgroundStatus.read(this)?.let { "\n$it" } ?: ""

    private fun logout() {
        if (busy) return
        setBusy(true, "Requesting server session revocation…")
        worker.execute {
            val outcome = runCatching {
                SyncBackgroundScheduler.pause(this).forEach { it.result.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                SyncOperationLock.withLock {
                    val session = SyncSessionVault(this).load() ?: error("No saved sync session.")
                    SyncAccountClient(HttpsSyncHttpTransport()).logout(session)
                    SyncSessionVault(this).clear()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    { status.text = "Server revoked the session; local encrypted session was cleared. Background sync is paused." },
                    { error -> status.text = safeMessage(error) }
                )
                setBusy(false)
            }
        }
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("chai_tapri_sync_device_identity_v1", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { existing ->
            if (runCatching { UUID.fromString(existing).toString() == existing }.getOrDefault(false)) return existing
        }
        return UUID.randomUUID().toString().also { created ->
            check(prefs.edit().putString("device_id", created).commit()) { "Device identity could not be saved." }
        }
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        if (message != null) status.text = message
        endpoint.isEnabled = !value
        email.isEnabled = !value
        password.isEnabled = !value
        ownerName.isEnabled = !value
        businessName.isEnabled = !value
        shopName.isEnabled = !value
    }

    private fun showDashboardIds(identity: SyncEnvelopeIdentity) {
        if (!::dashboardIds.isInitialized) return
        dashboardIds.text = "Owner dashboard sign-in IDs (select and copy):\nShop ID: ${identity.shopId}\nDevice ID: ${identity.deviceId}\nThese identify the registered shop/device; they are not passwords or access tokens."
    }

    private fun safeMessage(error: Throwable): String = when (error) {
        is SyncAuthenticationException -> when (error.statusCode) {
            401 -> "Server did not accept these account credentials. Check the email and password."
            403 -> "This account or device is not authorized for the selected shop."
            429 -> "Too many attempts. Wait before trying again."
            else -> "Account request failed (HTTP ${error.statusCode})."
        }
        is SyncRequestException -> "Sync request failed (HTTP ${error.statusCode}). Outbox entries remain available for retry."
        is SyncProtocolException -> "Server response could not be safely applied. Local records are preserved."
        else -> error.message?.takeIf { it.startsWith("Account setup requires") || it.startsWith("Enter a valid HTTPS") || it.startsWith("Account password") || it.startsWith("Enter a valid") || it.startsWith("Device identity") || it.startsWith("Email") || it.startsWith("Business") || it.startsWith("Shop ") }
            ?: "Operation failed. Local business data was preserved."
    }

    private fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT, secure: Boolean = false) = EditText(this).apply {
        hint = hintText
        inputType = type
        if (secure) {
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(textValue: String, size: Float) = TextView(this).apply {
        text = textValue; textSize = size; setPadding(0, 10, 0, 10)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; minHeight = 60; setOnClickListener { action() }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
