package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** CT-26 session cash register. Amounts are local staff declarations, never external payment verification. */
class CashRegisterActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

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
            content.addView(label("Cash Register | CT-26", 24f))
            content.addView(label("Session cash is calculated from events explicitly recorded while the drawer session is open. Cash entries are staff-declared. Earlier unlinked transactions are excluded.", 14f))
            status = label("", 14f); content.addView(status)
            val active = current.activeCashDrawer()
            if (active == null) {
                content.addView(label("No open drawer session.", 18f))
                content.addView(button("Open cash drawer") { openDialog() })
            } else {
                val expected = current.expectedCashPaise(active.sessionId) ?: 0L
                content.addView(label("Opening cash: ${money(active.openingCashPaise.toLong())}", 17f))
                content.addView(label("Expected cash now: ${money(expected)}", 23f))
                content.addView(button("Record cash in / cash out") { movementDialog() })
                content.addView(button("Close drawer and count cash") { closeDialog(expected) })
                content.addView(label("Session cash events", 18f))
                val events = current.cashDrawerMovements(active.sessionId).asReversed()
                if (events.isEmpty()) content.addView(label("No manual cash movements.", 14f))
                events.forEach { content.addView(label("${it.kind.replace('_', ' ')} | ${money(it.amountPaise.toLong())} | ${it.note}", 14f)) }
            }
            content.addView(label("Recent drawer sessions", 18f))
            current.cashDrawerHistory().take(10).forEach { session ->
                val expected = current.expectedCashPaise(session.sessionId) ?: 0L
                val state = if (session.closedAt == null) "OPEN" else "CLOSED · expected ${money(expected)} · counted ${money(session.closingCashPaise!!.toLong())} · variance ${money(session.closingCashPaise.toLong() - expected)}"
                content.addView(label("$state · opening ${money(session.openingCashPaise.toLong())}", 14f))
            }
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Cash register could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun openDialog() = amountDialog("Open cash drawer", "Counted opening cash in rupees") { paise ->
        val result = model?.openCashDrawer(paise) ?: "Storage is unavailable."; status.text = result; render()
    }

    private fun movementDialog() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 8) }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@CashRegisterActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash in", "Cash out")) }
        val amount = EditText(this).apply { hint = "Amount in rupees"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
        val reason = EditText(this).apply { hint = "Reason (3 to 120 characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(120)) }
        form.addView(kind); form.addView(amount); form.addView(reason)
        AlertDialog.Builder(this).setTitle("Record drawer movement").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create().also { dialog ->
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val paise = toPaise(amount.text.toString())
                if (paise == null) { status.text = "Enter a positive exact rupee amount. No movement recorded."; return@setOnClickListener }
                val result = model?.recordCashDrawerMovement(if (kind.selectedItemPosition == 0) "cash_in" else "cash_out", paise, reason.text.toString()) ?: "Storage is unavailable."
                dialog.dismiss(); status.text = result; render()
            } }
            dialog.show()
        }
    }

    private fun closeDialog(expected: Long) = amountDialog("Close cash drawer", "Physical cash counted in rupees · expected ${money(expected)}") { paise ->
        val result = model?.closeCashDrawer(paise) ?: "Storage is unavailable."; status.text = result; render()
    }

    private fun amountDialog(title: String, hint: String, save: (Int) -> Unit) {
        val amount = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(amount).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val paise = toPaise(amount.text.toString())
            if (paise == null) { status.text = "Enter a valid non-negative exact rupee amount. No change made."; return@setOnClickListener }
            dialog.dismiss(); save(paise)
        } }
        dialog.show()
    }

    private fun toPaise(text: String): Int? = runCatching { text.trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it in 0..100_000_000 } }.getOrNull()
    private fun money(paise: Long): String {
        val absolute = kotlin.math.abs(paise)
        return String.format(Locale.ROOT, "%s₹%d.%02d", if (paise < 0) "-" else "", absolute / 100, absolute % 100)
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
