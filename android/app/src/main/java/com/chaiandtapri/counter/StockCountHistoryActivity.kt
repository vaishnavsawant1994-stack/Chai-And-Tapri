package com.chaiandtapri.counter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** CT-16 read-only local history of reviewed physical count commands. */
class StockCountHistoryActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render() {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            content.addView(label("Stock Count History · CT-16", 24f))
            content.addView(label("Read-only local audit history. Times use this device's clock and may be inaccurate. This history does not change stock.", 14f))
            val names = current.products().associate { it.productId to it.name }
            val sessions = StockCountHistory.from(current.auditRecords(), names)
            if (sessions.isEmpty()) content.addView(label("No physical stock counts have been recorded.", 16f))
            sessions.forEach { session ->
                content.addView(label("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.recordedAtEpochMs))} · ${session.rows.size} products", 17f))
                content.addView(label("Reason: ${session.reason}", 14f))
                session.actorId?.let { content.addView(label("Recorded by local account: $it", 13f)) }
                session.rows.forEach { row ->
                    val quantity = row.countedQuantity?.let { "counted $it ${if (it == 1) "unit" else "units"}" }
                        ?: "historical quantity unavailable"
                    content.addView(label("${row.productName} · $quantity", 15f))
                }
                content.addView(label("Command ${session.commandId}", 12f))
            }
            content.addView(button("Count multiple products · CT-16") { startActivity(Intent(this, StockCountActivity::class.java)) })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Stock count history could not be opened. No records were changed.", 16f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
