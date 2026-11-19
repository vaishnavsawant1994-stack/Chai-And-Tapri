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

/** CT-25 local operating-expense entry; purchase receipts and supplier settlements stay separate. */
class ExpensesActivity : Activity() {
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
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this))
            model = current
            content.addView(label("Operating Expenses | CT-25", 24f))
            content.addView(label("Local lifetime total: ${money(current.totalExpensesPaise())}. Dates and daily totals are not available yet. Cash entries are staff-declared, not independently verified.", 14f))
            status = label("", 14f); content.addView(status)
            content.addView(button("Record operating expense") { askExpense() })
            content.addView(label("Expense history", 19f))
            val entries = current.expenseHistory().asReversed()
            if (entries.isEmpty()) content.addView(label("No expenses recorded.", 15f))
            entries.forEach { expense ->
                content.addView(label("${expense.category} | ${money(expense.amountPaise.toLong())} | ${expense.method} | ${expense.status}${if (expense.note.isBlank()) "" else " | ${expense.note}"}", 14f))
            }
            content.addView(label("Supplier purchases and supplier payments are tracked separately. Cash expenses recorded during an open CT-26 drawer session reduce that session's expected cash. Do not also record the same expense as a separate cash-out movement.", 13f))
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Expense records could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun askExpense() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val categories = listOf("rent", "electricity", "wages", "transport", "cleaning", "repair", "other")
        val category = Spinner(this).apply { adapter = ArrayAdapter(this@ExpensesActivity, android.R.layout.simple_spinner_dropdown_item, categories) }
        val amount = EditText(this).apply { hint = "Amount in rupees"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
        val method = Spinner(this).apply { adapter = ArrayAdapter(this@ExpensesActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash (staff-declared)", "UPI (staff-declared)", "Card (staff-declared)")) }
        val note = EditText(this).apply { hint = "Optional note (160 characters max)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(160)) }
        form.addView(label("Category", 14f)); form.addView(category); form.addView(amount); form.addView(label("Payment method", 14f)); form.addView(method); form.addView(note)
        val dialog = AlertDialog.Builder(this).setTitle("Record operating expense").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save expense", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val paise = runCatching { amount.text.toString().trim().toBigDecimal().movePointRight(2).intValueExact() }.getOrNull()
                if (paise == null || paise <= 0) { status.text = "Enter a positive exact rupee amount. No expense recorded."; return@setOnClickListener }
                val methodCode = listOf("cash", "upi", "card")[method.selectedItemPosition]
                val result = model?.recordExpense(categories[category.selectedItemPosition], paise, methodCode, note.text.toString()) ?: "Storage is unavailable."
                dialog.dismiss(); status.text = result; render()
            }
        }
        dialog.show()
    }

    private fun money(paise: Long) = String.format(Locale.ROOT, "₹%d.%02d", paise / 100, paise % 100)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
