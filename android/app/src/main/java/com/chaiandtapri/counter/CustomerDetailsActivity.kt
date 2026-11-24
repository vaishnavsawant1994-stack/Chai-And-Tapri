package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import java.util.Locale
import java.util.UUID

/** CT-22 local account detail backed only by persisted credit and payment events. */
class CustomerDetailsActivity : Activity() {
    @VisibleForTesting var profileEditorDialog: AlertDialog? = null
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var customerId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        customerId = intent.getStringExtra(EXTRA_CUSTOMER_ID)?.trim().orEmpty()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && customerId.isNotBlank()) render() }

    private fun render(message: String? = null) {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            val credits = current.customerCredits().filter { it.customerId == customerId }
            val profile = current.customerProfiles().firstOrNull { it.customerId == customerId }
            if (customerId.isBlank() || (credits.isEmpty() && profile == null)) {
                content.addView(label("No persisted account was found for this customer ID.", 18f))
                content.addView(button("Back") { finish() })
                return
            }
            val events = current.paymentHistory().filter { it.customerId == customerId }
            val credited = credits.sumOf { it.amountPaise }
            val repaid = credits.sumOf { it.repaidPaise }
            val reversed = credits.sumOf { it.reversedPaise }
            val due = credits.sumOf { it.outstandingPaise }
            content.addView(label("Customer Details / CT-22", 24f))
            content.addView(label("Account ID: $customerId", 18f))
            profile?.let {
                content.addView(label("Name: ${it.displayName}", 17f))
                it.phone?.let { phone -> content.addView(label("Contact: ${"••••••" + phone.takeLast(4)} · consent recorded", 14f)) }
            }
            if (LocalSessionState.isOwner) {
                content.addView(button(if (profile == null) "Add customer profile" else "Edit customer profile") {
                    editProfile(profile)
                })
            }
            content.addView(label("Credit extended: ${money(credited)}", 16f))
            content.addView(label("Repaid: ${money(repaid)}", 16f))
            content.addView(label("Credit reversed: ${money(reversed)}", 16f))
            content.addView(label("Outstanding: ${money(due)}", 19f))
            if (due > 0) {
                val oldestOpenCredit = CreditAging.oldestOutstandingOrderCreatedAt(credits, current.allOrders(), customerId)
                content.addView(label(CreditAging.description(oldestOpenCredit, System.currentTimeMillis()), 14f))
            }
            message?.let { content.addView(label(it, 15f)) }
            content.addView(label("Credit by order", 18f))
            if (credits.isEmpty()) content.addView(label("No credit sales are recorded for this account.", 14f))
            credits.forEach { credit ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }
                row.addView(label("Order #${credit.orderNumber}: ${money(credit.amountPaise)} extended; ${money(credit.repaidPaise)} repaid; ${money(credit.reversedPaise)} reversed; ${money(credit.outstandingPaise)} due", 14f))
                row.addView(button("Open order #${credit.orderNumber}") {
                    startActivity(android.content.Intent(this, OrderDetailsActivity::class.java)
                        .putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, credit.orderNumber))
                })
                content.addView(row, matchWidth())
            }
            content.addView(label("Account timeline · newest first; dates use device local time", 18f))
            if (events.isEmpty()) content.addView(label("No payment events are recorded for this account.", 14f))
            CustomerTimeline.newestFirst(events).forEach { event ->
                val recordedTime = CustomerTimeline.recordedTime(event.createdAtEpochMs)
                content.addView(label(
                    "$recordedTime / ${event.kind} / ${event.method.uppercase(Locale.ROOT)} / ${money(event.amountPaise)} / ${event.status}" +
                        (event.orderNumber?.let { " / order #$it" } ?: ""), 14f
                ))
            }
            if (due > 0) content.addView(button("Record cash repayment") { askRepayment(due) })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Customer details could not be opened. No financial record was changed.", 17f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render(message) })
        }
    }

    private fun askRepayment(duePaise: Int) {
        val amount = EditText(this).apply {
            hint = "Cash amount in rupees; due ${money(duePaise)}"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Record cash repayment")
            .setMessage("Staff-declared cash repayment for $customerId. This does not verify external payment.")
            .setView(amount).setNegativeButton("Cancel", null).setPositiveButton("Record repayment") { _, _ ->
                val paise = runCatching { amount.text.toString().trim().toBigDecimal().movePointRight(2).intValueExact() }
                    .getOrNull()?.takeIf { it in 1..duePaise }
                if (paise == null) render("Enter a valid positive amount no greater than the outstanding balance. No change was made.")
                else {
                    val result = model?.repayCredit(customerId, paise, "ct22-repayment-${UUID.randomUUID()}")
                    render(result ?: "Repayment was not recorded.")
                }
            }.show()
    }

    private fun editProfile(profile: CustomerProfile?) {
        if (!LocalSessionState.isOwner) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val displayName = EditText(this).apply {
            hint = "Customer name"
            contentDescription = "Customer name"
            setSingleLine(true)
            text.append(profile?.displayName.orEmpty())
        }
        val phone = EditText(this).apply {
            hint = "Phone number (optional)"
            contentDescription = "Customer phone number"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setText(profile?.phone.orEmpty())
        }
        val consent = CheckBox(this).apply {
            text = getString(R.string.customer_profile_contact_consent)
            isChecked = profile?.contactConsent == true
        }
        form.addView(label("Contact details are optional. Store a phone number only with the customer's agreement. Unchecking consent clears the saved number; credit history is kept.", 13f))
        form.addView(displayName)
        form.addView(phone)
        form.addView(consent)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (profile == null) "Add customer profile" else "Edit customer profile")
            .setView(form)
            .setNegativeButton("Cancel", null)
        if (profile != null && (profile.phone != null || profile.contactConsent)) {
            builder.setNeutralButton("Clear saved phone and revoke consent") { _, _ ->
                render(model?.updateCustomerProfile(profile.copy(phone = null, contactConsent = false))
                    ?: "Customer profile could not be updated.")
            }
        }
        builder.setPositiveButton(if (profile == null) "Save profile" else "Save changes") { _, _ ->
            val rawPhone = phone.text.toString().trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
            val retainContact = consent.isChecked && rawPhone.isNotEmpty()
            val updated = CustomerProfile(
                customerId = customerId,
                displayName = displayName.text.toString().trim(),
                phone = rawPhone.takeIf { retainContact },
                contactConsent = retainContact
            )
            val result = if (profile == null) model?.saveCustomerProfile(updated) else model?.updateCustomerProfile(updated)
            render(result ?: "Customer profile could not be saved.")
        }
        profileEditorDialog = builder.show().also { dialog ->
            dialog.setOnDismissListener { profileEditorDialog = null }
        }
    }

    private fun money(paise: Int) = String.format(Locale.ROOT, "₹%.2f", paise / 100.0)
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(value: String, action: () -> Unit) = android.widget.Button(this).apply { text = value; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }

    companion object { const val EXTRA_CUSTOMER_ID = "customer_details_id" }
}
