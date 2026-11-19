package com.chaiandtapri.counter

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Owner-only local staff account management. PINs are never rendered back or persisted as text. */
class StaffManagementActivity : Activity() {
    private lateinit var content: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireOwner(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render(message: String? = null) {
        content.removeAllViews()
        content.addView(TextView(this).apply { text = "Staff accounts · local device"; textSize = 25f })
        content.addView(TextView(this).apply { text = "Cashiers can use counter sales and orders. Managers can access shop operations and reports. Only the owner can manage staff, prices, security and recovery. Staff access is local to this encrypted database."; textSize = 14f; setPadding(0, 10, 0, 14) })
        message?.let { content.addView(TextView(this).apply { text = it; textSize = 15f; setPadding(0, 8, 0, 8) }) }
        val id = input("Staff ID")
        val name = input("Display name")
        val pin = input("Six-digit PIN").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters = arrayOf(android.text.InputFilter.LengthFilter(6)); isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO }
        val role = Spinner(this).apply { adapter = ArrayAdapter(this@StaffManagementActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cashier", "Manager")) }
        content.addView(id); content.addView(name); content.addView(pin); content.addView(role)
        content.addView(button("Create staff account") {
            val chars = CharArray(pin.text.length) { pin.text[it] }; pin.text.clear()
            val selectedRole = if (role.selectedItemPosition == 1) StaffRole.MANAGER else StaffRole.CASHIER
            Thread({
                val result = runCatching { LocalStaffAuth.create(this, id.text.toString(), name.text.toString(), selectedRole, chars) }
                runOnUiThread { render(if (result.getOrDefault(false)) "Staff account created." else result.exceptionOrNull()?.message ?: "Staff ID already exists or account could not be created.") }
            }, "tapri-staff-create").apply { isDaemon = true; start() }
        })
        val accounts = runCatching { SqliteCounterPersistence(this).use { it.listStaffAccounts() } }.getOrDefault(emptyList())
        content.addView(TextView(this).apply { text = "Existing staff"; textSize = 19f; setPadding(0, 20, 0, 8) })
        if (accounts.isEmpty()) content.addView(TextView(this).apply { text = "No staff accounts yet."; textSize = 15f })
        accounts.forEach { account ->
            content.addView(TextView(this).apply { text = "${account.displayName} · ${account.staffId} · ${account.role} · ${if (account.enabled) "Enabled" else "Disabled"}"; textSize = 15f; setPadding(0, 8, 0, 4) })
            content.addView(button(if (account.enabled) "Disable ${account.displayName}" else "Enable ${account.displayName}") {
                Thread({
                    val changed = runCatching { SqliteCounterPersistence(this).use { it.setStaffAccountEnabled(account.staffId, !account.enabled, java.util.UUID.randomUUID().toString()) } }.getOrDefault(false)
                    runOnUiThread { render(if (changed) "Account status updated." else "Account status could not be updated.") }
                }, "tapri-staff-status").apply { isDaemon = true; start() }
            })
        }
        content.addView(button("Back to shop") { finish() })
    }

    private fun input(hint: String) = EditText(this).apply { this.hint = hint; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; minHeight = 56; setOnClickListener { action() } }
}
