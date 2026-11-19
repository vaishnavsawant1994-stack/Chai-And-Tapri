package com.chaiandtapri.counter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** CT-01 entry routes. No balances or sales are fabricated on the welcome screen. */
class WelcomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(label("Chai And Tapri OS", 30f))
        column.addView(label("Counter and shop management for chai tapris, paan shops, cafés and small stores.", 17f))
        column.addView(label("Local owner access protects this device's shop workspace. Online sync and separate staff accounts are not configured.", 14f))
        column.addView(button("Set up owner access / open shop") { startActivity(Intent(this, LocalAccessActivity::class.java)) })
        column.addView(button("Device permissions") { startActivity(Intent(this, DevicePermissionsActivity::class.java)) })
        column.addView(button("Open counter") { startActivity(Intent(this, LocalAccessActivity::class.java)) })
        column.addView(button("Backup and recovery") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RECOVERY, true))
        })
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 16, 0, 16)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        minHeight = 64
        setOnClickListener { action() }
    }
}
