package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** CT-21/23/24 first local slice: customer balances, credit events, and staff-declared cash repayment. */
class CustomerCreditActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (content.childCount > 0) render() }

    private fun render(message: String? = null) {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            content.addView(label("Customers & Udhar Ledger · CT-21/23", 24f))
            content.addView(label("Local encrypted customer records only. Cash and UPI repayments are staff-declared; UPI is not provider-verified. Contact information is stored only with recorded consent; messaging is not available.", 14f))
            message?.let { content.addView(label(it, 14f)) }
            val allCredits = current.customerCredits()
            val accounts = allCredits.groupBy { it.customerId }.toSortedMap()
            val allOrders = current.allOrders()
            val profiles = current.customerProfiles().associateBy { it.customerId }
            val search = EditText(this).apply {
                hint = "Search customer accounts"
                contentDescription = "Search customer accounts"
                setSingleLine(true)
            }
            content.addView(search, matchWidth())
            content.addView(button("Add customer profile") { addCustomerProfile(current) })
            val accountIds = (accounts.keys + profiles.keys).distinct().sorted()
            if (accountIds.isEmpty()) content.addView(label("No customer profiles or credit accounts have been recorded.", 17f))
            val accountCards = mutableListOf<Pair<String, LinearLayout>>()
            accountIds.forEach { customerId ->
                val entries = accounts[customerId].orEmpty()
                val balance = entries.sumOf { it.outstandingPaise }
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 14, 12, 14) }
                card.addView(label(customerId, 19f))
                profiles[customerId]?.let { profile ->
                    card.addView(label(profile.displayName, 18f))
                    profile.phone?.let { phone -> card.addView(label("Contact: ${maskPhone(phone)} · consent recorded", 13f)) }
                    card.addView(button("Edit profile / contact consent") { editCustomerProfile(current, profile) })
                }
                card.addView(label("Outstanding credit: ${money(balance)}", 17f))
                if (balance > 0) {
                    val oldestCreditOrder = CreditAging.oldestOutstandingOrderCreatedAt(allCredits, allOrders, customerId)
                    card.addView(label(CreditAging.description(oldestCreditOrder, System.currentTimeMillis()), 13f))
                }
                entries.forEach { credit ->
                    card.addView(label("Order #${credit.orderNumber} · credit ${money(credit.amountPaise)} · repaid ${money(credit.repaidPaise)} · reversed ${money(credit.reversedPaise)} · due ${money(credit.outstandingPaise)}", 13f))
                }
                val events = current.paymentHistory().filter { it.customerId == customerId }
                card.addView(label("Account events", 15f))
                events.forEach { event ->
                    card.addView(label("${event.kind} · ${event.method.uppercase(Locale.ROOT)} · ${money(event.amountPaise)} · ${event.status}", 13f))
                }
                card.addView(button("Customer details") {
                    startActivity(android.content.Intent(this, CustomerDetailsActivity::class.java)
                        .putExtra(CustomerDetailsActivity.EXTRA_CUSTOMER_ID, customerId))
                })
                if (balance > 0) card.addView(button("Record repayment") { chooseRepaymentMethod(customerId, balance) })
                content.addView(card, matchWidth())
                accountCards += customerId to card
            }
            val noMatches = label("No customer accounts match this search.", 15f).apply { visibility = android.view.View.GONE }
            content.addView(noMatches)
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim().orEmpty()
                    val matching = accountCards.filter { (accountId, _) ->
                        accountId.contains(query, ignoreCase = true) || profiles[accountId]?.displayName?.contains(query, ignoreCase = true) == true
                    }
                    accountCards.forEach { (accountId, card) ->
                        card.visibility = if (accountId.contains(query, ignoreCase = true) || profiles[accountId]?.displayName?.contains(query, ignoreCase = true) == true) android.view.View.VISIBLE else android.view.View.GONE
                    }
                    noMatches.visibility = if (query.isNotEmpty() && matching.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Customer balances could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun addCustomerProfile(current: CounterModel) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val accountId = EditText(this).apply { hint = "Customer account ID"; setSingleLine(true) }
        val displayName = EditText(this).apply { hint = "Customer name"; setSingleLine(true) }
        val phone = EditText(this).apply { hint = "Phone number (optional)"; inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true) }
        val consent = CheckBox(this).apply { text = "Customer agreed to store this number for account-related contact. No marketing messages." }
        form.addView(label("Profiles are stored in encrypted local storage. Only add a phone number with the customer's agreement.", 13f))
        form.addView(accountId); form.addView(displayName); form.addView(phone); form.addView(consent)
        AlertDialog.Builder(this).setTitle("Add customer profile").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save profile") { _, _ ->
                val rawPhone = phone.text.toString().trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
                val result = current.saveCustomerProfile(CustomerProfile(
                    accountId.text.toString().trim(), displayName.text.toString().trim(), rawPhone.takeIf(String::isNotEmpty), consent.isChecked
                ))
                render(result)
            }.show()
    }

    private fun editCustomerProfile(current: CounterModel, profile: CustomerProfile) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val displayName = EditText(this).apply { hint = "Customer name"; setSingleLine(true); setText(profile.displayName) }
        val phone = EditText(this).apply { hint = "Phone number (optional)"; inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true); setText(profile.phone.orEmpty()) }
        val consent = CheckBox(this).apply {
            text = "Customer agreed to store this number for account-related contact. No marketing messages."
            isChecked = profile.contactConsent
        }
        form.addView(label("Uncheck consent and clear the phone number to revoke stored contact consent. This does not change credit or transaction history.", 13f))
        form.addView(displayName); form.addView(phone); form.addView(consent)
        val builder = AlertDialog.Builder(this).setTitle("Edit customer profile").setView(form)
            .setNegativeButton("Cancel", null)
        if (profile.phone != null || profile.contactConsent) {
            builder.setNeutralButton("Clear saved phone and revoke consent") { _, _ ->
                val result = current.updateCustomerProfile(profile.copy(phone = null, contactConsent = false))
                render(result)
            }
        }
        builder.setPositiveButton("Save changes") { _, _ ->
                val rawPhone = phone.text.toString().trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
                val retainContact = consent.isChecked && rawPhone.isNotEmpty()
                val result = current.updateCustomerProfile(profile.copy(
                    displayName = displayName.text.toString().trim(),
                    phone = rawPhone.takeIf { retainContact },
                    contactConsent = retainContact
                ))
                render(result)
            }.show()
    }

    private fun maskPhone(phone: String): String = "••••••${phone.takeLast(4)}"

    private fun chooseRepaymentMethod(customerId: String, balancePaise: Int) {
        AlertDialog.Builder(this).setTitle("Repayment method")
            .setItems(arrayOf("Cash (staff-declared)", "UPI (staff-declared, not verified)")) { _, which ->
                askRepayment(customerId, balancePaise, if (which == 0) "cash" else "upi")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun askRepayment(customerId: String, balancePaise: Int, method: String) {
        val methodLabel = if (method == "cash") "cash" else "UPI (staff-declared; not provider-verified)"
        val amount = EditText(this).apply {
            hint = "$methodLabel amount in rupees (due ${money(balancePaise)})"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Record $methodLabel repayment")
            .setMessage("This records a staff-declared $methodLabel repayment for $customerId. No external payment is verified.")
            .setView(amount).setNegativeButton("Cancel", null).setPositiveButton("Record repayment") { _, _ ->
                val paise = parsePaise(amount.text.toString())
                if (paise == null) content.addView(label("Enter a positive amount with no fractions smaller than one paisa. No change made.", 14f), 0)
                else {
                    val current = model
                    val commandId = "ui-repayment-${java.util.UUID.randomUUID()}"
                    val before = current?.customerCredits().orEmpty().filter { it.customerId == customerId }
                    val result = current?.repayCredit(customerId, paise, commandId, method)
                    val receipt = if (result?.contains("allocated") == true) {
                        val payment = current?.paymentHistory()?.firstOrNull { it.entryId == commandId }
                        payment?.let { CustomerRepaymentReceipt.create(customerId, before, current?.customerCredits().orEmpty(), it) }
                    } else null
                    render()
                    content.addView(label(result ?: "Repayment was not recorded.", 14f), 1)
                    receipt?.let(::showRepaymentReceipt)
                }
            }.show()
    }

    private fun showRepaymentReceipt(receipt: CustomerRepaymentReceipt) {
        AlertDialog.Builder(this).setTitle("Repayment recorded")
            .setMessage(receipt.displayText()).setPositiveButton("Done", null).show()
    }

    private fun parsePaise(raw: String): Int? = runCatching {
        raw.trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it > 0 }
    }.getOrNull()

    private fun money(paise: Int) = String.format(Locale.ROOT, "₹%.2f", paise / 100.0)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
