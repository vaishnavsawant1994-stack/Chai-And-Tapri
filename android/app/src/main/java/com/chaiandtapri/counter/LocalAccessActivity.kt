package com.chaiandtapri.counter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** CT-03 local owner enrollment/login gate; credentials remain in encrypted local storage. */
class LocalAccessActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tapri-owner-auth").apply { isDaemon = true } }
    private lateinit var username: EditText
    private lateinit var displayName: EditText
    private lateinit var secret: EditText
    private lateinit var status: TextView
    private var enrolling = false
    private var staffMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoading()
        worker.execute {
            val hasOwner = runCatching { LocalOwnerAuth.hasOwner(this) }.getOrDefault(false)
            runOnUiThread { if (!isFinishing) buildScreen(hasOwner) }
        }
    }

    private fun showLoading() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        column.addView(TextView(this).apply { text = "Opening protected owner access…"; textSize = 20f })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun buildScreen(hasOwner: Boolean) {
        staffMode = false
        enrolling = !hasOwner
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 28) }
        column.addView(TextView(this).apply { text = if (enrolling) "Set up owner access" else "Owner sign in"; textSize = 28f })
        column.addView(TextView(this).apply {
            text = "This owner account is stored on this device inside encrypted shop storage. It is not online identity or staff role management. Keep the passphrase safe; account recovery is not configured."
            textSize = 15f; setPadding(0, 12, 0, 20)
        })
        username = field("Owner username").also(column::addView)
        if (enrolling) displayName = field("Owner display name").also(column::addView)
        secret = field(if (enrolling) "Passphrase (12+ characters)" else "Passphrase", password = true).also(column::addView)
        status = TextView(this).apply { textSize = 15f; setPadding(0, 12, 0, 12) }.also(column::addView)
        column.addView(button(if (enrolling) "Create owner account" else "Unlock shop") { submit() })
        if (hasOwner) column.addView(button("Staff sign in") { buildStaffLogin() })
        if (!enrolling) column.addView(button("Backup & Recovery") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RECOVERY, true))
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun buildStaffLogin() {
        staffMode = true
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 28) }
        column.addView(TextView(this).apply { text = "Staff sign in"; textSize = 28f })
        column.addView(TextView(this).apply { text = "Use the staff ID and six-digit PIN set by the owner on this device."; textSize = 15f; setPadding(0, 12, 0, 20) })
        username = field("Staff ID").also(column::addView)
        secret = field("Six-digit PIN", password = true).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters = arrayOf(android.text.InputFilter.LengthFilter(6)) }.also(column::addView)
        status = TextView(this).apply { textSize = 15f; setPadding(0, 12, 0, 12) }.also(column::addView)
        column.addView(button("Sign in") { submit() })
        column.addView(button("Back to owner sign in") { buildScreen(true) })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun submit() {
        val user = username.text.toString()
        val name = if (enrolling) displayName.text.toString() else ""
        val chars = CharArray(secret.text.length) { secret.text[it] }
        secret.text.clear()
        status.text = "Checking local access…"
        worker.execute {
            val result = runCatching {
                if (staffMode) LocalStaffAuth.authenticate(this, user, chars)
                else if (enrolling) {
                    if (LocalOwnerAuth.enroll(this, user, name, chars)) {
                        LocalSessionState.establish(name.trim())
                        AuthResult.ACCEPTED
                    } else AuthResult.REJECTED
                } else LocalOwnerAuth.authenticate(this, user, chars)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ auth ->
                    if (auth == AuthResult.ACCEPTED) {
                        status.text = if (LocalSessionState.isOwner) "Owner access unlocked." else "Staff access unlocked."
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    } else status.text = if (auth == AuthResult.TEMPORARILY_LOCKED) "Access temporarily locked. Wait before trying again." else "Owner access could not be verified. Check the details and try again."
                    }, { error -> status.text = error.message?.takeIf { it.contains("12+") || it.contains("username") || it.contains("display name") } ?: "Local access could not be completed. Local data was preserved." })
            }
        }
    }

    private fun field(hintText: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        if (password) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; minHeight = 60
        setOnClickListener { action() }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
