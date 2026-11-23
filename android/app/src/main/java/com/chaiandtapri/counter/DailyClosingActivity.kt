package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode

/** CT-28 review for a completed drawer session. This is not yet a calendar-day accounting report. */
class DailyClosingActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var selectedSessionId: String? = null
    @Volatile private var loading = false
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        selectedSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && content.childCount > 0) render() }

    private fun render() {
        if (loading || destroyed) return
        loading = true
        val previous = model
        model = null
        content.removeAllViews()
        content.addView(label("Daily Closing | CT-28", 24f))
        content.addView(label("Loading encrypted closing records…", 14f))
        RecoveryExecutor.executor.execute {
            previous?.close()
            val loaded = runCatching { CounterModel(SqliteCounterPersistence(this@DailyClosingActivity)) }
            runOnUiThread {
                loading = false
                if (destroyed) {
                    loaded.getOrNull()?.let { store -> RecoveryExecutor.executor.execute { store.close() } }
                    return@runOnUiThread
                }
                content.removeAllViews()
                loaded.onSuccess { current ->
                    model = current
                    renderLoaded(current)
                }.onFailure { error ->
                    content.addView(label("Closing records could not be opened. No replacement data was created.", 18f))
                    content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
                    content.addView(button("Retry") { render() })
                }
            }
        }
    }

    private fun renderLoaded(current: CounterModel) {
        try {
            content.addView(label("Daily Closing | CT-28", 24f))
            content.addView(label("Closing review is scoped to a closed cash-drawer session. Calendar-day sales totals, unlinked cash activity and verified UPI settlement are not available here.", 14f))
            status = label("", 14f); content.addView(status)
            content.addView(label("Busy-counter estimates", 18f))
            content.addView(label("These amounts are operator estimates only. They do not create sales, collections, stock movements, or profit and remain separate from verified records.", 14f))
            content.addView(button("Record unitemized sales estimate") { estimateDialog(current) })
            current.unitemizedSalesEstimates().take(10).forEach { estimate ->
                val whenRecorded = if (estimate.recordedAtEpochMs > 0) time(estimate.recordedAtEpochMs) else "time unavailable"
                content.addView(label("ESTIMATED · ${money(estimate.amountPaise.toLong())} · $whenRecorded\nReason: ${estimate.reason}\nNot recorded sales or a collection", 14f))
            }
            val active = current.activeCashDrawer()
            if (active != null) {
                content.addView(label("A drawer session is still open. Close and count it before reviewing that session.", 16f))
                content.addView(button("Open Cash Register | CT-26") { startActivity(Intent(this, CashRegisterActivity::class.java)) })
            }
            val sessions = current.closedCashDrawerHistory()
            if (sessions.isEmpty()) {
                content.addView(label("No closed cash sessions are available to review.", 16f))
            } else {
                val selected = sessions.firstOrNull { it.sessionId == selectedSessionId } ?: sessions.first()
                selectedSessionId = selected.sessionId
                content.addView(label("Closed cash sessions", 18f))
                sessions.take(10).forEach { session ->
                    val prefix = if (session.sessionId == selected.sessionId) "Selected · " else "Review · "
                    content.addView(button(prefix + sessionLabel(session)) { selectedSessionId = session.sessionId; render() })
                }
                renderSession(current, selected)
            }
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Closing records could not be rendered. No business data was changed.", 18f))
            content.addView(label("Screen error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun estimateDialog(current: CounterModel) {
        val amount = EditText(this).apply {
            hint = "Unitemized estimate in rupees"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        val reason = EditText(this).apply {
            hint = "Reason for estimate (4 to 120 characters)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0); addView(amount); addView(reason) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Record estimated amount")
            .setMessage("Use only to note a busy-counter amount that was not itemized. This stays classified as an estimate and will not be added to actual sales or collections.")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Review estimate", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val paise = runCatching { BigDecimal(amount.text.toString().trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact() }.getOrNull()
                val note = reason.text.toString().trim()
                if (paise == null || paise !in 1..100_000_000) { amount.error = "Enter a positive amount with at most two decimals"; return@setOnClickListener }
                if (note.length !in 4..120) { reason.error = "Enter a reason between 4 and 120 characters"; return@setOnClickListener }
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm estimate only")
                    .setMessage("Save ${money(paise.toLong())} as an unitemized estimate? This will not change actual sales, collections, inventory, profit, or cash reconciliation.")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Save estimate only") { _, _ ->
                        status.text = "Saving estimate in encrypted audit history…"
                        RecoveryExecutor.executor.execute {
                            val result = current.recordUnitemizedSalesEstimate(paise, note, "busy-counter-estimate-${UUID.randomUUID()}")
                            runOnUiThread { if (!destroyed) { status.text = result; render() } }
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun renderSession(current: CounterModel, session: CashDrawerSession) {
        val entries = current.sessionPaymentEntries(session.sessionId)
        val cashCollections = entries.filter { it.method == "cash" && it.kind == "order_collection" && it.amountPaise > 0 }.sumOf { it.amountPaise.toLong() }
        val cashRefunds = -entries.filter { it.method == "cash" && it.kind == "cash_refund" }.sumOf { it.amountPaise.toLong() }
        val repayments = entries.filter { it.method == "cash" && it.kind == "customer_repayment" }.sumOf { it.amountPaise.toLong() }
        val expenses = current.sessionCashExpenses(session.sessionId).sumOf { it.amountPaise.toLong() }
        val supplierPayments = current.sessionCashSupplierPayments(session.sessionId).sumOf { it.amountPaise.toLong() }
        val movements = current.cashDrawerMovements(session.sessionId)
        val cashIn = movements.filter { it.kind == "cash_in" }.sumOf { it.amountPaise.toLong() }
        val cashOut = movements.filter { it.kind == "cash_out" }.sumOf { it.amountPaise.toLong() }
        val expected = current.expectedCashPaise(session.sessionId) ?: 0L
        val counted = session.closingCashPaise?.toLong() ?: 0L
        val variance = counted - expected
        content.addView(label("Session: ${sessionLabel(session)}", 17f))
        content.addView(label("Opening cash: ${money(session.openingCashPaise.toLong())}", 15f))
        content.addView(label("Cash sales recorded: ${money(cashCollections)} · staff-declared", 15f))
        content.addView(label("Cash customer repayments: ${money(repayments)} · staff-declared", 15f))
        content.addView(label("Cash refunds: ${money(cashRefunds)} · staff-declared", 15f))
        content.addView(label("Cash operating expenses: ${money(expenses)} · staff-declared", 15f))
        content.addView(label("Cash supplier settlements: ${money(supplierPayments)} · staff-declared", 15f))
        content.addView(label("Other cash-in / cash-out: ${money(cashIn)} / ${money(cashOut)}", 15f))
        content.addView(label("Expected cash: ${money(expected)}", 20f))
        content.addView(label("Physical count: ${money(counted)}", 18f))
        content.addView(label(if (variance == 0L) "Cash difference: ${money(variance)} · matched" else "Cash difference: ${money(variance)} · unresolved", 18f))

        val review = current.closingReview(session.sessionId)
        content.addView(label(if (review == null) "Review status: not acknowledged" else "Review status: acknowledged at ${time(review.reviewedAt)}${if (review.note.isBlank()) "" else " · ${review.note}"}", 14f))
        if (review == null) content.addView(button("Acknowledge review and note follow-up") { reviewDialog(session.sessionId, variance) })

        content.addView(label("Current shop items to follow up (not limited to this drawer session)", 18f))
        content.addView(label("Open orders: ${current.currentOpenOrderCount()}", 15f))
        content.addView(label("UPI pending: ${money(current.currentPendingUpiPaise())} · not verified collection", 15f))
        content.addView(label("Customer credit outstanding: ${money(current.currentCustomerReceivablePaise())}", 15f))
        content.addView(label("Acknowledging this review does not clear a cash difference, collect UPI, close open orders or settle customer credit.", 14f))
    }

    private fun reviewDialog(sessionId: String, variance: Long) {
        val note = EditText(this).apply { hint = "Optional follow-up note (240 characters max)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine(false); minLines = 2; filters = arrayOf(android.text.InputFilter.LengthFilter(240)) }
        val title = if (variance == 0L) "Acknowledge closing review" else "Acknowledge unresolved cash difference"
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage("This records that the closing information was reviewed. It does not change financial entries.").setView(note).setNegativeButton("Cancel", null).setPositiveButton("Acknowledge", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val activeModel = model
            val followUp = note.text.toString()
            dialog.dismiss()
            status.text = "Saving review to encrypted storage…"
            RecoveryExecutor.executor.execute {
                val result = activeModel?.recordCashClosingReview(sessionId, followUp) ?: "Storage is unavailable."
                runOnUiThread { if (!destroyed) { status.text = result; render() } }
            }
        } }
        dialog.show()
    }

    private fun sessionLabel(session: CashDrawerSession): String = "${time(session.openedAt)} – ${time(session.closedAt ?: session.openedAt)} · ${session.sessionId.takeLast(6)}"
    private fun time(timestamp: Long) = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
    private fun money(paise: Long): String { val abs = kotlin.math.abs(paise); return String.format(Locale.ROOT, "%s₹%d.%02d", if (paise < 0) "-" else "", abs / 100, abs % 100) }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() {
        destroyed = true
        model?.let { store -> RecoveryExecutor.executor.execute { store.close() } }
        model = null
        super.onDestroy()
    }

    companion object { const val EXTRA_SESSION_ID = "cash_session_id" }
}
