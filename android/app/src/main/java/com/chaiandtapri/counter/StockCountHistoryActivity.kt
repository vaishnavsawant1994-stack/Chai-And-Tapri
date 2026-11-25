package com.chaiandtapri.counter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date
import java.time.ZoneId

/** CT-16 read-only local history of reviewed physical count commands. */
class StockCountHistoryActivity : ComponentActivity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var exportStatus: TextView
    @Volatile private var destroyed = false

    private val createExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) {
            if (::exportStatus.isInitialized) exportStatus.text = "Stock count export cancelled. No business data was changed."
        } else {
            exportStatus.text = "Preparing local count history…"
            RecoveryExecutor.executor.execute {
                val csv = runCatching {
                    CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true).use { current ->
                        val names = current.products().associate { it.productId to it.name }
                        val sessions = StockCountHistory.from(current.auditRecords(), names)
                        StockCountHistoryCsv.render(sessions, ZoneId.systemDefault().id)
                    }
                }
                val result = csv.mapCatching { data ->
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("The selected destination could not be opened.")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                }
                runOnUiThread {
                    if (!destroyed && ::exportStatus.isInitialized) exportStatus.text = result.fold(
                        { "Stock count history CSV exported. Times use device clock; historical quantities may be unavailable." },
                        { error -> "Export failed (${error.javaClass.simpleName}). No ledger records were changed." }
                    )
                }
            }
        }
    }

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
            exportStatus = label("", 14f).also(content::addView)
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
            content.addView(button("Export stock count history as CSV") {
                createExportLauncher.launch("chai-tapri-stock-count-history.csv")
            })
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
    override fun onDestroy() { destroyed = true; model?.close(); model = null; super.onDestroy() }
}
