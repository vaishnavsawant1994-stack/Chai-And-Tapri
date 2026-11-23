package com.chaiandtapri.counter

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SalesHistoryExportUiTest {
    @Test fun filteredHistoryOpensSafExportAndLeavesLedgerUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic export-test owner")
        CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true).use { model ->
            val product = model.saveProduct(TapriProduct("history_export_tea", "Synthetic History Tea", "Chai", 1200, priceConfirmed = true))
            assertTrue("Product fixture: $product", product.startsWith("Product saved"))
            val count = model.confirmOpeningStock("history_export_tea", 3, "synthetic export", "history-export-stock")
            assertTrue("Opening-stock fixture: $count", count.startsWith("Opening stock confirmed"))
            val sale = model.quickSale(listOf("history_export_tea" to 1), "upi_pending", commandId = "history-export-sale")
            assertTrue("Sale fixture: $sale", sale.startsWith("Quick sale"))
        }
        val before = SqliteCounterPersistence(context).use { it.load() ?: error("Synthetic history missing") }
        val activity = instrumentation.startActivitySync(Intent(context, SalesHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun find(prefix: String? = null, editText: Boolean = false): AccessibilityNodeInfo? {
                fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    val text = node.text?.toString().orEmpty()
                    if ((prefix == null || text.startsWith(prefix, ignoreCase = true)) &&
                        (!editText || node.className?.toString()?.endsWith("EditText") == true)) return node
                    for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                    return null
                }
                return visit(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun await(prefix: String? = null, editText: Boolean = false): AccessibilityNodeInfo {
                repeat(100) { find(prefix, editText)?.let { return it }; Thread.sleep(100) }
                throw AssertionError("Sales history export control not found: $prefix")
            }
            val search = await(editText = true)
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic History Tea")
            }))
            assertTrue(await("Export matching sales as CSV").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var pickerText = ""
            repeat(200) {
                pickerText = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    val values = mutableListOf<String>()
                    fun collect(node: AccessibilityNodeInfo?) {
                        if (node == null) return
                        node.text?.toString()?.let(values::add)
                        for (index in 0 until node.childCount) collect(node.getChild(index))
                    }
                    collect(root); values.joinToString(" | ")
                }.orEmpty()
                if (pickerText.contains("Downloads", true) && pickerText.contains("Save", true)) return@repeat
                Thread.sleep(100)
            }
            assertTrue("System document picker should be available: $pickerText", pickerText.contains("Downloads", true) && pickerText.contains("Save", true))
            val filename = "ct11-history-${System.currentTimeMillis()}.csv"
            val nameField = await(editText = true)
            assertTrue(nameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
            }))
            assertTrue(await("Save").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            repeat(200) {
                val status = find("Sales history CSV exported")
                if (status != null) return@repeat
                Thread.sleep(100)
            }
            assertTrue("CSV export should finish", find("Sales history CSV exported") != null)
            val after = SqliteCounterPersistence(context).use { it.load() ?: error("Export removed the ledger") }
            assertEquals("Export is read-only", before, after)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
            LocalSessionState.clear()
        }
    }
}
