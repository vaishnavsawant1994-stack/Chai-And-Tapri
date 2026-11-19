package com.chaiandtapri.counter

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Locale

/** CT-27 prototype: operator-entered staff labels tied to counted cash-drawer sessions; no staff authentication. */
class StaffShiftsActivity : ComponentActivity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && content.childCount > 0) render() }

    private fun render() {
        model?.close(); content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this)); model = current
            content.addView(label("Staff Shifts | CT-27", 24f))
            content.addView(label("Shift labels are entered by the operator and are not authenticated staff identities. The cash summary includes only records linked to this drawer session; it does not attribute every order or action to a person.", 14f))
            val active = current.activeCashDrawer()?.takeIf { it.staffLabel != null }
            if (active == null) {
                content.addView(label("No active staff shift.", 18f))
                if (current.activeCashDrawer() != null) content.addView(label("A non-shift cash drawer session is open. Close it in Cash Register before starting a staff shift.", 14f))
                else content.addView(button("Start staff shift") { startDialog() })
            } else {
                val expected = current.expectedCashPaise(active.sessionId) ?: 0L
                content.addView(label("Operator label: ${active.staffLabel}", 19f))
                content.addView(label("Started: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(active.openedAt))}", 14f))
                content.addView(label("Opening cash: ${money(active.openingCashPaise.toLong())}\nExpected session cash: ${money(expected)}", 18f))
                content.addView(label("Linked cash collections: ${money(current.sessionPaymentEntries(active.sessionId).filter { it.method == "cash" && it.kind == "order_collection" }.sumOf { it.amountPaise.toLong() })}\nCash expenses: ${money(current.sessionCashExpenses(active.sessionId).sumOf { it.amountPaise.toLong() })}\nSupplier cash settlements: ${money(current.sessionCashSupplierPayments(active.sessionId).sumOf { it.amountPaise.toLong() })}", 15f))
                content.addView(button("End shift and record handover") { endDialog(active.sessionId, expected) })
            }
            content.addView(label("Recent shift sessions", 19f))
            val history = current.cashDrawerHistory().filter { it.staffLabel != null }.take(20)
            if (history.isEmpty()) content.addView(label("No staff shift sessions recorded.", 14f))
            history.forEach { shift ->
                val expected = current.expectedCashPaise(shift.sessionId) ?: 0L
                val state = if (shift.closedAt == null) "ACTIVE" else "ENDED · handed over ${money(shift.closingCashPaise!!.toLong())} · variance ${money(shift.closingCashPaise.toLong() - expected)}"
                val handover = shift.handoverToLabel?.let { "\nHandover label: $it" }.orEmpty()
                val note = shift.handoverNote.takeIf(String::isNotBlank)?.let { "\nNote: $it" }.orEmpty()
                content.addView(label("${shift.staffLabel} · $state\nOpening ${money(shift.openingCashPaise.toLong())} · expected ${money(expected)}$handover$note", 14f))
            }
            content.addView(button("Cash Register · CT-26") { startActivity(android.content.Intent(this, CashRegisterActivity::class.java)) })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Staff shifts are unavailable. No replacement data was created. (${error.javaClass.simpleName})", 17f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun startDialog() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 8) }
        val staff = EditText(this).apply { hint = "Staff label (2 to 60 characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setSingleLine(true) }
        val opening = EditText(this).apply { hint = "Counted opening cash in rupees"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
        form.addView(staff); form.addView(opening)
        AlertDialog.Builder(this).setTitle("Start staff shift").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Start", null).create().also { dialog ->
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = toPaise(opening.text.toString())
                if (staff.text.toString().trim().length !in 2..60 || amount == null) { staff.error = if (staff.text.toString().trim().length !in 2..60) "Enter a staff label" else null; opening.error = if (amount == null) "Enter a valid cash count" else null; return@setOnClickListener }
                val result = model?.startStaffShift(staff.text.toString(), amount) ?: "Local storage is unavailable."
                dialog.dismiss(); android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show(); render()
            } }
            dialog.show()
        }
    }

    private fun endDialog(sessionId: String, expected: Long) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 8) }
        val count = EditText(this).apply { hint = "Counted cash in rupees · expected ${money(expected)}"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
        val next = EditText(this).apply { hint = "Receiving staff label"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setSingleLine(true) }
        val note = EditText(this).apply { hint = "Handover note (optional)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(160)) }
        form.addView(count); form.addView(next); form.addView(note)
        AlertDialog.Builder(this).setTitle("End shift and hand over cash").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Record handover", null).create().also { dialog ->
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = toPaise(count.text.toString())
                if (amount == null || next.text.toString().trim().length !in 2..60) { count.error = if (amount == null) "Enter a valid cash count" else null; next.error = if (next.text.toString().trim().length !in 2..60) "Enter receiving staff label" else null; return@setOnClickListener }
                val result = model?.endStaffShift(amount, next.text.toString(), note.text.toString()) ?: "Local storage is unavailable."
                dialog.dismiss(); android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show(); render()
            } }
            dialog.show()
        }
    }

    private fun toPaise(text: String): Int? = runCatching { text.trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it in 0..100_000_000 } }.getOrNull()
    private fun money(paise: Long): String { val abs = kotlin.math.abs(paise); return String.format(Locale.ROOT, "%s₹%d.%02d", if (paise < 0) "-" else "", abs / 100, abs % 100) }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
