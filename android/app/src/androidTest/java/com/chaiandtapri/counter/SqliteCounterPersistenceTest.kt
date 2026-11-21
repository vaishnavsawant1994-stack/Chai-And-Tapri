package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.content.Context
import android.content.ContentValues
import android.Manifest
import android.net.Uri
import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.Spinner
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's real SQLite implementation when an emulator/device is available. */
@RunWith(AndroidJUnit4::class)
class SqliteCounterPersistenceTest {
    private fun scrollActiveWindowForward(root: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo) {
            if (target == null && node.isScrollable) target = node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        target?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    @Before fun establishSyntheticOwnerSessionForScreenTests() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocalSessionState.establish("Synthetic test owner")
        }
    }

    @After fun clearSyntheticOwnerSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { LocalSessionState.clear() }
    }

    @Test fun localOwnerEnrollmentWrongSecretsLockoutAndUnlockArePersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalSessionState.clear()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        assertTrue(!LocalOwnerAuth.hasOwner(context))
        assertTrue(LocalOwnerAuth.enroll(context, "  TapriOwner ", "Shop Owner", "correct horse battery staple".toCharArray()))
        assertTrue(LocalOwnerAuth.hasOwner(context))
        SqliteCounterPersistence(context).use { assertEquals(600_000, it.loadLocalOwnerCredential("tapriowner")?.iterations) }
        assertEquals(AuthResult.REJECTED, LocalOwnerAuth.authenticate(context, "tapriowner", "incorrect passphrase".toCharArray(), 1_000_000))
        repeat(3) { assertEquals(AuthResult.REJECTED, LocalOwnerAuth.authenticate(context, "tapriowner", "incorrect passphrase".toCharArray(), 1_000_001L + it)) }
        assertEquals(AuthResult.TEMPORARILY_LOCKED, LocalOwnerAuth.authenticate(context, "tapriowner", "incorrect passphrase".toCharArray(), 1_000_010))
        assertEquals(AuthResult.TEMPORARILY_LOCKED, LocalOwnerAuth.authenticate(context, "tapriowner", "correct horse battery staple".toCharArray(), 1_000_020))
        assertEquals(AuthResult.ACCEPTED, LocalOwnerAuth.authenticate(context, "tapriowner", "correct horse battery staple".toCharArray(), 1_061_000))
        assertEquals("Shop Owner", LocalSessionState.ownerDisplayName)
        SqliteCounterPersistence(context).use { db ->
            val events = db.loadAuditRecords()
            assertEquals("local-owner", events.single { it.event == "local_owner.enrolled" }.actorId)
            assertEquals("local-owner", events.single { it.event == "local_owner.login_succeeded" }.actorId)
            assertTrue(events.filter { it.event == "local_owner.login_failed" }.all { it.actorId == null })
        }
        LocalSessionState.clear()
        assertEquals(AuthResult.ACCEPTED, LocalOwnerAuth.authenticate(context, "tapriowner", "correct horse battery staple".toCharArray(), 1_062_000))
        LocalSessionState.clear()
    }

    @Test fun ownerAccessScreenAuthenticatesAndOpensProtectedCounter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.clear()
        val secret = "Synthetic owner access passphrase".toCharArray()
        assertTrue(LocalOwnerAuth.enroll(context, "screenowner", "Screen Test Owner", secret))

        val mainMonitor = Instrumentation.ActivityMonitor(MainActivity::class.java.name, null, false)
        var access: LocalAccessActivity? = null
        var main: MainActivity? = null
        try {
            instrumentation.addMonitor(mainMonitor)
            access = instrumentation.startActivitySync(Intent(context, LocalAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LocalAccessActivity
            fun awaitNode(hint: String? = null, text: String? = null, className: String? = null): AccessibilityNodeInfo {
                var tree = ""
                repeat(180) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var found: AccessibilityNodeInfo? = null
                    val labels = mutableListOf<String>()
                    fun visit(node: AccessibilityNodeInfo) {
                        node.text?.toString()?.let(labels::add)
                        node.hintText?.toString()?.let(labels::add)
                        val matches = when {
                            hint != null -> node.hintText?.toString()?.equals(hint, true) == true
                            text != null -> node.text?.toString()?.equals(text, true) == true
                            else -> node.className?.toString() == className
                        }
                        if (found == null && matches) found = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                    }
                    if (root != null) visit(root)
                    tree = labels.joinToString(" | ")
                    found?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Owner access UI node not found: hint=$hint text=$text class=$className; UI=$tree")
            }
            assertTrue(awaitNode(hint = "Owner username").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "screenowner") }))
            assertTrue(awaitNode(hint = "Passphrase").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Synthetic owner access passphrase") }))
            assertTrue(awaitNode(text = "Unlock shop").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            main = mainMonitor.waitForActivityWithTimeout(120_000) as? MainActivity
            assertNotNull("Valid local owner sign-in should open the protected counter", main)
            assertTrue(awaitNode(text = "Owner: Screen Test Owner").text.toString().contains("Screen Test Owner"))
            assertTrue(LocalSessionState.authenticated)
        } finally {
            instrumentation.removeMonitor(mainMonitor)
            instrumentation.runOnMainSync { main?.finish(); access?.finish(); LocalSessionState.clear() }
            Thread.sleep(300)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun localStaffPinAuthenticationRoleAndDisablePersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic owner")
        assertTrue(LocalOwnerAuth.enroll(context, "stafftestowner", "Synthetic owner", "staff fixture owner passphrase".toCharArray()))
        LocalSessionState.establish("Synthetic owner")
        assertTrue(LocalStaffAuth.create(context, "cashier01", "Counter Cashier", StaffRole.CASHIER, "123456".toCharArray()))
        assertEquals(AuthResult.REJECTED, LocalStaffAuth.authenticate(context, "cashier01", "000000".toCharArray(), 2_000_000))
        assertEquals(AuthResult.ACCEPTED, LocalStaffAuth.authenticate(context, "cashier01", "123456".toCharArray(), 2_000_001))
        assertEquals(StaffRole.CASHIER, LocalSessionState.role)
        assertTrue(LocalSessionState.authenticated)
        assertFalse(LocalSessionState.isOwner)
        assertFalse(LocalSessionState.isManager)
        LocalSessionState.establish("Synthetic owner")
        assertTrue(LocalStaffAuth.create(context, "manager01", "Counter Manager", StaffRole.MANAGER, "654321".toCharArray()))
        LocalSessionState.clear()
        assertEquals(AuthResult.ACCEPTED, LocalStaffAuth.authenticate(context, "manager01", "654321".toCharArray(), 2_000_002))
        assertTrue(LocalSessionState.isManager)
        assertFalse(LocalSessionState.isOwner)
        SqliteCounterPersistence(context).use { db ->
            assertEquals(StaffRole.CASHIER, db.loadStaffCredential("cashier01")?.account?.role)
            assertTrue("A manager cannot disable staff accounts", runCatching {
                db.setStaffAccountEnabled("cashier01", false, java.util.UUID.randomUUID().toString())
            }.exceptionOrNull() is SecurityException)
            LocalSessionState.establish("Synthetic owner")
            assertTrue(db.setStaffAccountEnabled("cashier01", false, java.util.UUID.randomUUID().toString()))
            val audit = db.loadAuditRecords()
            assertEquals("local-owner", audit.filter { it.event.startsWith("staff.account_created") }.first { it.event.contains("CASHIER") }.actorId)
            assertEquals("local-owner", audit.filter { it.event.startsWith("staff.account_created") }.first { it.event.contains("MANAGER") }.actorId)
            assertEquals("cashier01", audit.single { it.event == "staff.login_succeeded" && it.actorId == "cashier01" }.actorId)
            assertTrue(audit.filter { it.event == "staff.login_failed" }.all { it.actorId == null })
            assertEquals("local-owner", audit.single { it.event == "staff.account_disabled" }.actorId)
        }
        LocalSessionState.clear()
        assertEquals(AuthResult.REJECTED, LocalStaffAuth.authenticate(context, "cashier01", "123456".toCharArray(), 2_000_100))
        LocalSessionState.clear()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun businessAuditRecordsPersistAuthenticatedActorAcrossEncryptedReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic owner")
        CounterModel(SqliteCounterPersistence(context)).use { model ->
            assertTrue(model.saveProduct(TapriProduct("audit_tea", "Audit Tea", "Tea", 1250, priceConfirmed = true)).contains("Product saved"))
            assertTrue(model.saveShopProfile(ShopProfile("Audit Shop", "tea_snacks", "hi-IN", "INR")).startsWith("Shop setup saved"))
            assertTrue(model.saveCustomerProfile(CustomerProfile("audit-customer", "Audit Customer")).contains("Customer profile saved"))
            assertTrue(model.apply("actor-correction-draft", ProposedCommand("create", null, "create draft", "chai", 2)).contains("Order #12 created"))
            LocalSessionState.establishStaff("audit-cashier", "Synthetic Cashier", StaffRole.CASHIER)
            assertTrue(model.saveProduct(TapriProduct("blocked_tea", "Blocked Tea", "Tea", 900, priceConfirmed = true)).contains("SecurityException"))
            assertTrue(model.saveShopProfile(ShopProfile("Unauthorized Edit", "tea_snacks", "hi-IN", "INR")).contains("SecurityException"))
            assertTrue(model.saveCustomerProfile(CustomerProfile("blocked-customer", "Private Customer", "+919900001111", true)).contains("SecurityException"))
            assertTrue(model.updateCustomerProfile(CustomerProfile("audit-customer", "Changed Customer" )).contains("SecurityException"))
            assertTrue(model.apply("cashier-order-line-correction", ProposedCommand("remove", 12, "cashier correction", "chai", 1)).contains("SecurityException"))
            assertEquals(2, model.allOrders().single { it.number == 12 }.lines.single().quantity)
            assertTrue(model.confirmOpeningStock("chai", 4, "synthetic attribution test", "actor-attributed-opening").contains("4 units"))
            assertTrue(model.quickSale(listOf("chai" to 1), "cash", commandId = "actor-attributed-sale").startsWith("Quick sale"))
            LocalSessionState.establishStaff("audit-manager", "Synthetic Manager", StaffRole.MANAGER)
            assertTrue(model.saveCustomerProfile(CustomerProfile("manager-customer", "Manager Customer")).contains("Customer profile saved"))
            assertTrue(model.apply("manager-order-line-correction", ProposedCommand("remove", 12, "manager correction", "chai", 1, expectedRevision = model.allOrders().single { it.number == 12 }.revision)).contains("Item removed"))
            LocalSessionState.establish("Synthetic owner")
            val record = model.auditRecords().single { it.commandId == "actor-attributed-sale" }
            assertEquals("audit-cashier", record.actorId)
            assertFalse(record.event.contains("123456"))
            assertEquals("local-owner", model.auditRecords().single { it.event.contains("product.catalog_saved product_id=audit_tea") }.actorId)
            assertEquals("local-owner", model.auditRecords().single { it.event.contains("shop.profile_updated") }.actorId)
            assertTrue(model.auditRecords().none { it.event.contains("product.catalog_saved product_id=blocked_tea") })
            assertTrue(model.auditRecords().none { it.event.contains("customer.profile_saved") && it.actorId == "audit-cashier" })
            assertEquals("audit-manager", model.auditRecords().single { it.event.contains("customer.profile_saved") && it.actorId == "audit-manager" }.actorId)
            assertEquals("audit-manager", model.auditRecords().single { it.event.contains("remove #12") }.actorId)
            assertEquals("audit-cashier", model.auditRecords().single { it.commandId == "actor-attributed-opening" }.actorId)
        }
        LocalSessionState.clear()
        CounterModel(SqliteCounterPersistence(context)).use { reopened ->
            val record = reopened.auditRecords().single { it.commandId == "actor-attributed-sale" }
            assertEquals("audit-cashier", record.actorId)
            assertTrue(record.event.contains("quick_sale"))
        }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun schemaV24ToV25AddsActorColumnAndPreservesHistoricalAudit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val prior = SqliteCounterPersistence(context)
        prior.writableDatabase.execSQL("INSERT INTO audit_events(event_id,command_id,event,created_at) VALUES('legacy-audit','legacy-command','legacy.event',123)")
        prior.writableDatabase.execSQL("ALTER TABLE audit_events DROP COLUMN actor_id")
        prior.writableDatabase.version = 24
        prior.close()

        SqliteCounterPersistence(context).use { upgraded ->
            assertEquals(27, upgraded.readableDatabase.version)
            val record = upgraded.loadAuditRecords().single { it.eventId == "legacy-audit" }
            assertEquals("legacy.event", record.event)
            assertNull(record.actorId)
        }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun cashierCanSignInThroughAccessScreenAndSeesCounterOnlyMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.clear()
        assertTrue(LocalOwnerAuth.enroll(context, "ui-owner", "UI Test Owner", "Synthetic owner passphrase for staff UI".toCharArray()))
        LocalSessionState.establish("UI Test Owner")
        assertTrue(LocalStaffAuth.create(context, "ui-cashier", "UI Test Cashier", StaffRole.CASHIER, "246810".toCharArray()))
        LocalSessionState.clear()

        val mainMonitor = Instrumentation.ActivityMonitor(MainActivity::class.java.name, null, false)
        var access: LocalAccessActivity? = null
        var main: MainActivity? = null
        try {
            instrumentation.addMonitor(mainMonitor)
            access = instrumentation.startActivitySync(Intent(context, LocalAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LocalAccessActivity
            fun find(text: String? = null, hint: String? = null): AccessibilityNodeInfo {
                var last = ""
                repeat(160) {
                    var selected: AccessibilityNodeInfo? = null
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val labels = mutableListOf<String>()
                    fun walk(node: AccessibilityNodeInfo) {
                        node.text?.toString()?.let(labels::add)
                        node.hintText?.toString()?.let(labels::add)
                        if (selected == null && ((text != null && node.text?.toString()?.startsWith(text, true) == true) || (hint != null && node.hintText?.toString() == hint))) selected = node
                        for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
                    }
                    root?.let(::walk)
                    last = labels.joinToString(" | ")
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Staff access UI element not found: text=$text hint=$hint; screen=$last")
            }
            assertTrue(find(text = "Staff sign in").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(find(hint = "Staff ID").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ui-cashier") }))
            assertTrue(find(hint = "Six-digit PIN").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "246810") }))
            assertTrue(find(text = "Sign in").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            main = mainMonitor.waitForActivityWithTimeout(120_000) as? MainActivity
            assertNotNull("Cashier PIN should open the counter", main)
            assertEquals("ui-cashier", LocalSessionState.actorId)
            assertEquals(StaffRole.CASHIER, LocalSessionState.role)
            assertTrue(find(text = "Cashier: UI Test Cashier").text.toString().contains("Cashier"))
            assertTrue(find(text = "Quick Sale").text.toString().contains("Quick Sale", ignoreCase = true))
            var staffAdminVisible = false
            runCatching { find(text = "Staff Accounts"); staffAdminVisible = true }
            assertFalse("Cashier must not see owner staff administration", staffAdminVisible)
        } finally {
            instrumentation.removeMonitor(mainMonitor)
            instrumentation.runOnMainSync { main?.finish(); access?.finish(); LocalSessionState.clear() }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun ownerCanCreateAndDisableStaffFromManagementScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.clear()
        assertTrue(LocalOwnerAuth.enroll(context, "staff-admin-owner", "Staff Admin Owner", "Synthetic owner passphrase for staff admin".toCharArray()))
        LocalSessionState.establish("Staff Admin Owner")
        val activity = instrumentation.startActivitySync(Intent(context, StaffManagementActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StaffManagementActivity
        try {
            fun find(text: String? = null, hint: String? = null): AccessibilityNodeInfo {
                var screen = ""
                repeat(120) {
                    var match: AccessibilityNodeInfo? = null
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val labels = mutableListOf<String>()
                    fun walk(node: AccessibilityNodeInfo) {
                        node.text?.toString()?.let(labels::add)
                        node.hintText?.toString()?.let(labels::add)
                        if (match == null && ((text != null && node.text?.toString()?.startsWith(text, true) == true) || (hint != null && node.hintText?.toString() == hint))) match = node
                        for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
                    }
                    root?.let(::walk)
                    screen = labels.joinToString(" | ")
                    match?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Staff management UI node not found: text=$text hint=$hint; UI=$screen")
            }
            fun enter(hint: String, value: String) {
                assertTrue(find(hint = hint).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }

            val staffId = "managed-ui"
            enter("Staff ID", staffId)
            enter("Display name", "Managed Cashier")
            enter("Six-digit PIN", "135790")
            instrumentation.runOnMainSync {
                fun findSpinner(view: View): Spinner? {
                    if (view is Spinner) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) findSpinner(view.getChildAt(i))?.let { return it }
                    return null
                }
                (findSpinner(activity.findViewById(android.R.id.content)) ?: throw AssertionError("Role selector missing")).setSelection(0)
            }
            assertTrue(find(text = "Create staff account").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Account creation should show success feedback", find(text = "Staff account created.").text.toString().contains("created", true))
            SqliteCounterPersistence(context).use { db ->
                val credential = db.loadStaffCredential(staffId)
                assertNotNull(credential)
                assertEquals("Managed Cashier", credential?.account?.displayName)
                assertEquals(StaffRole.CASHIER, credential?.account?.role)
                assertTrue(credential?.account?.enabled == true)
                assertFalse(credential?.verifier?.toString(Charsets.UTF_8)?.contains("135790") == true)
            }
            assertTrue(find(text = "Disable Managed Cashier").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(find(text = "Account status updated.").text.toString().contains("updated", true))
            SqliteCounterPersistence(context).use { db -> assertFalse(db.loadStaffCredential(staffId)?.account?.enabled ?: true) }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); LocalSessionState.clear() }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun devicePermissionsScreenDisclosesOptionalAccessAndTypedFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("ct04_permission_history", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO) }
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT) }
        val activity = instrumentation.startActivitySync(Intent(context, DevicePermissionsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue(strings.any { it.contains("Device Permissions") })
        assertTrue(strings.any { it.contains("Microphone") && it.contains("typed fallback") })
        assertTrue(strings.any { it.contains("Not granted") || it.contains("Denied") })
        assertTrue(strings.any { it.contains("Bluetooth route access") })
        assertTrue(strings.any { it.contains("Camera · Not requested") })
        assertTrue(strings.any { it.contains("No broad storage permission") })
        assertTrue(strings.any { it.contains("Request optional voice permissions") })
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun cloudSyncScreenIsOwnerGatedAndExplainsHttpsOutboxLimits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        SyncSessionVault.resetForTests(context)
        context.getSharedPreferences("chai_tapri_sync_device_identity_v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val activity = instrumentation.startActivitySync(Intent(context, CloudSyncActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        val inputs = mutableListOf<EditText>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString() + " " + view.hint.toString()
            if (view is EditText) inputs += view
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue(strings.any { it.contains("Cloud Sync") })
        assertTrue(strings.any { it.contains("trusted HTTPS backend") })
        assertTrue(strings.any { it.contains("Create server account and save session") })
        assertTrue(strings.any { it.contains("Sign back into this registered shop and device") })
        assertTrue(strings.any { it.contains("Sync now") })
        assertTrue(strings.any { it.contains("Revoke server session") })
        val password = inputs.firstOrNull { it.hint?.toString() == "Account password" }
        assertNotNull(password)
        assertTrue(password!!.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertFalse(SyncSessionVault.encryptedEnvelopeForTests(context).values.any { it != null })
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun refundCorrectionScreenShowsAuditedItemAndAmountPathsForEligibleOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1500, priceConfirmed = true)).contains("saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-12 screen fixture", "ct12-screen-stock").contains("10 units"))
        assertTrue(model.quickSale(listOf("chai" to 2), "cash", commandId = "ct12-screen-sale").startsWith("Quick sale"))
        val orderNumber = model.allOrders().single().number
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, RefundCorrectionActivity::class.java)
            .putExtra(RefundCorrectionActivity.EXTRA_ORDER_NUMBER, orderNumber)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        for (attempt in 0 until 80) {
            strings.clear()
            instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
            if (strings.any { it.contains("Return delivered item(s)") }) break
            Thread.sleep(100)
        }
        assertTrue(strings.any { it.contains("Order #$orderNumber") })
        assertTrue(strings.any { it.contains("Return delivered item(s)") })
        assertTrue(strings.any { it.contains("Goodwill / amount correction") })
        assertTrue(strings.any { it.contains("staff-declared") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun salesHistoryLaunchesAndCommitsAuditedItemRefundWithoutRestocking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("history_refund_tea", "History Refund Tea", "Chai", 1200, priceConfirmed = true)).contains("Product saved"))
        assertTrue(seed.confirmOpeningStock("history_refund_tea", 10, "synthetic CT-11/12 refund count", "history-refund-stock").contains("10 units"))
        assertTrue(seed.quickSale(listOf("history_refund_tea" to 2), "cash", commandId = "history-refund-sale").startsWith("Quick sale"))
        val orderNumber = seed.allOrders().single().number
        seed.close()

        val monitor = instrumentation.addMonitor(RefundCorrectionActivity::class.java.name, null, false)
        val history = instrumentation.startActivitySync(Intent(context, SalesHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var refundActivity: Activity? = null
        try {
            fun awaitNode(prefix: String? = null, hint: String? = null): AccessibilityNodeInfo {
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        val text = node.text?.toString().orEmpty()
                        val nodeHint = node.hintText?.toString().orEmpty()
                        if ((prefix == null || text.startsWith(prefix, ignoreCase = true)) &&
                            (hint == null || nodeHint.startsWith(hint, ignoreCase = true))) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Sales history/refund UI item not found: ${prefix ?: hint}")
            }
            fun awaitTextContaining(fragment: String): AccessibilityNodeInfo {
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString()?.contains(fragment, ignoreCase = true) == true) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Updated refund balance was not rendered: $fragment")
            }
            fun click(prefix: String) {
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun activate(node: AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        val text = node.text?.toString().orEmpty()
                        if (text.startsWith(prefix, ignoreCase = true) && node.isClickable && node.isEnabled &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        for (index in 0 until node.childCount) if (activate(node.getChild(index))) return true
                        return false
                    }
                    if (activate(root)) return
                    Thread.sleep(100)
                }
                assertTrue("Could not activate UI control: $prefix", false)
            }

            click("Refund / correct this order")
            refundActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertNotNull("Sales History should route into CT-12", refundActivity)
            click("Return delivered item(s)")
            val reason = awaitNode(hint = "Required return/correction reason")
            assertTrue("Refund reason should accept focus", reason.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val reasonArgs = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Damaged sealed cup") }
            assertTrue("Refund reason should accept text", reason.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, reasonArgs))
            assertTrue("Refund reason should be visible", awaitNode(prefix = "Damaged sealed cup").text.toString().contains("Damaged sealed cup"))
            instrumentation.runOnMainSync {
                val imm = refundActivity!!.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(refundActivity!!.window.decorView.windowToken, 0)
            }
            instrumentation.waitForIdleSync()
            val cashConfirmed = awaitNode(prefix = "I confirm any cash refund was handed to the customer")
            if (!cashConfirmed.isChecked) assertTrue("Cash refund requires explicit staff confirmation", cashConfirmed.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click("Review")
            click("Record audited refund")

            awaitNode(prefix = "Refund & Correction")
            val refreshedBalance = awaitTextContaining("refunded \u20B912.00")
            assertTrue(refreshedBalance.text.toString().contains("due \u20B90.00"))
            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val refunded = reopened.allOrders().single { it.number == orderNumber }
            assertEquals(1200, refunded.refundedPaise)
            assertEquals(1200, refunded.paidPaise)
            assertEquals(0, refunded.duePaise)
            assertEquals(1, refunded.lines.single().refundedQuantity)
            assertEquals(8, reopened.stockLevels()["history_refund_tea"])
            assertEquals(-2, reopened.stockMovements().single { it.reason == "quick_sale" }.quantityDelta)
            assertTrue(reopened.stockMovements().none { it.reason == "refund_restock" })
            assertTrue(reopened.paymentHistory().any { it.orderNumber == orderNumber && it.status == "staff_declared_refund" && it.amountPaise == -1200 })
            assertTrue(reopened.auditEvents().any { it.contains("refund") && it.contains("1200") })
            reopened.close()
        } finally {
            instrumentation.runOnMainSync {
                refundActivity?.finish()
                history.finish()
            }
            instrumentation.removeMonitor(monitor)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun salesHistorySearchFiltersPersistedOrdersAndPaymentStatesWithoutMutation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context))
        model.confirmOpeningStock("chai", 10, "CT-11 search synthetic stock", "ct11-search-stock-chai")
        model.confirmOpeningStock("samosa", 10, "CT-11 search synthetic stock", "ct11-search-stock-samosa")
        assertTrue(model.quickSale(listOf("chai" to 1), "cash", commandId = "ct11-search-cash").startsWith("Quick sale"))
        val cashOrder = model.allOrders().first().number
        val cashOrderCreatedAt = model.allOrders().first().createdAtEpochMs
        assertTrue(model.quickSale(listOf("samosa" to 1), "upi_pending", commandId = "ct11-search-upi").startsWith("Quick sale"))
        val upiOrder = model.allOrders().first().number
        val upiOrderCreatedAt = model.allOrders().first().createdAtEpochMs
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, SalesHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var search: EditText? = null
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is EditText && view.contentDescription == "Search sales history") search = view
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue("History search should be present", search != null)
        assertTrue(strings.any { it == "From date" })
        assertTrue(strings.any { it == "Through date" })
        assertTrue(strings.any { it.contains("date range uses order creation date") })
        assertTrue(strings.any { it.contains("Order date:") })
        instrumentation.runOnMainSync { search!!.setText("samosa") }
        strings.clear()
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue(strings.any { it.contains("Order #$upiOrder") })
        assertTrue(strings.none { it.contains("Order #$cashOrder") })
        assertTrue(strings.any { it.contains("UPI") || it.contains("upi") })

        instrumentation.runOnMainSync { search!!.setText("no matching product") }
        strings.clear()
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue(strings.any { it.contains("No orders match the current filters") })
        val after = CounterModel(SqliteCounterPersistence(context))
        assertEquals(2, after.allOrders().size)
        assertEquals(upiOrder, after.allOrders().first().number)
        assertEquals(upiOrderCreatedAt, after.allOrders().first().createdAtEpochMs)
        assertEquals(cashOrderCreatedAt, after.allOrders().last().createdAtEpochMs)
        after.close()
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun paymentScreenShowsPendingUpiAsUnverifiedAndOffersStaffDeclaredFollowUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 payment fixture", "ct10-opening").contains("10 units"))
        assertTrue(model.quickSale(listOf("chai" to 2), "upi_pending", commandId = "ct10-upi-order").startsWith("Quick sale"))
        val orderNumber = model.allOrders().single().number
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, orderNumber).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
        assertTrue(strings.any { it.contains("Payments · CT-10") })
        assertTrue(strings.any { it.contains("UPI receipt not verified") })
        assertTrue(strings.any { it == "Record UPI received · staff-declared" })
        assertTrue(strings.any { it.contains("provider verification is not integrated") })
        val after = CounterModel(SqliteCounterPersistence(context))
        assertEquals(Payment.UPI_PENDING, after.allOrders().single().payment)
        assertEquals(0, after.allOrders().single().paidPaise)
        assertEquals(1, after.paymentHistory().count { it.status == "pending" })
        after.close()
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun staffDeclaredUpiReceiptPersistsAttemptCancellationAndCannotDuplicateAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 UPI persistence fixture", "ct10-upi-stock").contains("10 units"))
        assertTrue(model.quickSale(listOf("chai" to 2), "upi_pending", commandId = "ct10-upi-sale").startsWith("Quick sale"))
        val order = model.allOrders().single()
        val attempt = model.paymentHistory().single { it.method == "upi" && it.status == "pending" }
        assertTrue(model.recordUpiReceived(order.number, 750, "ct10-upi-received", order.revision).contains("not provider-verified"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val restored = model.allOrders().single()
        assertEquals(1250, restored.duePaise)
        assertEquals(750, restored.paidPaise)
        assertEquals(Payment.PARTIALLY_PAID, restored.payment)
        val cancelled = model.paymentHistory().single { it.kind == "payment_attempt_cancelled" }
        assertEquals(attempt.entryId, cancelled.relatedEntryId)
        val receipt = model.paymentHistory().single { it.entryId == "ct10-upi-received" }
        assertEquals("upi", receipt.method)
        assertEquals("staff_declared", receipt.status)
        assertEquals(0, model.paymentHistory().count { it.status == "verified" })
        assertEquals("Duplicate command ignored.", model.recordUpiReceived(restored.number, 750, "ct10-upi-received", restored.revision))
        assertEquals(750, model.allOrders().single().paidPaise)
        assertEquals(1, model.paymentHistory().count { it.entryId == "ct10-upi-received" })
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun paymentScreenConfirmsAndRecordsPartialStaffDeclaredUpiReceipt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 UI receipt fixture", "ct10-ui-opening").contains("10 units"))
        assertTrue(model.quickSale(listOf("chai" to 2), "upi_pending", commandId = "ct10-ui-upi-order").startsWith("Quick sale"))
        val order = model.allOrders().single()
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(textPrefix: String? = null, className: String? = null): AccessibilityNodeInfo {
                var lastTree = "<no active window>"
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    if (root != null) {
                        val labels = mutableListOf<String>()
                        fun collect(node: AccessibilityNodeInfo) {
                            val value = node.text?.toString()
                            value?.let(labels::add)
                            if (selected == null && (if (textPrefix != null) value?.startsWith(textPrefix, ignoreCase = true) == true
                                else node.className?.toString() == className)) selected = node
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                        }
                        collect(root)
                        lastTree = labels.joinToString(" | ")
                    }
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Accessibility node not found: text=$textPrefix class=$className; current UI: $lastTree")
            }
            fun clickText(prefix: String) {
                val node = awaitNode(textPrefix = prefix)
                assertTrue("Expected clickable accessibility node for $prefix", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }

            clickText("Record UPI received")
            val amount = awaitNode(className = EditText::class.java.name)
            val input = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "7.50") }
            assertTrue("Amount field should accept accessibility text entry", amount.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            clickText("Continue")
            clickText("Record staff-declared receipt")

            var receipt: PaymentEntry? = null
            for (attempt in 0 until 100) {
                val reopened = CounterModel(SqliteCounterPersistence(context))
                receipt = reopened.paymentHistory().firstOrNull { it.method == "upi" && it.status == "staff_declared" }
                reopened.close()
                if (receipt != null) break
                Thread.sleep(100)
            }
            assertNotNull("Confirmed payment UI should append a receipt", receipt)
            assertEquals("upi", receipt!!.method)
            assertEquals("staff_declared", receipt!!.status)
            val verified = CounterModel(SqliteCounterPersistence(context))
            assertEquals(750, verified.allOrders().single().paidPaise)
            assertEquals(Payment.PARTIALLY_PAID, verified.allOrders().single().payment)
            assertEquals(0, verified.paymentHistory().count { it.status == "verified" })
            assertEquals(1, verified.paymentHistory().count { it.kind == "payment_attempt_cancelled" })
            verified.close()
        } finally {
            instrumentation.runOnMainSync {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.window.decorView.clearFocus()
                activity.finish()
            }
            Thread.sleep(300)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun paymentScreenConfirmsAndAtomicallyRecordsCashUdharSplit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 split UI fixture", "ct10-split-opening").contains("10 units"))
        assertTrue(model.saveCustomerProfile(CustomerProfile("ramesh-ct10", "Ramesh CT10")).contains("saved"))
        assertTrue(model.apply("ct10-split-create", ProposedCommand("create", null, "Create synthetic order", "chai")).contains("created"))
        assertTrue(model.apply("ct10-split-ready", ProposedCommand("ready", 12, "Ready order")).contains("ready"))
        assertTrue(model.apply("ct10-split-given", ProposedCommand("given", 12, "Hand over order")).contains("given"))
        val orderNumber = model.allOrders().single().number
        val stockBefore = model.stockLevels()["chai"]
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, orderNumber).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                var lastTree = "<no active window>"
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    if (root != null) {
                        val labels = mutableListOf<String>()
                        fun collect(node: AccessibilityNodeInfo) {
                            node.text?.toString()?.let(labels::add)
                            node.hintText?.toString()?.let(labels::add)
                            if (selected == null && (if (prefix != null) node.text?.toString()?.startsWith(prefix, true) == true
                                else if (hint != null) node.hintText?.toString()?.equals(hint, true) == true
                                else node.className?.toString() == className)) selected = node
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                        }
                        collect(root)
                        lastTree = labels.joinToString(" | ")
                    }
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Accessibility node not found: prefix=$prefix hint=$hint class=$className; UI=$lastTree")
            }
            fun clickText(prefix: String) {
                val node = awaitNode(prefix = prefix)
                // Wait for dialog/window animations to finish before injecting
                // a screen tap; the emulator can expose the next label early.
                Thread.sleep(300)
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                assertTrue("No visible bounds for $prefix: $bounds", !bounds.isEmpty)
                val automation = instrumentation.uiAutomation
                val now = android.os.SystemClock.uptimeMillis()
                val x = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    assertTrue("Could not tap $prefix", automation.injectInputEvent(down, true))
                    assertTrue("Could not release tap $prefix", automation.injectInputEvent(up, true))
                } finally {
                    down.recycle()
                    up.recycle()
                }
                Thread.sleep(300)
            }
            fun enterAmount(value: String) {
                val field = awaitNode(hint = "Cash received in rupees", className = EditText::class.java.name)
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }

            clickText("Split cash + Udhar")
            clickText("Ramesh CT10")
            enterAmount("5.00")
            clickText("Review split")
            clickText("Record split")

            var saved: CounterOrder? = null
            for (attempt in 0 until 100) {
                val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
                saved = reopened.allOrders().singleOrNull { it.number == orderNumber }
                val complete = saved?.payment == Payment.SPLIT && reopened.paymentHistory().count { it.orderNumber == orderNumber } == 2
                reopened.close()
                if (complete) break
                Thread.sleep(100)
            }
            assertEquals(Payment.SPLIT, saved?.payment)
            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val result = reopened.allOrders().single { it.number == orderNumber }
            assertEquals(0, result.duePaise)
            assertEquals(500, result.paidPaise)
            assertEquals(1000, result.creditPaise)
            assertEquals(1000, reopened.creditBalance("ramesh-ct10"))
            assertEquals("staff_declared", reopened.paymentHistory().single { it.method == "cash" }.status)
            assertEquals("receivable", reopened.paymentHistory().single { it.method == "udhar" }.status)
            assertEquals(0, reopened.paymentHistory().count { it.status == "verified" })
            assertEquals(stockBefore, reopened.stockLevels()["chai"])
            assertEquals(1, reopened.auditEvents().count { it.contains("cash_udhar_split #$orderNumber") })
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun paymentScreenRecordsCashPlusUpiPendingAndResolvesItWithoutFalseVerification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 cash UPI fixture", "ct10-cash-upi-opening").contains("10 units"))
        assertTrue(model.apply("ct10-cash-upi-create", ProposedCommand("create", null, "Create cash UPI order", "chai", 2)).contains("created"))
        assertTrue(model.apply("ct10-cash-upi-ready", ProposedCommand("ready", 12, "Ready cash UPI order")).contains("ready"))
        assertTrue(model.apply("ct10-cash-upi-given", ProposedCommand("given", 12, "Hand over cash UPI order")).contains("given"))
        val order = model.allOrders().single()
        val stockBefore = model.stockLevels()["chai"]
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var followupActivity: Activity? = null
        try {
            fun awaitNode(prefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                var lastTree = "<no active window>"
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    if (root != null) {
                        val labels = mutableListOf<String>()
                        fun collect(node: AccessibilityNodeInfo) {
                            node.text?.toString()?.let(labels::add)
                            node.hintText?.toString()?.let(labels::add)
                            if (selected == null && (if (prefix != null) node.text?.toString()?.startsWith(prefix, true) == true
                                else if (hint != null) node.hintText?.toString()?.equals(hint, true) == true
                                else node.className?.toString() == className)) selected = node
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                        }
                        collect(root)
                        lastTree = labels.joinToString(" | ")
                    }
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Accessibility node not found: prefix=$prefix hint=$hint class=$className; UI=$lastTree")
            }
            fun clickText(prefix: String) { assertTrue(awaitNode(prefix = prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            fun enterCash(value: String) {
                val field = awaitNode(hint = "Cash received in rupees", className = EditText::class.java.name)
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }

            clickText("Split cash + UPI pending")
            enterCash("5.00")
            clickText("Review split")
            clickText("Record split")

            val splitScreen = mutableListOf<String>()
            fun collectSplitScreen(view: View) {
                if (view is TextView) splitScreen += view.text.toString()
                if (view is ViewGroup) for (index in 0 until view.childCount) collectSplitScreen(view.getChildAt(index))
            }
            instrumentation.runOnMainSync { collectSplitScreen(activity.window.decorView) }
            assertTrue("Payment screen should show pending state after split: $splitScreen", splitScreen.any { it.contains("UPI_PENDING") })
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(250)

            var saved = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            var recorded = saved.allOrders().single { it.number == order.number }
            assertEquals("Saved split should persist after activity restart: $recorded payments=${saved.paymentHistory()} audit=${saved.auditEvents()}", Payment.UPI_PENDING, recorded.payment)
            assertEquals(500, recorded.paidPaise)
            assertEquals(1500, recorded.duePaise)
            assertEquals(500, saved.paymentHistory().single { it.method == "cash" }.amountPaise)
            val attempt = saved.paymentHistory().single { it.method == "upi" && it.status == "pending" }
            assertEquals(1500, attempt.amountPaise)
            assertEquals(0, saved.paymentHistory().count { it.status == "verified" })
            saved.close()

            followupActivity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
                .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            clickText("Record UPI received")
            clickText("Continue")
            clickText("Record staff-declared receipt")
            instrumentation.runOnMainSync { followupActivity?.finish() }
            Thread.sleep(250)
            saved = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            recorded = saved.allOrders().single { it.number == order.number }
            assertEquals(Payment.STAFF_DECLARED, recorded.payment)
            assertEquals(2000, recorded.paidPaise)
            assertEquals(0, recorded.duePaise)
            assertEquals(attempt.entryId, saved.paymentHistory().single { it.kind == "payment_attempt_cancelled" }.relatedEntryId)
            assertEquals(0, saved.paymentHistory().count { it.status == "verified" })
            assertEquals(stockBefore, saved.stockLevels()["chai"])
            assertEquals(1, saved.auditEvents().count { it.contains("cash_upi_pending_split #${order.number}") })
            saved.close()
        } finally {
            instrumentation.runOnMainSync {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.window.decorView.clearFocus()
                activity.finish()
                followupActivity?.finish()
            }
            Thread.sleep(300)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun paymentScreenConfirmsDirectUpiAsStaffDeclaredWithoutPendingOrVerifiedState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "CT-10 direct UPI fixture", "ct10-direct-upi-opening").contains("10 units"))
        assertTrue(model.apply("ct10-direct-upi-create", ProposedCommand("create", null, "Create direct UPI order", "chai", 2)).contains("created"))
        assertTrue(model.apply("ct10-direct-upi-ready", ProposedCommand("ready", 12, "Ready direct UPI order")).contains("ready"))
        assertTrue(model.apply("ct10-direct-upi-given", ProposedCommand("given", 12, "Hand over direct UPI order")).contains("given"))
        val order = model.allOrders().single()
        val stockBefore = model.stockLevels()["chai"]
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    fun collect(node: AccessibilityNodeInfo) {
                        if (selected == null && (if (prefix != null) node.text?.toString()?.startsWith(prefix, true) == true
                            else if (hint != null) node.hintText?.toString()?.equals(hint, true) == true
                            else node.className?.toString() == className)) selected = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                    }
                    root?.let(::collect)
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Payment UI node not found: prefix=$prefix hint=$hint class=$className")
            }
            fun clickText(prefix: String) { assertTrue(awaitNode(prefix = prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            clickText("Record UPI received · staff-declared")
            val amount = awaitNode(className = EditText::class.java.name)
            assertTrue(amount.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "5.00")
            }))
            clickText("Continue")
            clickText("Record staff-declared receipt")
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(250)

            val restored = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val paidOrder = restored.allOrders().single { it.number == order.number }
            assertEquals(Payment.PARTIALLY_PAID, paidOrder.payment)
            assertEquals(500, paidOrder.paidPaise)
            assertEquals(1500, paidOrder.duePaise)
            val receipt = restored.paymentHistory().single()
            assertEquals("upi", receipt.method)
            assertEquals("staff_declared", receipt.status)
            assertEquals("order_collection", receipt.kind)
            assertEquals(0, restored.paymentHistory().count { it.status == "pending" || it.status == "verified" })
            assertEquals(stockBefore, restored.stockLevels()["chai"])
            assertEquals(1, restored.auditEvents().count { it.contains("upi_direct #${order.number}") })
            restored.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun paymentScreenHistoryShowsCashAndCreditEvidenceWithoutClaimingVerification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Test Chai", "Tea", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 5, "CT-10 history fixture", "ct10-history-opening").contains("5 units"))
        assertTrue(model.apply("ct10-history-create", ProposedCommand("create", null, "Create history fixture", "chai", 2)).contains("created"))
        assertTrue(model.apply("ct10-history-ready", ProposedCommand("ready", 12, "Ready history fixture")).contains("ready"))
        assertTrue(model.apply("ct10-history-given", ProposedCommand("given", 12, "Hand over history fixture")).contains("given"))
        var order = model.allOrders().single()
        assertTrue(model.recordUpiPending(order.number, "ct10-history-upi-pending", order.revision).contains("UPI pending"))
        order = model.allOrders().single()
        assertTrue(model.recordUpiReceived(order.number, 500, "ct10-history-upi-received", order.revision).contains("not provider-verified"))
        order = model.allOrders().single()
        assertTrue(model.recordCashUdharSplit(order.number, 500, "history-customer", "ct10-history-split", order.revision).contains("Split recorded"))
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, PaymentActivity::class.java)
            .putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun findText(prefix: String): AccessibilityNodeInfo? {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
                fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) return node
                    for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
                    return null
                }
                return search(root)
            }
            var historyButton: AccessibilityNodeInfo? = null
            for (attempt in 0 until 100) {
                historyButton = findText("Payment history")
                if (historyButton != null) break
                instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollActiveWindowForward)
                Thread.sleep(100)
            }
            assertNotNull("Payment history action should be present", historyButton)
            assertTrue(historyButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var visibleText = ""
            for (attempt in 0 until 100) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val values = mutableListOf<String>()
                fun collect(node: AccessibilityNodeInfo) {
                    node.text?.toString()?.let(values::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                }
                root?.let(::collect)
                visibleText = values.joinToString(" | ")
                if (visibleText.contains("staff-declared", true) && visibleText.contains("Udhar receivable", true)) break
                Thread.sleep(100)
            }
            assertTrue("History should disclose declared cash: $visibleText", visibleText.contains("Cash; staff-declared", true))
            assertTrue("History should identify credit as receivable: $visibleText", visibleText.contains("Udhar receivable; not collected", true))
            assertTrue("History should make provider verification status explicit: $visibleText", visibleText.contains("not provider-verified", true))
            assertTrue("History should show the cash amount: $visibleText", visibleText.contains("₹5.00"))
            assertTrue("History should show the receivable amount: $visibleText", visibleText.contains("₹20.00"))
            assertTrue("History should distinguish a staff-declared UPI receipt from external verification: $visibleText", visibleText.contains("UPI receipt; staff-declared, not provider-verified", true))
            assertFalse("The UI must not claim external payment verification", visibleText.contains("| verified", true))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun dismissingRecoverySecretDialogReleasesBusyStateAndClearsFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
        var ready = false
        for (attempt in 0 until 120) {
            instrumentation.runOnMainSync { ready = runCatching { statusField.get(activity) != null }.getOrDefault(false) }
            if (ready) break
            Thread.sleep(100)
        }
        assertTrue("Counter activity should finish loading", ready)
        val busyField = MainActivity::class.java.getDeclaredField("recoveryBusy").apply { isAccessible = true }
        val setBusy = MainActivity::class.java.getDeclaredMethod("setRecoveryBusy", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val prompt = MainActivity::class.java.getDeclaredMethod(
            "promptRecoverySecret", String::class.java, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java
        ).apply { isAccessible = true }
        var callbackInvoked = false
        var dialog: AlertDialog? = null
        var busyAfterCancel = true
        var stillShowing = false
        instrumentation.runOnMainSync {
            setBusy.invoke(activity, true)
            val callback: (CharArray) -> Unit = { callbackInvoked = true }
            dialog = prompt.invoke(activity, "Verify encrypted backup", false, callback) as AlertDialog
            assertTrue("Secret entry dialog should be visible", dialog!!.isShowing)
            dialog!!.cancel()
        }
        for (attempt in 0 until 30) {
            instrumentation.runOnMainSync {
                busyAfterCancel = busyField.getBoolean(activity)
                stillShowing = dialog!!.isShowing
            }
            if (!busyAfterCancel) break
            Thread.sleep(100)
        }
        assertTrue("Cancelling secret entry must not execute a restore", !callbackInvoked)
        assertTrue("Dialog cancellation should dismiss the prompt", !stillShowing)
        assertTrue("Cancel/back/outside dismissal must release the temporary busy state (busy=$busyAfterCancel)", !busyAfterCancel)
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun recoveryActivityCompletesBackupVerificationCallbackForSafContentUri() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        CounterModel(SqliteCounterPersistence(context)).close()
        val recoverySecret = "ct051b-ui-verification-secret".toCharArray()
        val backup = java.io.ByteArrayOutputStream()
        PortableSnapshotBackup(context).export(backup, recoverySecret.copyOf())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ct051b-verification-${System.currentTimeMillis()}.ctbackup")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create synthetic SAF verification fixture.")
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(backup.toByteArray()) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
            var status: TextView? = null
            for (attempt in 0 until 120) {
                instrumentation.runOnMainSync { status = runCatching { statusField.get(activity) as TextView }.getOrNull() }
                if (status != null) break
                Thread.sleep(100)
            }
            assertTrue("Counter activity should finish loading before backup verification", status != null)
            val verifyMethod = MainActivity::class.java.getDeclaredMethod("verifyBackup", Uri::class.java, CharArray::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync { verifyMethod.invoke(activity, uri, recoverySecret) }
            var result = ""
            for (attempt in 0 until 900) {
                instrumentation.runOnMainSync { result = status!!.text.toString() }
                if (result.startsWith("Backup verified") || result.startsWith("Backup verification failed")) break
                Thread.sleep(100)
            }
            assertTrue("Recovery verification callback did not complete: $result", result.startsWith("Backup verified"))
            var busyAfterVerification = true
            instrumentation.runOnMainSync { busyAfterVerification = MainActivity::class.java.getDeclaredField("recoveryBusy").apply { isAccessible = true }.getBoolean(activity) }
            assertTrue("Successful verification releases the busy state before metadata confirmation", !busyAfterVerification)
            val model = CounterModel(SqliteCounterPersistence(context))
            assertTrue("Verifying a backup must not create or replace orders", model.allOrders().isEmpty())
            model.close()
            instrumentation.runOnMainSync { activity.finish() }
        } finally {
            recoverySecret.fill('\u0000')
            context.contentResolver.delete(uri, null, null)
            context.deleteDatabase("chai_tapri_counter.db")
        }
    }

    @Test fun ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic Recovery Owner")
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("saf_roundtrip_tea", "SAF Roundtrip Tea", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(seed.confirmOpeningStock("saf_roundtrip_tea", 8, "synthetic SAF roundtrip opening count", "saf-roundtrip-stock").contains("8 units"))
        assertTrue(seed.quickSale(listOf("saf_roundtrip_tea" to 2), "cash", commandId = "saf-roundtrip-cash-sale").startsWith("Quick sale"))
        seed.close()

        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
        val menuMethod = MainActivity::class.java.getDeclaredMethod("showRecoveryMenu").apply { isAccessible = true }
        try {
            var status: TextView? = null
            repeat(120) {
                instrumentation.runOnMainSync { status = runCatching { statusField.get(activity) as TextView }.getOrNull() }
                if (status != null) return@repeat
                Thread.sleep(100)
            }
            assertNotNull("Owner counter should load before starting its backup UI", status)

            val automation = instrumentation.uiAutomation
            fun findNode(textPrefix: String? = null, hintPrefix: String? = null, descriptionPrefix: String? = null): AccessibilityNodeInfo {
                repeat(160) {
                    var found: AccessibilityNodeInfo? = null
                    fun walk(node: AccessibilityNodeInfo) {
                        val text = node.text?.toString().orEmpty()
                        val hint = node.hintText?.toString().orEmpty()
                        val description = node.contentDescription?.toString().orEmpty()
                        if (found == null && ((textPrefix != null && text.startsWith(textPrefix, ignoreCase = true)) ||
                                (hintPrefix != null && hint.startsWith(hintPrefix, ignoreCase = true)) ||
                                (descriptionPrefix != null && description.startsWith(descriptionPrefix, ignoreCase = true)))) found = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                    }
                    automation.rootInActiveWindow?.let(::walk)
                    found?.let { return it }
                    Thread.sleep(100)
                }
                val visible = mutableListOf<String>()
                fun collect(node: AccessibilityNodeInfo) {
                    node.text?.toString()?.let(visible::add)
                    node.contentDescription?.toString()?.let(visible::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                }
                automation.rootInActiveWindow?.let(::collect)
                throw AssertionError("SAF backup UI control not found: text=$textPrefix hint=$hintPrefix description=$descriptionPrefix; visible=${visible.joinToString(" | ")}")
            }
            val names = mutableListOf<String>()
            fun visibleNames(): List<String> {
                names.clear()
                fun collect(node: AccessibilityNodeInfo) {
                    node.text?.toString()?.let(names::add)
                    node.contentDescription?.toString()?.let(names::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                }
                automation.rootInActiveWindow?.let(::collect)
                return names.toList()
            }
            fun clickText(text: String) {
                val textNode = findNode(textPrefix = text)
                var target: AccessibilityNodeInfo? = textNode
                var clicked = false
                while (target != null && !clicked) {
                    if (target.isClickable) clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target = target.parent
                }
                if (!clicked) {
                    val bounds = android.graphics.Rect()
                    textNode.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        val x = bounds.exactCenterX()
                        val y = bounds.exactCenterY()
                        val downTime = android.os.SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0)
                        try {
                            automation.injectInputEvent(down, true)
                            clicked = automation.injectInputEvent(up, true)
                        } finally {
                            down.recycle()
                            up.recycle()
                        }
                    }
                }
                assertTrue("Could not click $text; picker text=${visibleNames().joinToString(" | ")}", clicked)
            }
            fun setText(hint: String, value: String) {
                val field = findNode(hintPrefix = hint)
                assertTrue("Could not enter $hint", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }

            instrumentation.runOnMainSync { menuMethod.invoke(activity) }
            clickText("Create encrypted backup")
            val recoverySecret = "synthetic-saf-roundtrip-secret-2026"
            setText("Recovery secret (12+ characters)", recoverySecret)
            setText("Confirm recovery secret", recoverySecret)
            clickText("Continue")

            // The actual Android CreateDocument picker remains on screen here. SAF opens in
            // the last-used directory; require an explicit Downloads location before saving.
            var pickerText = ""
            val pickerDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < pickerDeadline) {
                pickerText = visibleNames().joinToString(" | ")
                if (pickerText.contains("Downloads", ignoreCase = true) && pickerText.contains("Save", ignoreCase = true)) break
                Thread.sleep(100)
            }
            assertTrue("CreateDocument must open in Downloads before export: $pickerText", pickerText.contains("Downloads", ignoreCase = true) && pickerText.contains("Save", ignoreCase = true))
            val filenameField = runCatching { findNode(hintPrefix = "File name") }.getOrNull()
            filenameField?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "cat051b-saf-roundtrip-${System.currentTimeMillis()}.ctbackup")
            })
            clickText("Save")

            var completion = ""
            val completionDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < completionDeadline) {
                instrumentation.runOnMainSync { completion = status?.text?.toString().orEmpty() }
                if (completion.startsWith("Encrypted backup created") || completion.startsWith("Backup failed")) break
                Thread.sleep(100)
            }
            assertTrue("The real SAF export flow should finish successfully; status=$completion", completion.startsWith("Encrypted backup created"))

            // Reopen the same artifact through Android's real OpenDocument picker and
            // complete the authenticated, explicit-replacement restore flow.
            instrumentation.runOnMainSync { menuMethod.invoke(activity) }
            clickText("Restore from backup")
            var restorePicker = ""
            val restorePickerDeadline = System.currentTimeMillis() + 15_000
            var backupRow = ""
            while (System.currentTimeMillis() < restorePickerDeadline) {
                val names = visibleNames()
                restorePicker = names.joinToString(" | ")
                backupRow = names.firstOrNull { it.startsWith("Preview the file ", ignoreCase = true) && it.endsWith(".ctbackup", ignoreCase = true) }.orEmpty()
                if (backupRow.isNotBlank() && restorePicker.contains("Downloads", ignoreCase = true)) break
                Thread.sleep(100)
            }
            assertTrue("OpenDocument should show the newly exported archive in Downloads: $restorePicker", backupRow.isNotBlank())
            val backupName = backupRow.removePrefix("Preview the file ")
            clickText(backupName)
            var pickerHasOpen = false
            val openButtonDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < openButtonDeadline) {
                pickerHasOpen = visibleNames().any { it.equals("Open", ignoreCase = true) }
                if (pickerHasOpen) break
                Thread.sleep(100)
            }
            if (pickerHasOpen) clickText("Open")
            setText("Recovery secret (12+ characters)", recoverySecret)
            clickText("Continue")
            var verificationText = ""
            val verificationDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < verificationDeadline) {
                verificationText = visibleNames().joinToString(" | ")
                if (verificationText.contains("Backup verified", ignoreCase = true) || verificationText.contains("Backup verification failed", ignoreCase = true)) break
                Thread.sleep(100)
            }
            assertTrue("The exported archive must authenticate before replacement: $verificationText", verificationText.contains("Backup verified", ignoreCase = true))
            clickText("Continue")
            clickText("Replace and restore")
            var restoreActivityFinished = false
            var restoreFailure = ""
            val restoreDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < restoreDeadline) {
                instrumentation.runOnMainSync {
                    restoreActivityFinished = activity.isFinishing
                    restoreFailure = status?.text?.toString().orEmpty().takeIf { it.startsWith("Restore failed") }.orEmpty()
                }
                if (restoreActivityFinished || restoreFailure.isNotEmpty()) break
                Thread.sleep(100)
            }
            assertTrue("The real SAF restore should finish through the login handoff: $restoreFailure", restoreActivityFinished)
            val restored = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            try {
                val restoredOrder = restored.allOrders().single()
                assertTrue("Restored order should retain the exported product", restoredOrder.lines.any { it.productId == "saf_roundtrip_tea" })
                assertTrue("Restored order should retain its cash payment", restoredOrder.paidPaise == 3000)
                assertEquals(1, restored.paymentHistory().count { it.kind == "order_collection" && it.method == "cash" })
                assertEquals(6, restored.stockLevels()["saf_roundtrip_tea"])
            } finally {
                restored.close()
            }
            instrumentation.runOnMainSync { activity.finish(); LocalSessionState.clear() }
        } finally {
            val uiAutomation = instrumentation.uiAutomation
            val activePackage = uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (activePackage == "com.google.android.documentsui") {
                uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(250)
            }
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish(); LocalSessionState.clear() }
        }
    }

    @Test fun cleanInstallRecoveryRestoresSyntheticBackupThroughSafUiWhenExplicitlyRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        // This destructive drill is opt-in. The normal suite must not count an
        // assumption violation as a failure or accidentally replace local test data.
        if (arguments.getString("cat051bCleanRestore") != "true") {
            android.util.Log.i("CAT051B", "Clean-install restore drill not run; explicit synthetic recovery arguments are required.")
            return
        }
        val expectedBackupName = arguments.getString("cat051bBackupName")
            ?: throw AssertionError("Pass the exact synthetic backup filename for the clean-install recovery drill")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LocalSessionState.clear()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_RECOVERY, true)
        val activity = instrumentation.startActivitySync(intent)
        val automation = instrumentation.uiAutomation
        fun visibleNames(): List<String> {
            val result = mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo) {
                node.text?.toString()?.let(result::add)
                node.contentDescription?.toString()?.let(result::add)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
            }
            automation.rootInActiveWindow?.let(::collect)
            return result
        }
        fun findNode(textPrefix: String? = null, hintPrefix: String? = null, descriptionPrefix: String? = null): AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                var found: AccessibilityNodeInfo? = null
                fun walk(node: AccessibilityNodeInfo) {
                    val text = node.text?.toString().orEmpty()
                    val hint = node.hintText?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    if (found == null && ((textPrefix != null && text.startsWith(textPrefix, true)) ||
                                (hintPrefix != null && hint.startsWith(hintPrefix, true)) ||
                                (descriptionPrefix != null && description.startsWith(descriptionPrefix, true)))) found = node
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                }
                automation.rootInActiveWindow?.let(::walk)
                found?.let { return it }
                Thread.sleep(100)
            }
            throw AssertionError("Recovery UI control missing: text=$textPrefix hint=$hintPrefix description=$descriptionPrefix; visible=${visibleNames().joinToString(" | ")}")
        }
        fun clickText(text: String) {
            val node = findNode(textPrefix = text)
            var parent: AccessibilityNodeInfo? = node
            var clicked = false
            while (parent != null && !clicked) {
                if (parent.isClickable) clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent = parent.parent
            }
            if (!clicked) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val x = bounds.exactCenterX()
                    val y = bounds.exactCenterY()
                    val downTime = android.os.SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0)
                    try {
                        automation.injectInputEvent(down, true)
                        clicked = automation.injectInputEvent(up, true)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                }
            }
            assertTrue("Could not select $text; visible=${visibleNames().joinToString(" | ")}", clicked)
        }
        fun setText(hint: String, value: String) {
            val field = findNode(hintPrefix = hint)
            assertTrue("Could not enter $hint", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        }

        try {
            clickText("Restore from backup")
            var pickerLabels = emptyList<String>()
            val pickerDeadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < pickerDeadline) {
                pickerLabels = visibleNames()
                if (pickerLabels.any { it.equals("Preview the file $expectedBackupName", true) }) break
                Thread.sleep(100)
            }
            if (pickerLabels.none { it.equals("Preview the file $expectedBackupName", true) }) {
                runCatching { findNode(descriptionPrefix = "Show roots").performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                clickText("Downloads")
                pickerLabels = visibleNames()
            }
            val backupRow = pickerLabels.firstOrNull { it.equals("Preview the file $expectedBackupName", true) }
                ?: throw AssertionError("Expected synthetic backup not shown by SAF: ${pickerLabels.joinToString(" | ")}")
            val backupName = backupRow.removePrefix("Preview the file ")
            clickText(backupName)
            val openDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < openDeadline && visibleNames().none { it.equals("Open", true) }) Thread.sleep(100)
            if (visibleNames().any { it.equals("Open", true) }) clickText("Open")

            val recoverySecret = "synthetic-saf-roundtrip-secret-2026"
            setText("Recovery secret (12+ characters)", recoverySecret)
            clickText("Continue")
            var labels = emptyList<String>()
            val verifiedDeadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < verifiedDeadline) {
                labels = visibleNames()
                if (labels.any { it.equals("Backup verified", true) } || labels.any { it.contains("Backup verification failed", true) }) break
                Thread.sleep(100)
            }
            assertTrue("Clean-install restore must authenticate the archive before replacement: ${labels.joinToString(" | ")}", labels.any { it.equals("Backup verified", true) })
            clickText("Continue")
            clickText("Replace and restore")
            val finishedDeadline = System.currentTimeMillis() + 90_000
            var finished = false
            while (System.currentTimeMillis() < finishedDeadline) {
                instrumentation.runOnMainSync { finished = activity.isFinishing }
                if (finished) break
                Thread.sleep(100)
            }
            assertTrue("Restore should finish and hand off to local access", finished)

            val restored = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            try {
                val order = restored.allOrders().single()
                assertTrue("Clean-install restore should retain the synthetic product", order.lines.any { it.productId == "saf_roundtrip_tea" })
                assertEquals(3000, order.paidPaise)
                assertEquals(1, restored.paymentHistory().count { it.kind == "order_collection" && it.method == "cash" })
                assertEquals(6, restored.stockLevels()["saf_roundtrip_tea"])
                val localKey = SecureDatabaseKeyManager(context, context.getDatabasePath(SqliteCounterPersistence.DATABASE_NAME)).getOrCreatePassphrase()
                assertTrue("Fresh install should have usable device-local database key material", localKey.isNotEmpty())
                localKey.fill(0)
            } finally {
                restored.close()
            }
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish(); LocalSessionState.clear() }
        }
    }

    @Test fun voiceSettingsPersistsLanguageAndAliasesWithoutExecutingBusinessCommands() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val setup = CounterModel(SqliteCounterPersistence(context))
        assertTrue(setup.saveProduct(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1000, true, true, true, listOf("cutting"))).startsWith("Product saved"))
        assertTrue(setup.saveVoiceLanguageTag("mr-IN").startsWith("Voice language saved"))
        setup.close()

        val activity = instrumentation.startActivitySync(Intent(context, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun strings(): List<String> {
            val collected = mutableListOf<String>()
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is TextView) collected += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.findViewById(android.R.id.content))
            }
            return collected
        }
        repeat(30) { if (strings().any { it == "Save aliases" }) return@repeat; Thread.sleep(100) }
        assertTrue(strings().contains("Voice Settings · CT-31"))
        assertTrue(strings().any { it.contains("continuous Bluetooth listening is not implemented") })
        val widgets = mutableListOf<android.view.View>()
        instrumentation.runOnMainSync {
            fun collect(view: View) {
                if (view is Spinner || view is EditText || view is Button) widgets += view
                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(activity.findViewById(android.R.id.content))
        }
        val spinners = widgets.filterIsInstance<Spinner>()
        val aliasEditor = widgets.filterIsInstance<EditText>().single()
        assertEquals(2, spinners.size)
        assertEquals(1, spinners[0].selectedItemPosition)
        instrumentation.runOnMainSync {
            val cuttingChaiPosition = (0 until spinners[1].count).single { spinners[1].getItemAtPosition(it).toString().contains("(cutting_chai)") }
            spinners[1].setSelection(cuttingChaiPosition)
            aliasEditor.setText("cutting\ncut chai")
            widgets.filterIsInstance<Button>().single { it.text.toString() == "Save aliases" }.performClick()
        }
        repeat(30) { if (strings().any { it.startsWith("Product saved") }) return@repeat; Thread.sleep(100) }
        assertTrue(strings().any { it.startsWith("Product saved") })
        instrumentation.runOnMainSync {
            spinners[0].setSelection(2)
            widgets.filterIsInstance<Button>().single { it.text.toString() == "Save language" }.performClick()
        }
        repeat(30) { if (strings().any { it.startsWith("Voice language saved") }) return@repeat; Thread.sleep(100) }
        assertTrue(strings().any { it.startsWith("Voice language saved") })

        val verify = CounterModel(SqliteCounterPersistence(context))
        assertEquals("en-IN", verify.voiceLanguageTag())
        assertEquals(listOf("cutting", "cut chai"), verify.products().single { it.productId == "cutting_chai" }.aliases)
        assertTrue(verify.auditEvents().any { it.contains("voice.language_updated language=en-IN") })
        assertTrue(verify.allOrders().isEmpty())
        verify.close()
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun shopProfileSettingsEditPersistsOwnerChangesWithoutFinancialSideEffects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic test owner")
        CounterModel(SqliteCounterPersistence(context)).use { model ->
            assertTrue(model.saveShopProfile(ShopProfile("Old Tea Corner", "chai_tapri", "hi-IN", "INR")).startsWith("Shop setup saved"))
        }

        val activity = instrumentation.startActivitySync(Intent(context, ShopSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun widgets(): List<View> {
            val found = mutableListOf<View>()
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is EditText || view is Spinner || view is Button || view is TextView) found += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(activity.findViewById(android.R.id.content))
            }
            return found
        }
        repeat(60) { if (widgets().filterIsInstance<Button>().any { it.text.toString() == "Save shop profile" }) return@repeat; Thread.sleep(100) }
        val initial = widgets()
        assertTrue(initial.filterIsInstance<TextView>().any { it.text.toString().contains("Shop Profile & Settings · CT-32") })
        val name = initial.filterIsInstance<EditText>().single()
        val selectors = initial.filterIsInstance<Spinner>()
        assertEquals(2, selectors.size)
        instrumentation.runOnMainSync {
            name.setText("Updated Tea Corner")
            selectors[0].setSelection(3)
            selectors[1].setSelection(2)
            initial.filterIsInstance<Button>().single { it.text.toString() == "Save shop profile" }.performClick()
        }
        var saved = false
        repeat(60) {
            saved = widgets().filterIsInstance<TextView>().any { it.text.toString().startsWith("Shop setup saved") }
            if (!saved) Thread.sleep(100)
        }
        assertTrue("Owner profile save should finish with success feedback", saved)
        CounterModel(SqliteCounterPersistence(context)).use { model ->
            assertEquals(ShopProfile("Updated Tea Corner", "cafe", "en-IN", "INR"), model.shopProfile())
            assertTrue(model.auditRecords().any { it.event.contains("shop.profile_updated") })
            assertTrue("Editing shop configuration must not create an order", model.allOrders().isEmpty())
            assertTrue("Editing shop configuration must not create payments", model.paymentHistory().isEmpty())
            assertTrue("Editing shop configuration must not create stock movements", model.stockMovements().isEmpty())
        }
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun businessAlertsUseLocalRecordsAndDiscloseUnavailableAlertClasses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_pack", "Tea Pack", "Pantry", 500, true, false, true, reorderThreshold = 3)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("tea_pack", 2, "synthetic low-stock alert", "alerts-stock-count").contains("2 units"))
        model.close()
        LocalSessionState.establish("Synthetic Owner")
        val activity = instrumentation.startActivitySync(Intent(context, BusinessAlertsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        repeat(30) {
            strings.clear()
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is TextView) strings += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.findViewById(android.R.id.content))
            }
            if (strings.any { it.contains("Low-stock alerts use only owner-configured thresholds") }) return@repeat
            Thread.sleep(100)
        }
        assertTrue(strings.any { it == "Business Alerts · CT-30" })
        assertTrue(strings.any { it.contains("Low stock") && it.contains("Tea Pack") })
        assertTrue(strings.any { it.contains("2 units on hand") && it.contains("alert threshold 3 units") })
        assertTrue(strings.any { it.contains("Low-stock alerts use only owner-configured thresholds") })
        assertTrue(strings.any { it.contains("Overdue-credit and sync alerts are unavailable") })
        instrumentation.runOnMainSync { activity.finish() }
        LocalSessionState.clear()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun dailyReportRendersOnlyPersistedLifetimeLedgerTotalsAndDisclaimsProfit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        assertTrue(model.saveProduct(TapriProduct("chai", "Regular Chai", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 10, "synthetic report fixture", "report-opening-stock").startsWith("Opening stock confirmed"))
        assertTrue(model.quickSale(listOf("chai" to 1), "cash", commandId = "report-synthetic-sale").startsWith("Quick sale"))
        assertTrue(model.recordExpense("supplies", 500, "cash", "synthetic report expense", "report-synthetic-expense").startsWith("Expense recorded"))
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, DailyReportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        repeat(20) {
            strings.clear()
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is TextView) strings += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.findViewById(android.R.id.content))
            }
            if (strings.any { it.contains("Profit is unavailable") }) return@repeat
            Thread.sleep(100)
        }
        assertTrue(strings.any { it.contains("Scope: all records on this device") })
        assertTrue(strings.any { it.contains("Recorded sales, net of refunds") })
        assertTrue(strings.any { it == "₹15.00" })
        assertTrue(strings.any { it == "₹5.00" })
        assertTrue(strings.any { it.contains("Profit is unavailable") })
        assertTrue(strings.any { it == "Export current report as CSV" })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun purchaseReceivingScreenShowsPersistedStockAndSupplierPayable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.saveSupplier("dealer_1", "Local Tea Dealer", "dealer-create").startsWith("Supplier saved"))
        assertTrue(model.confirmOpeningStock("tea_packet", 0, "count before receipt", "dealer-opening-count").contains("0 units"))
        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "dealer-receipt", supplierInvoiceReference = "DEALER-INV-01").contains("Received 1 product lines"))
        assertTrue(model.recordSupplierCashPayment("dealer_1", 1000, "dealer-cash-payment").startsWith("Staff-declared cash payment"))
        assertTrue(model.returnPurchase("dealer_1", "dealer-receipt", mapOf("tea_packet" to 1), "damaged sealed pack", "dealer-return").contains("Purchase return recorded"))
        assertTrue(model.createPurchaseOrderDraft("dealer_1", mapOf("tea_packet" to 4), mapOf("tea_packet" to 500), "dealer-order-ui", "dealer-order-ui-create").contains("no stock or payable"))
        assertEquals(9, model.stockLevels()["tea_packet"])
        assertEquals(3500L, model.supplierPayablePaise("dealer_1"))
        assertTrue(model.markPurchaseOrderSent("dealer-order-ui", 0, "dealer-order-ui-sent").contains("marked sent"))
        assertTrue(model.receivePurchaseOrder("dealer-order-ui", mapOf("tea_packet" to 2), "dealer-order-ui-partial", "PO-INV-02").startsWith("Received"))
        assertEquals(11, model.stockLevels()["tea_packet"])
        assertEquals(4500L, model.supplierPayablePaise("dealer_1"))
        assertTrue(model.recordSupplierPayment("dealer_1", 500, "upi", "dealer-upi-payment").startsWith("Staff-declared UPI payment"))
        assertEquals(4000L, model.supplierPayablePaise("dealer_1"))
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, ProcurementActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.contains("Local Tea Dealer"))
        assertTrue(strings.any { it.contains("payable") && it.contains("40.00") })
        assertTrue(strings.any { it.contains("Tea Packet") && it.contains("₹5.00") })
        assertTrue(strings.any { it.contains("staff_declared") && it.contains("₹10.00") })
        assertTrue(strings.any { it.contains("Purchase returns") })
        assertTrue(strings.any { it.contains("upi") && it.contains("staff_declared") && it.contains("5.00") })
        assertTrue(strings.any { it.contains("payable reduction") && it.contains("5.00") })
        assertTrue(strings.any { it.contains("dealer-order-ui") && it.contains("PARTIALLY RECEIVED") })
        assertTrue(strings.any { it.contains("2/4 received") })
        assertTrue(strings.any { it.contains("order dealer-order-ui") })
        assertTrue(strings.any { it.contains("supplier invoice DEALER-INV-01") })
        assertTrue(strings.any { it.contains("supplier invoice PO-INV-02") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun procurementPaymentDialogRecordsUpiAsStaffDeclaredWithoutChangingCashDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("supplier_ui_tea", "Supplier UI Tea", "Tea", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(seed.saveSupplier("supplier_ui_dealer", "Supplier UI Dealer", "supplier-ui-add").startsWith("Supplier saved"))
        assertTrue(seed.confirmOpeningStock("supplier_ui_tea", 0, "supplier UI count", "supplier-ui-count").contains("0 units"))
        assertTrue(seed.receivePurchase("supplier_ui_dealer", mapOf("supplier_ui_tea" to 2), mapOf("supplier_ui_tea" to 500), "supplier-ui-receipt").startsWith("Received"))
        assertTrue(seed.openCashDrawer(10000, "supplier-ui-drawer").startsWith("Cash drawer opened"))
        val drawerId = seed.activeCashDrawer()!!.sessionId
        seed.close()

        val activity = instrumentation.startActivitySync(Intent(context, ProcurementActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                var lastTree = "<no active window>"
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    if (root != null) {
                        val labels = mutableListOf<String>()
                        fun visit(node: AccessibilityNodeInfo) {
                            node.text?.toString()?.let(labels::add)
                            node.hintText?.toString()?.let(labels::add)
                            val matches = when {
                                prefix != null -> node.text?.toString()?.startsWith(prefix, true) == true
                                hint != null -> node.hintText?.toString()?.equals(hint, true) == true
                                else -> node.className?.toString() == className
                            }
                            if (selected == null && matches) selected = node
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                        }
                        visit(root)
                        lastTree = labels.joinToString(" | ")
                    }
                    selected?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Supplier payment UI node not found: prefix=$prefix hint=$hint class=$className; UI=$lastTree")
            }
            fun click(prefix: String) { assertTrue("Could not activate $prefix", awaitNode(prefix = prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            fun enter(hint: String, value: String) {
                assertTrue(awaitNode(hint = hint, className = EditText::class.java.name).performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                ))
            }

            click("Record supplier payment")
            enter("Existing supplier ID", "supplier_ui_dealer")
            enter("Cash amount in rupees", "5.00")
            assertTrue(awaitNode(className = Spinner::class.java.name).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click("UPI")
            click("Record payment")

            var reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val payment = reopened.supplierPaymentHistory().single()
            assertEquals("upi", payment.method)
            assertEquals("staff_declared", payment.status)
            assertEquals(null, payment.drawerSessionId)
            assertEquals(500L, reopened.supplierPayablePaise("supplier_ui_dealer"))
            assertEquals(10000L, reopened.expectedCashPaise(drawerId))
            assertTrue(SqliteCounterPersistence(context).use { auditStore ->
                auditStore.loadAuditRecords().any { it.event.contains("supplier.payment_recorded") && it.event.contains("method=upi status=staff_declared") }
            })
            CounterSnapshotIntegrity.requireConsistent(SqliteCounterPersistence(context).use { it.load()!! })
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Thread.sleep(200)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun supplierDirectorySearchOpensIsolatedSupplierLedgerDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("supplier_detail_tea", "Supplier Detail Tea", "Tea", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(seed.saveSupplier("alpha_dealer", "Alpha Dealer", "supplier-detail-alpha").startsWith("Supplier saved"))
        assertTrue(seed.saveSupplier("beta_dealer", "Beta Dealer", "supplier-detail-beta").startsWith("Supplier saved"))
        assertTrue(seed.confirmOpeningStock("supplier_detail_tea", 0, "synthetic supplier detail baseline", "supplier-detail-count").contains("0 units"))
        assertTrue(seed.receivePurchase("alpha_dealer", mapOf("supplier_detail_tea" to 2), mapOf("supplier_detail_tea" to 500), "supplier-detail-alpha-receipt", supplierInvoiceReference = "ALPHA-INV-1").startsWith("Received"))
        assertTrue(seed.recordSupplierPayment("alpha_dealer", 300, "cash", "supplier-detail-alpha-payment").startsWith("Staff-declared cash payment"))
        assertTrue(seed.receivePurchase("beta_dealer", mapOf("supplier_detail_tea" to 1), mapOf("supplier_detail_tea" to 900), "supplier-detail-beta-receipt").startsWith("Received"))
        seed.close()

        var directory: ProcurementActivity? = null
        var details: SupplierDetailsActivity? = null
        val detailsMonitor = android.app.Instrumentation.ActivityMonitor(SupplierDetailsActivity::class.java.name, null, false)
        try {
            instrumentation.addMonitor(detailsMonitor)
            directory = instrumentation.startActivitySync(Intent(context, ProcurementActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProcurementActivity
            fun awaitNode(prefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                var lastTree = "<no active window>"
                repeat(100) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    var selected: AccessibilityNodeInfo? = null
                    if (root != null) {
                        val labels = mutableListOf<String>()
                        fun visit(node: AccessibilityNodeInfo) {
                            node.text?.toString()?.let(labels::add)
                            node.hintText?.toString()?.let(labels::add)
                            val matches = when {
                                prefix != null -> node.text?.toString()?.startsWith(prefix, true) == true
                                hint != null -> node.hintText?.toString()?.equals(hint, true) == true
                                else -> node.className?.toString() == className
                            }
                            if (selected == null && matches) selected = node
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                        }
                        visit(root)
                        lastTree = labels.joinToString(" | ")
                    }
                    selected?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Supplier UI node not found: prefix=$prefix hint=$hint class=$className; UI=$lastTree")
            }
            assertTrue(awaitNode(hint = "Search suppliers by name or ID", className = EditText::class.java.name).performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Alpha") }
            ))
            assertTrue(awaitNode(prefix = "Alpha Dealer").text.toString().contains("Alpha Dealer"))
            val directoryActions = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("View supplier details")
            assertEquals("Search should leave one supplier card in the directory", 1, directoryActions.size)
            assertTrue(awaitNode(prefix = "View supplier details").performAction(AccessibilityNodeInfo.ACTION_CLICK))

            details = detailsMonitor.waitForActivityWithTimeout(5_000) as? SupplierDetailsActivity
            assertNotNull("Supplier details should open from the filtered directory", details)
            val strings = mutableListOf<String>()
            fun collect(view: View) {
                if (view is TextView) strings += view.text.toString()
                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(details!!.findViewById(android.R.id.content))
            assertTrue(strings.contains("Alpha Dealer"))
            assertTrue(strings.contains("Supplier ID: alpha_dealer"))
            assertTrue(strings.contains("Current payable: ₹7.00"))
            assertTrue(strings.any { it.contains("ALPHA-INV-1") && it.contains("Supplier Detail Tea") })
            assertTrue(strings.any { it.contains("Staff-declared cash") && it.contains("₹3.00") })
            assertFalse("A supplier profile must not expose another supplier's records", strings.any { it.contains("Beta Dealer") || it.contains("beta_dealer") })

            assertTrue(awaitNode(prefix = "Edit supplier profile").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(awaitNode(hint = "Supplier / dealer name", className = EditText::class.java.name).performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Alpha Dealer Updated") }
            ))
            assertTrue(awaitNode(hint = "Business phone (optional)", className = EditText::class.java.name).performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "+91 98765 43210") }
            ))
            assertTrue(awaitNode(prefix = "Save").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(awaitNode(prefix = "Alpha Dealer Updated").text.toString().contains("Alpha Dealer Updated"))
            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            assertEquals("Alpha Dealer Updated", reopened.suppliers().single { it.supplierId == "alpha_dealer" }.name)
            assertEquals("+91 98765 43210", reopened.suppliers().single { it.supplierId == "alpha_dealer" }.phone)
            assertEquals(700L, reopened.supplierPayablePaise("alpha_dealer"))
            assertTrue(reopened.auditEvents().any { it.contains("supplier.saved") && it.contains("contact=set") && !it.contains("98765") })
            reopened.close()
        } finally {
            instrumentation.removeMonitor(detailsMonitor)
            instrumentation.runOnMainSync { details?.finish(); directory?.finish() }
            Thread.sleep(200)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun schemaV8ToV16CreatesOwnerCredentialTableAndOrderDatesAndPreservesExistingOrders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        assertTrue(model.saveProduct(TapriProduct("chai", "Regular Chai", "Chai", 1500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 5, "synthetic migration fixture", "v8-opening-stock").contains("5 units"))
        val saleResult = model.quickSale(listOf("chai" to 1), "cash", commandId = "v8-existing-sale")
        assertTrue(saleResult, saleResult.startsWith("Quick sale"))
        model.close()

        val legacy = SqliteCounterPersistence(context)
        val database = legacy.writableDatabase
        database.execSQL("DROP TABLE supplier_payments")
        database.execSQL("DROP TABLE purchase_receipt_lines")
        database.execSQL("DROP TABLE purchase_receipts")
        database.execSQL("DROP TABLE suppliers")
        database.execSQL("DROP TABLE purchase_return_lines")
        database.execSQL("DROP TABLE purchase_returns")
        database.execSQL("DROP TABLE expenses")
        database.execSQL("DROP TABLE cash_drawer_movements")
        database.execSQL("DROP TABLE cash_drawer_sessions")
        database.execSQL("DROP TABLE cash_closing_reviews")
        database.execSQL("CREATE TABLE payment_entries_v8(entry_id TEXT PRIMARY KEY,order_no INTEGER,customer_id TEXT,amount_paise INTEGER NOT NULL,method TEXT NOT NULL,status TEXT NOT NULL,kind TEXT NOT NULL,related_entry_id TEXT,created_at INTEGER NOT NULL)")
        database.execSQL("INSERT INTO payment_entries_v8 SELECT entry_id,order_no,customer_id,amount_paise,method,status,kind,related_entry_id,created_at FROM payment_entries")
        database.execSQL("DROP TABLE payment_entries")
        database.execSQL("ALTER TABLE payment_entries_v8 RENAME TO payment_entries")
        database.execSQL("ALTER TABLE orders DROP COLUMN created_at")
        database.version = 8
        legacy.close()

        val upgraded = SqliteCounterPersistence(context)
        val restored = CounterModel(upgraded)
        assertEquals(1, restored.allOrders().size)
        assertEquals(1500, restored.allOrders().single().salePaise)
        assertEquals(0L, restored.allOrders().single().createdAtEpochMs)
        assertTrue(restored.suppliers().isEmpty())
        assertEquals(SqliteCounterPersistence.DATABASE_VERSION, upgraded.readableDatabase.version)
        assertTrue(upgraded.readableDatabase.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='local_owner_credentials'", null).use { it.moveToFirst() })
        restored.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun staffShiftCashHandoverAndSessionSummaryPersistAcrossEncryptedReopenAndRender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        var model = CounterModel(SqliteCounterPersistence(context))
        assertTrue(model.confirmOpeningStock("chai", 10, "synthetic shift test stock", "shift-stock-ui").contains("10 units"))
        assertTrue(model.startStaffShift("Morning operator", 5_000, "shift-open-ui").contains("Shift started"))
        assertTrue(model.quickSale(listOf("chai" to 1), "cash", commandId = "shift-sale-ui").startsWith("Quick sale"))
        assertTrue(model.endStaffShift(6_500, "Evening operator", "Till counted together", "shift-end-ui").contains("matched"))
        model.close()
        model = CounterModel(SqliteCounterPersistence(context))
        val shift = model.cashDrawerHistory().single()
        assertEquals("Morning operator", shift.staffLabel)
        assertEquals("Evening operator", shift.handoverToLabel)
        assertEquals("Till counted together", shift.handoverNote)
        assertEquals(6_500L, model.expectedCashPaise(shift.sessionId))
        assertTrue(model.auditEvents().any { it.contains("staff_shift.started") && it.contains("Morning operator") })
        assertTrue(model.auditEvents().any { it.contains("staff_shift.ended") && it.contains("Evening operator") })
        model.close()

        val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context, StaffShiftsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) { if (view is TextView) strings += view.text.toString(); if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i)) }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.any { it.contains("Morning operator") })
        assertTrue(strings.any { it.contains("Evening operator") })
        assertTrue(strings.any { it.contains("variance") && it.contains("65.00") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun expenseRecordIsAuditedAndPersistsAcrossEncryptedDatabaseReopenAndScreenLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        val beforeRecord = System.currentTimeMillis()
        assertTrue(model.recordExpense("electricity", 3450, "cash", "synthetic monthly bill", "expense-ui").startsWith("Expense recorded"))
        val afterRecord = System.currentTimeMillis()
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context))
        assertEquals(3450L, reopened.totalExpensesPaise())
        assertEquals("staff_declared", reopened.expenseHistory().single().status)
        val recordedAt = reopened.expenseHistory().single().recordedAtEpochMs
        assertTrue(recordedAt != null && recordedAt in beforeRecord..afterRecord)
        assertEquals(3450L, ExpenseHistorySummary.daily(reopened.expenseHistory(), java.time.ZoneId.systemDefault()).single().totalPaise)
        assertEquals("Duplicate command ignored.", reopened.recordExpense("electricity", 3450, "cash", "synthetic monthly bill", "expense-ui"))
        assertTrue(reopened.auditEvents().any { it.contains("expense.recorded category=electricity") })
        reopened.close()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, ExpensesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.any { it.contains("total:") && it.contains("34.50") })
        assertTrue(strings.any { it.contains("Daily totals") && it.contains("device-local") })
        assertTrue(strings.any { it.contains(java.time.Instant.ofEpochMilli(recordedAt!!).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) && it.contains("34.50") })
        assertTrue(strings.any { it.contains("electricity") && it.contains("staff_declared") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun cashDrawerSessionAndReconciliationPersistAndRenderOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("drawer_chai", "Drawer Chai", "Chai", 1500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("drawer_chai", 5, "synthetic drawer test", "drawer-stock").contains("5 units"))
        assertTrue(model.openCashDrawer(5_000, "drawer-ui-open").contains("opened"))
        val sale = model.quickSale(listOf("drawer_chai" to 1), "cash", commandId = "drawer-ui-sale")
        assertTrue(sale, sale.startsWith("Quick sale"))
        assertTrue(model.recordExpense("cleaning", 200, "cash", "soap", "drawer-ui-expense").startsWith("Expense recorded"))
        assertEquals(6_300L, model.expectedCashPaise("drawer-ui-open"))
        assertTrue(model.closeCashDrawer(6_000, "drawer-ui-close").contains("unresolved variance"))
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context))
        assertNull(reopened.activeCashDrawer())
        assertEquals(6_300L, reopened.expectedCashPaise("drawer-ui-open"))
        assertEquals(6_000, reopened.cashDrawerHistory().single().closingCashPaise)
        assertTrue(reopened.auditEvents().any { it.contains("cash_drawer.closed") })
        reopened.close()

        val activity = instrumentation.startActivitySync(Intent(context, CashRegisterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val strings = mutableListOf<String>()
        fun collect(view: View) { if (view is TextView) strings += view.text.toString(); if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index)) }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.contains("Cash Register | CT-26"))
        assertTrue(strings.any { it.contains("expected ₹63.00") && it.contains("counted ₹60.00") && it.contains("variance -₹3.00") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun dailyClosingReviewShowsSessionTotalsAndKeepsCurrentExceptionsSeparate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("closing_chai", "Closing Chai", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("closing_chai", 5, "synthetic closing check", "closing-stock").contains("5 units"))
        assertTrue(model.openCashDrawer(5_000, "closing-open").contains("opened"))
        assertTrue(model.quickSale(listOf("closing_chai" to 1), "cash", commandId = "closing-cash-sale").startsWith("Quick sale"))
        assertTrue(model.recordExpense("cleaning", 200, "cash", "soap", "closing-expense").startsWith("Expense recorded"))
        assertTrue(model.quickSale(listOf("closing_chai" to 1), "upi_pending", commandId = "closing-upi").startsWith("Quick sale"))
        assertTrue(model.apply("closing-open-order", ProposedCommand("create", null, "draft chai", "closing_chai", 1)).contains("Order #"))
        assertTrue(model.closeCashDrawer(6_000, "closing-close").contains("unresolved variance"))
        assertTrue(model.recordCashClosingReview("closing-open", "recount float", "closing-review").contains("review recorded"))
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals("recount float", reopened.closingReview("closing-open")?.note)
        val intent = Intent(context, DailyClosingActivity::class.java).putExtra(DailyClosingActivity.EXTRA_SESSION_ID, "closing-open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val strings = mutableListOf<String>()
        fun collect(view: View) { if (view is TextView) strings += view.text.toString(); if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index)) }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.contains("Daily Closing | CT-28"))
        assertTrue(strings.any { it.contains("Cash sales recorded: ₹15.00") })
        assertTrue(strings.any { it.contains("Cash operating expenses: ₹2.00") })
        assertTrue(strings.any { it.contains("Expected cash: ₹63.00") })
        assertTrue(strings.any { it.contains("Physical count: ₹60.00") })
        assertTrue(strings.any { it.contains("Cash difference: -₹3.00 · unresolved") })
        assertTrue(strings.any { it.contains("Open orders: 1") })
        assertTrue(strings.any { it.contains("UPI pending: ₹15.00") })
        assertTrue(strings.any { it.contains("Review status: acknowledged") })
        instrumentation.runOnMainSync { activity.finish() }
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun supplierReturnStockPayableAndAuditSurviveEncryptedDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.saveSupplier("dealer_1", "Local Tea Dealer", "return-supplier").startsWith("Supplier saved"))
        assertTrue(model.confirmOpeningStock("tea_packet", 0, "count before receipt", "return-opening").contains("0 units"))
        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "return-receipt").contains("Received 1 product lines"))
        assertTrue(model.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 1), "damaged sealed pack", "return-event").contains("Purchase return recorded"))
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(9, reopened.stockLevels()["tea_packet"])
        assertEquals(4500L, reopened.supplierPayablePaise("dealer_1"))
        assertEquals(1, reopened.purchaseReturnHistory().size)
        assertEquals(-1, reopened.stockMovements().single { it.reason == "purchase_return" }.quantityDelta)
        assertTrue(reopened.auditEvents().any { it.contains("purchase.returned") })
        assertEquals("Duplicate command ignored.", reopened.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 1), "damaged sealed pack", "return-event"))
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun schemaV21AddsSupplierInvoiceReferenceWithoutChangingExistingReceiptFinancials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("invoice_migration_tea", "Invoice Migration Tea", "Pantry", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("invoice_migration_tea", 2, "schema v21 fixture", "invoice-v21-opening").contains("2 units"))
        assertTrue(model.saveSupplier("invoice_migration_dealer", "Invoice Migration Dealer", "invoice-v21-supplier").startsWith("Supplier saved"))
        assertTrue(model.receivePurchase("invoice_migration_dealer", mapOf("invoice_migration_tea" to 1), mapOf("invoice_migration_tea" to 500), "invoice-v21-receipt").startsWith("Received"))
        model.close()

        val legacy = SqliteCounterPersistence(context)
        legacy.writableDatabase.execSQL("ALTER TABLE purchase_receipts DROP COLUMN supplier_invoice_reference")
        legacy.writableDatabase.version = 21
        legacy.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence.DATABASE_VERSION)
        assertEquals(1, model.purchaseHistory().size)
        assertEquals(null, model.purchaseHistory().single().supplierInvoiceReference)
        assertEquals(3, model.stockLevels()["invoice_migration_tea"])
        assertEquals(500L, model.supplierPayablePaise("invoice_migration_dealer"))
        assertTrue(model.receivePurchase("invoice_migration_dealer", mapOf("invoice_migration_tea" to 1), mapOf("invoice_migration_tea" to 500), "invoice-v22-receipt", supplierInvoiceReference = "TAX-INV-22").startsWith("Received"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals("TAX-INV-22", model.purchaseHistory().single { it.receiptId == "invoice-v22-receipt" }.supplierInvoiceReference)
        assertEquals(4, model.stockLevels()["invoice_migration_tea"])
        assertEquals(1000L, model.supplierPayablePaise("invoice_migration_dealer"))
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun schemaV20PurchaseOrderMigrationPreservesReceiptsAndSupportsPartialDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_order_migration", "Tea Order Migration", "Pantry", 1500, true, true, true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("tea_order_migration", 3, "schema v20 purchase order fixture", "po-v20-opening").contains("3 units"))
        assertTrue(model.saveSupplier("po_migration_supplier", "Migration Tea Dealer", "po-v20-supplier").startsWith("Supplier saved"))
        assertTrue(model.receivePurchase("po_migration_supplier", mapOf("tea_order_migration" to 1), mapOf("tea_order_migration" to 500), "po-v20-existing-receipt").startsWith("Received"))
        model.close()

        val simulatedV20 = SqliteCounterPersistence(context)
        val oldDatabase = simulatedV20.writableDatabase
        oldDatabase.execSQL("DROP TABLE purchase_order_draft_lines")
        oldDatabase.execSQL("DROP TABLE purchase_order_drafts")
        oldDatabase.execSQL("ALTER TABLE purchase_receipts DROP COLUMN purchase_order_id")
        oldDatabase.version = 20
        simulatedV20.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence.DATABASE_VERSION)
        assertEquals(4, model.stockLevels()["tea_order_migration"])
        assertEquals(500L, model.supplierPayablePaise("po_migration_supplier"))
        assertEquals(1, model.purchaseHistory().size)
        assertTrue(model.createPurchaseOrderDraft("po_migration_supplier", mapOf("tea_order_migration" to 5), mapOf("tea_order_migration" to 1000), "po-v21-order", "po-v21-create").contains("draft"))
        assertEquals(4, model.stockLevels()["tea_order_migration"])
        assertEquals(500L, model.supplierPayablePaise("po_migration_supplier"))
        assertTrue(model.markPurchaseOrderSent("po-v21-order", 0, "po-v21-send").contains("marked sent"))
        assertTrue(model.receivePurchaseOrder("po-v21-order", mapOf("tea_order_migration" to 2), "po-v21-partial").startsWith("Received"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val restoredOrder = model.purchaseOrderDrafts().single()
        assertEquals("PARTIALLY RECEIVED", model.purchaseOrderState(restoredOrder))
        assertEquals(6, model.stockLevels()["tea_order_migration"])
        assertEquals(2500L, model.supplierPayablePaise("po_migration_supplier"))
        assertTrue(model.receivePurchaseOrder("po-v21-order", mapOf("tea_order_migration" to 3), "po-v21-final").startsWith("Received"))
        assertEquals("RECEIVED", model.purchaseOrderState(model.purchaseOrderDrafts().single()))
        assertEquals(9, model.stockLevels()["tea_order_migration"])
        assertEquals(5500L, model.supplierPayablePaise("po_migration_supplier"))
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun schemaV22SupplierContactMigrationPreservesLedgerAndPersistsOptionalPhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("supplier_contact_tea", "Supplier Contact Tea", "Pantry", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("supplier_contact_tea", 0, "supplier contact migration", "supplier-contact-stock").contains("0 units"))
        assertTrue(model.saveSupplier("supplier_contact_dealer", "Supplier Contact Dealer", "supplier-contact-create").startsWith("Supplier saved"))
        assertTrue(model.receivePurchase("supplier_contact_dealer", mapOf("supplier_contact_tea" to 2), mapOf("supplier_contact_tea" to 500), "supplier-contact-receipt").startsWith("Received"))
        model.close()

        val schema22 = SqliteCounterPersistence(context)
        schema22.writableDatabase.execSQL("ALTER TABLE suppliers DROP COLUMN phone")
        schema22.writableDatabase.version = 22
        schema22.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(null, model.suppliers().single().phone)
        assertEquals(2, model.stockLevels()["supplier_contact_tea"])
        assertEquals(1000L, model.supplierPayablePaise("supplier_contact_dealer"))
        assertTrue(model.saveSupplier("supplier_contact_dealer", "Supplier Contact Dealer", "supplier-contact-update", "+91 98765-43210").startsWith("Supplier saved"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals("+91 98765-43210", model.suppliers().single().phone)
        assertEquals(1, model.purchaseHistory().size)
        assertEquals(2, model.stockLevels()["supplier_contact_tea"])
        assertEquals(1000L, model.supplierPayablePaise("supplier_contact_dealer"))
        assertTrue(model.auditEvents().any { it.contains("supplier.saved") && it.contains("contact=set") && !it.contains("98765") })
        assertTrue(model.saveSupplier("supplier_contact_dealer", "Supplier Contact Dealer", "supplier-contact-invalid", "12").contains("valid supplier phone"))
        assertTrue(model.saveSupplier("supplier_contact_dealer", "Supplier Contact Dealer", "supplier-contact-clear", "").startsWith("Supplier saved"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(null, model.suppliers().single().phone)
        assertEquals(1000L, model.supplierPayablePaise("supplier_contact_dealer"))
        assertTrue(model.auditEvents().any { it.contains("supplier-contact-clear") && it.contains("contact=none") })
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun customerCreditScreenShowsPersistedBalanceAndRepaymentEvents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("credit_tea", "Credit Tea", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("credit_tea", 5, "synthetic credit test count", "credit-stock").contains("5 units"))
        assertTrue(model.quickSale(listOf("credit_tea" to 2), "udhar", "customer-demo", "credit-sale").startsWith("Quick sale"))
        model.close()
        val repaymentModel = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(repaymentModel.repayCredit("customer-demo", 1000, "credit-repayment").startsWith("Repayment"))
        repaymentModel.close()

        val activity = instrumentation.startActivitySync(Intent(context, CustomerCreditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val strings = mutableListOf<String>()
            fun collect(view: View) {
                if (view is TextView) strings += view.text.toString()
                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(activity.findViewById(android.R.id.content))
            assertTrue(strings.contains("customer-demo"))
            assertTrue(strings.any { it.contains("Outstanding credit:") && it.contains("20.00") })
            assertTrue(strings.any { it.contains("Oldest open credit:") && it.contains("since order (device clock)") && it.contains("No due date is recorded") })
            assertTrue(strings.any { it.contains("customer_repayment") && it.contains("10.00") && it.contains("staff_declared") })

            val automation = instrumentation.uiAutomation
            fun findNode(textPrefix: String? = null, hintPrefix: String? = null): AccessibilityNodeInfo {
                repeat(100) {
                    var found: AccessibilityNodeInfo? = null
                    fun walk(node: AccessibilityNodeInfo) {
                        val text = node.text?.toString().orEmpty()
                        val hint = node.hintText?.toString().orEmpty()
                        if (found == null && ((textPrefix != null && text.startsWith(textPrefix, ignoreCase = true)) ||
                                (hintPrefix != null && hint.startsWith(hintPrefix, ignoreCase = true)))) found = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                    }
                    val root = automation.rootInActiveWindow
                    root?.let(::walk)
                    found?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Customer repayment UI element not found: text=$textPrefix hint=$hintPrefix")
            }
            assertTrue(findNode(textPrefix = "Record cash repayment").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val amountField = findNode(hintPrefix = "Cash amount in rupees")
            assertTrue("Repayment amount editor should accept focus", amountField.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue(amountField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "5.00")
            }))
            assertEquals("5.00", findNode(textPrefix = "5.00").text?.toString())
            assertTrue(findNode(textPrefix = "Record repayment").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            strings.clear()
            instrumentation.runOnMainSync { collect(activity.findViewById(android.R.id.content)) }
            assertTrue("The UI should confirm a repayment result; visible text=${strings.joinToString(" | ")}", strings.any { it.contains("Repayment") && it.contains("5.00 allocated") })

            val afterUiRepayment = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            assertEquals(1500, afterUiRepayment.creditBalance("customer-demo"))
            val repayments = afterUiRepayment.paymentHistory().filter { it.customerId == "customer-demo" && it.kind == "customer_repayment" }
            assertEquals(2, repayments.size)
            assertTrue(repayments.any { it.amountPaise == 500 && it.status == "staff_declared" && it.method == "cash" })
            afterUiRepayment.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase("chai_tapri_counter.db")
        }
    }

    @Test fun inventoryRouteSavesMultiProductPhysicalCountAsOnePersistentSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("ui_tea_count", "UI Count Tea", "Tea", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(seed.saveProduct(TapriProduct("ui_sugar_count", "UI Count Sugar", "Grocery", 2000, true, false, true)).startsWith("Product saved"))
        assertTrue(seed.confirmOpeningStock("ui_tea_count", 5, "count UI test baseline", "ui-count-seed-tea").contains("5 units"))
        assertTrue(seed.confirmOpeningStock("ui_sugar_count", 8, "count UI test baseline", "ui-count-seed-sugar").contains("8 units"))
        seed.close()

        val monitor = instrumentation.addMonitor(StockCountActivity::class.java.name, null, false)
        val inventory = instrumentation.startActivitySync(Intent(context, InventoryOverviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var countActivity: StockCountActivity? = null
        try {
            fun findNode(textPrefix: String? = null, hintPrefix: String? = null, description: String? = null): AccessibilityNodeInfo {
                repeat(100) {
                    var found: AccessibilityNodeInfo? = null
                    fun walk(node: AccessibilityNodeInfo) {
                        val text = node.text?.toString().orEmpty()
                        val hint = node.hintText?.toString().orEmpty()
                        val desc = node.contentDescription?.toString().orEmpty()
                        if (found == null && ((textPrefix != null && text.startsWith(textPrefix, true)) ||
                                (hintPrefix != null && hint.startsWith(hintPrefix, true)) || (description != null && desc == description))) found = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                    }
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.let(::walk)
                    found?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Stock count UI element not found: text=$textPrefix hint=$hintPrefix description=$description")
            }
            assertTrue(findNode(textPrefix = "Count multiple products").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            countActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? StockCountActivity
            assertNotNull("Inventory Overview should open CT-16 count screen", countActivity)
            assertTrue(findNode(description = "Count UI Count Tea").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(findNode(description = "Count UI Count Sugar").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            fun enter(hint: String, value: String) {
                assertTrue(findNode(hintPrefix = hint).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }
            enter("UI Count Tea physical quantity", "3")
            enter("UI Count Sugar physical quantity", "invalid")
            enter("Count reason", "evening shelf count")
            assertTrue(findNode(textPrefix = "Save selected counts atomically").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(findNode(textPrefix = "Enter a whole-number physical count").text.toString().contains("No changes were made"))
            assertEquals("3", findNode(hintPrefix = "UI Count Tea physical quantity").text.toString())
            assertTrue(findNode(description = "Count UI Count Tea").isChecked)
            enter("UI Count Sugar physical quantity", "0")
            assertTrue(findNode(textPrefix = "Save selected counts atomically").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(findNode(textPrefix = "Review physical stock count").isVisibleToUser)
            val reviewText = findNode(textPrefix = "Nothing changes until you confirm.").text.toString()
            assertTrue(reviewText.contains("UI Count Tea: prior 5, counted 3, variance -2 units"))
            assertTrue(reviewText.contains("UI Count Sugar: prior 8, counted 0, variance -8 units"))
            assertTrue(findNode(textPrefix = "Confirm and save counts").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Wait until the activity reports its committed result before reading the database",
                findNode(textPrefix = "Physical count saved").text.toString().startsWith("Physical count saved"))

            val verify = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            assertEquals(3, verify.stockLevels()["ui_tea_count"])
            assertEquals(0, verify.stockLevels()["ui_sugar_count"])
            assertEquals(2, verify.stockMovements().count { it.reason == "physical_count:evening shelf count" })
            assertTrue(verify.auditEvents().any { it.contains("stock.physical_count") && it.contains("products=ui_sugar_count,ui_tea_count") })
            assertTrue(verify.purchaseHistory().isEmpty())
            verify.close()
            val db = SqliteCounterPersistence(context)
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE command_id IN (SELECT command_id FROM audit_events WHERE event LIKE '% stock.physical_count%')", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            db.close()
        } finally {
            instrumentation.runOnMainSync { countActivity?.finish(); inventory.finish() }
            instrumentation.removeMonitor(monitor)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun customerDirectoryOpensDetailsWithPersistedCreditAndRepaymentHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("ct22_tea", "CT22 Tea", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("ct22_tea", 5, "synthetic CT-22 fixture", "ct22-stock").contains("5 units"))
        assertTrue(model.quickSale(listOf("ct22_tea" to 2), "udhar", "ct22-customer", "ct22-credit-sale").startsWith("Quick sale"))
        assertTrue(model.saveCustomerProfile(CustomerProfile("ct22-customer", "Priya Joshi", "+919911223344", true)).contains("saved"))
        assertTrue(model.repayCredit("ct22-customer", 1000, "ct22-credit-repayment").startsWith("Repayment"))
        model.close()

        val monitor = instrumentation.addMonitor(CustomerDetailsActivity::class.java.name, null, false)
        val directory = instrumentation.startActivitySync(Intent(context, CustomerCreditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var openButton: Button? = null
            instrumentation.runOnMainSync {
                fun find(view: View) {
                    if (view is Button && view.text.toString() == "Customer details") openButton = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) view.getChildAt(index)?.let(::find)
                }
                find(directory.findViewById(android.R.id.content))
                assertTrue("Directory should expose the persisted account detail action", openButton != null)
                assertTrue(openButton!!.performClick())
            }
            val details = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? CustomerDetailsActivity
            assertNotNull("Directory action should open CT-22", details)
            val strings = mutableListOf<String>()
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is TextView) strings += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) view.getChildAt(index)?.let(::collect)
                }
                collect(details!!.findViewById(android.R.id.content))
            }
            assertTrue(strings.contains("Account ID: ct22-customer"))
            assertTrue(strings.contains("Credit extended: ₹30.00"))
            assertTrue(strings.contains("Repaid: ₹10.00"))
            assertTrue(strings.contains("Outstanding: ₹20.00"))
            assertTrue(strings.contains("Name: Priya Joshi"))
            assertTrue(strings.any { it.contains("customer_repayment") && it.contains("device local time") })
            assertTrue(strings.any { it.contains("Oldest open credit:") && it.contains("since order (device clock)") && it.contains("No due date is recorded") })
            assertTrue(strings.any { it.contains("Contact:") && it.contains("3344") })
            assertFalse(strings.any { it.contains("+919911223344") })
            assertTrue(strings.any { it.contains("customer_repayment") && it.contains("₹10.00") && it.contains("staff_declared") })
            instrumentation.runOnMainSync { details!!.finish(); directory.finish() }
        } finally {
            instrumentation.removeMonitor(monitor)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun customerDirectorySearchFiltersAccountIdsAndNamesCaseInsensitively() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("ct21_tea", "CT21 Tea", "Chai", 1000, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("ct21_tea", 5, "synthetic CT-21 search fixture", "ct21-stock").contains("5 units"))
        assertTrue(model.quickSale(listOf("ct21_tea" to 1), "udhar", "account-alpha", "ct21-alpha-sale").startsWith("Quick sale"))
        assertTrue(model.quickSale(listOf("ct21_tea" to 1), "udhar", "Account-Beta", "ct21-beta-sale").startsWith("Quick sale"))
        assertTrue(model.saveCustomerProfile(CustomerProfile("Account-Beta", "Priya Nair")).contains("saved"))
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, CustomerCreditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var search: EditText? = null
            var alphaCard: View? = null
            var betaCard: View? = null
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is EditText && view.hint.toString() == "Search customer accounts") search = view
                    if (view is TextView && view.text.toString() == "account-alpha") alphaCard = view.parent as? View
                    if (view is TextView && view.text.toString() == "Account-Beta") betaCard = view.parent as? View
                    if (view is ViewGroup) for (index in 0 until view.childCount) view.getChildAt(index)?.let(::collect)
                }
                collect(activity.findViewById(android.R.id.content))
                assertNotNull("Directory search input should be present", search)
                assertNotNull(alphaCard); assertNotNull(betaCard)
                search!!.setText("pRiYa")
                assertEquals(View.GONE, alphaCard!!.visibility)
                assertEquals(View.VISIBLE, betaCard!!.visibility)
                search!!.setText("ALPHA")
                assertEquals(View.VISIBLE, alphaCard!!.visibility)
                assertEquals(View.GONE, betaCard!!.visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun customerProfileCreationStoresConsentedContactAndKeepsPiiOutOfAudit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val missingConsent = model.saveCustomerProfile(CustomerProfile("profile-asha", "Asha Sharma", "+919876543210", false))
        assertTrue(missingConsent.contains("consent"))
        assertTrue(model.customerProfiles().isEmpty())
        assertTrue(model.saveCustomerProfile(CustomerProfile("profile-asha", "Asha Sharma", "+919876543210", true)).contains("saved"))
        assertTrue(model.saveCustomerProfile(CustomerProfile("profile-asha", "Changed Name")).contains("already uses"))
        assertTrue(model.saveProduct(TapriProduct("profile_tea", "Profile Test Tea", "Chai", 1000, true, true, true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("profile_tea", 3, "profile persistence fixture", "profile-stock").contains("3 units"))
        assertTrue(model.quickSale(listOf("profile_tea" to 1), "cash", commandId = "profile-trigger-sale").startsWith("Quick sale"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val profile = model.customerProfiles().single()
        assertEquals("profile-asha", profile.customerId)
        assertEquals("Asha Sharma", profile.displayName)
        assertEquals("+919876543210", profile.phone)
        assertTrue(profile.contactConsent)
        val profileAudit = model.auditEvents().single { it.contains("customer.profile_saved") }
        assertTrue(profileAudit.contains("customer.profile_saved"))
        assertFalse(profileAudit.contains("profile-asha"))
        assertFalse(profileAudit.contains("Asha Sharma"))
        assertFalse(profileAudit.contains("+919876543210"))
        val store = SqliteCounterPersistence(context)
        store.readableDatabase.rawQuery("SELECT command_id FROM audit_events WHERE event LIKE 'customer.profile_saved%'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val commandId = cursor.getString(0)
            assertEquals(1, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE command_id=?", arrayOf(commandId)).use { it.moveToFirst(); it.getInt(0) })
        }
        store.close()
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun customerProfileUpdateRevokesContactAndAuditsWithoutChangingCredit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveCustomerProfile(CustomerProfile("profile-revoke", "Asha Sharma", "+919876543210", true)).contains("saved"))
        val before = model.customerProfiles().single()
        val update = model.updateCustomerProfile(before.copy(displayName = "Asha S." , phone = null, contactConsent = false))
        assertTrue(update, update.contains("updated"))
        val after = model.customerProfiles().single()
        assertEquals("Asha S.", after.displayName)
        assertNull(after.phone)
        assertFalse(after.contactConsent)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(0, model.creditBalance("profile-revoke"))
        val store = SqliteCounterPersistence(context)
        store.readableDatabase.rawQuery("SELECT command_id,event FROM audit_events WHERE event LIKE 'customer.profile_updated%'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val commandId = cursor.getString(0)
            val updateEvent = cursor.getString(1)
            assertTrue(updateEvent.contains("consent_revoked=true"))
            assertFalse(updateEvent.contains("profile-revoke"))
            assertFalse(updateEvent.contains("Asha"))
            assertFalse(updateEvent.contains("+919876543210"))
            assertEquals(1, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(1, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE command_id=?", arrayOf(commandId)).use { it.moveToFirst(); it.getInt(0) })
        }
        store.close()
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun schemaV18ToV19AddsOptionalReorderThresholdWithoutChangingStockOrOrders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val product = TapriProduct("migration_tea", "Migration Tea", "Chai", 1200, true, true, true, reorderThreshold = 4)
        assertTrue(model.saveProduct(product).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("migration_tea", 7, "migration fixture", "threshold-stock").contains("7 units"))
        assertTrue(model.quickSale(listOf("migration_tea" to 2), "cash", commandId = "threshold-sale").startsWith("Quick sale"))
        model.close()

        val legacy = SqliteCounterPersistence(context)
        legacy.writableDatabase.execSQL("ALTER TABLE product_catalog DROP COLUMN reorder_threshold")
        legacy.writableDatabase.version = 18
        legacy.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence.DATABASE_VERSION)
        assertEquals(null, model.products().single { it.productId == "migration_tea" }.reorderThreshold)
        assertEquals(1, model.allOrders().size)
        assertEquals(2400, model.allOrders().single().salePaise)
        assertEquals(5, model.stockLevels()["migration_tea"])
        assertTrue(model.saveProduct(product.copy(reorderThreshold = 3)).contains("Product saved"))
        model.close()
        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(3, reopened.products().single { it.productId == "migration_tea" }.reorderThreshold)
        assertEquals(5, reopened.stockLevels()["migration_tea"])
        reopened.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun schemaV16ToV18AddsCustomerProfilesAndOutboxPayloadsWithoutChangingFinancialRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("migration_tea", "Migration Tea", "Chai", 1200, true, true, true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("migration_tea", 4, "v16 migration stock fixture", "migration-stock").contains("4 units"))
        assertTrue(model.quickSale(listOf("migration_tea" to 2), "udhar", "legacy-account", "migration-credit-sale").startsWith("Quick sale"))
        model.close()

        val oldStore = SqliteCounterPersistence(context)
        val oldDb = oldStore.writableDatabase
        oldDb.execSQL("DROP TABLE customer_profiles")
        oldDb.execSQL("ALTER TABLE product_catalog DROP COLUMN reorder_threshold")
        oldDb.version = 16
        oldStore.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence.DATABASE_VERSION)
        assertEquals(1, model.allOrders().size)
        assertEquals(2400, model.allOrders().single().salePaise)
        assertEquals(2400, model.creditBalance("legacy-account"))
        assertTrue(model.customerProfiles().isEmpty())
        assertTrue(model.saveCustomerProfile(CustomerProfile("legacy-account", "Legacy Customer")).contains("saved"))
        assertTrue(model.quickSale(listOf("migration_tea" to 1), "cash", commandId = "post-migration-sale").startsWith("Quick sale"))
        model.close()
        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals("Legacy Customer", model.customerProfiles().single().displayName)
        assertEquals(2, model.allOrders().size)
        assertEquals(2400, model.creditBalance("legacy-account"))
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun customerDirectoryAddProfileDialogSavesContactOnlyAfterConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val activity = instrumentation.startActivitySync(Intent(context, CustomerCreditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(textPrefix: String? = null, hint: String? = null, className: String? = null): AccessibilityNodeInfo {
                repeat(100) {
                    var selected: AccessibilityNodeInfo? = null
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun walk(node: AccessibilityNodeInfo) {
                        val text = node.text?.toString().orEmpty()
                        val matches = when {
                            textPrefix != null -> text.startsWith(textPrefix, ignoreCase = true)
                            hint != null -> node.hintText?.toString() == hint
                            else -> node.className?.toString() == className
                        }
                        if (selected == null && matches) selected = node
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                    }
                    root?.let(::walk)
                    selected?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Customer profile UI element was not found: text=$textPrefix hint=$hint class=$className")
            }
            fun clickText(text: String) { assertTrue(awaitNode(textPrefix = text).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            fun setConsent(checked: Boolean) {
                val text = "Customer agreed to store this number"
                if (awaitNode(textPrefix = text).isChecked != checked) clickText(text)
                assertEquals(checked, awaitNode(textPrefix = text).isChecked)
            }
            fun enter(hint: String, value: String) {
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                repeat(20) {
                    val field = awaitNode(hint = hint)
                    val visible = field.text?.toString().orEmpty()
                    // Android may expose the hint as node.text while an empty EditText is focused.
                    // Do not treat that initial hint as proof that an empty replacement was entered;
                    // issue ACTION_SET_TEXT first and verify the persisted profile after saving.
                    if (visible == value) return
                    assertTrue(awaitNode(hint = hint).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                    Thread.sleep(100)
                }
                val visible = awaitNode(hint = hint).text?.toString().orEmpty()
                assertTrue("Accessibility text entry did not settle for $hint: $visible", visible == value || (value.isEmpty() && visible == hint))
            }

            clickText("Add customer profile")
            enter("Customer account ID", "ui-customer-1")
            enter("Customer name", "Meera Joshi")
            enter("Phone number (optional)", "+919811223344")
            setConsent(true)
            clickText("Save profile")

            val saveFeedback = mutableListOf<String>()
            instrumentation.runOnMainSync {
                fun collectFeedback(view: View) {
                    if (view is TextView) saveFeedback += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) view.getChildAt(index)?.let(::collectFeedback)
                }
                collectFeedback(activity.findViewById(android.R.id.content))
            }
            assertTrue("Profile form should show saved confirmation; visible text: $saveFeedback", saveFeedback.any { it.contains("Customer profile saved") })

            val saved = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val profile = saved.customerProfiles().singleOrNull()
            assertNotNull("Confirmed profile save must be durable; visible text: $saveFeedback", profile)
            profile!!
            assertEquals("Meera Joshi", profile.displayName)
            assertEquals("+919811223344", profile.phone)
            assertTrue(profile.contactConsent)
            saved.close()
            val visibleText = mutableListOf<String>()
            instrumentation.runOnMainSync {
                fun collect(view: View) {
                    if (view is TextView) visibleText += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) view.getChildAt(index)?.let(::collect)
                }
                collect(activity.findViewById(android.R.id.content))
            }
            assertTrue(visibleText.contains("Meera Joshi"))
            assertTrue(visibleText.any { it.contains("Contact:") && it.contains("3344") })
            assertFalse(visibleText.any { it.contains("+919811223344") })

            clickText("Edit profile / contact consent")
            clickText("Clear saved phone and revoke consent")
            instrumentation.waitForIdleSync()
            val revoked = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val revokedProfile = revoked.customerProfiles().single()
            assertEquals("Meera Joshi", revokedProfile.displayName)
            assertNull(revokedProfile.phone)
            assertFalse(revokedProfile.contactConsent)
            revoked.close()
        } finally {
            instrumentation.runOnMainSync {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.window.decorView.clearFocus()
                activity.finish()
            }
            Thread.sleep(300)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun orderDetailsScreenReadsPersistedFinancialStockAndAuditRecords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai_detail", "Detail Chai", "Chai", 1500, true, true, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("chai_detail", 4, "synthetic detail test count", "detail-stock").contains("4 units"))
        assertTrue(model.quickSale(listOf("chai_detail" to 2), "cash", commandId = "detail-sale").startsWith("Quick sale"))
        val detailAudit = model.auditRecords().single { it.commandId == "detail-sale" }
        assertTrue("Audit record should retain its locally recorded timestamp", detailAudit.createdAtEpochMs > 0L)
        model.close()

        val activity = instrumentation.startActivitySync(Intent(context, OrderDetailsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, 12))
        val strings = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) strings += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.findViewById(android.R.id.content))
        assertTrue(strings.contains("Order #12"))
        assertTrue(strings.any { it.contains("Delivered sale: ₹30.00") })
        assertTrue(strings.any { it.contains("Collections recorded: ₹30.00") })
        assertTrue(strings.any { it.contains("chai_detail · -2 units · quick_sale") })
        assertTrue(strings.any { it.contains("quick_sale #12") })
        assertTrue(strings.any { it.contains("Local time:") && it.contains("quick_sale #12") })
        assertTrue(strings.any { it.contains("device's clock") && it.contains("not server-verified") })
        instrumentation.runOnMainSync { activity.finish() }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun managerCanCorrectUnhandedLineFromOrderDetailsWithoutChangingPaymentsOrStock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Regular Chai", "Chai", 1500, true, true, true)).contains("Product saved"))
        assertTrue(model.saveProduct(TapriProduct("samosa", "Samosa", "Snacks", 2000, true, false, true)).contains("Product saved"))
        assertTrue(model.apply("detail-correction-create", ProposedCommand("create", null, "new order", "chai", 2)).contains("Order #12 created"))
        assertTrue(model.apply("detail-correction-add", ProposedCommand("add", 12, "add samosa", "samosa", 1, expectedRevision = model.allOrders().single().revision)).contains("Added 1"))
        val originalRevision = model.allOrders().single().revision
        model.close()
        LocalSessionState.establishStaff("detail-manager", "Detail Manager", StaffRole.MANAGER)

        val activity = instrumentation.startActivitySync(Intent(context, OrderDetailsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, 12))
        try {
            var correctionButton: Button? = null
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is Button && view.text.toString() == "Correct unhanded quantity · Regular Chai") correctionButton = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(activity.findViewById(android.R.id.content))
                assertNotNull("Manager should see an unhanded-line correction", correctionButton)
                correctionButton!!.performClick()
            }

            val ui = instrumentation.uiAutomation
            var clicked = false
            var visibleDialogText = ""
            repeat(80) {
                if (!clicked) {
                    val root = ui.rootInActiveWindow
                    fun clickText(node: AccessibilityNodeInfo): Boolean {
                        val text = node.text?.toString().orEmpty()
                        if (text.isNotBlank()) visibleDialogText += "$text | "
                        if (text.contains("Remove quantity", ignoreCase = true)) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        for (index in 0 until node.childCount) node.getChild(index)?.let { child -> if (clickText(child)) return true }
                        return false
                    }
                    if (root != null) clicked = clickText(root)
                    if (!clicked) Thread.sleep(100)
                }
            }
            assertTrue("Confirmation dialog should expose an explicit remove action; visible text: $visibleDialogText", clicked)
            Thread.sleep(300)
            SqliteCounterPersistence(context).use { db ->
                val snapshot = db.load() ?: error("Corrected snapshot missing")
                val order = snapshot.orders.single { it.number == 12 }
                assertEquals(originalRevision + 1, order.revision)
                assertTrue(order.lines.none { it.productId == "chai" })
                assertEquals(1, order.lines.single { it.productId == "samosa" }.quantity)
                assertTrue("Correction audit actor/event: ${db.loadAuditRecords().joinToString { "${it.actorId}:${it.event}" }}", db.loadAuditRecords().any { it.event.contains("remove #12") && it.actorId == "detail-manager" })
                assertTrue(snapshot.paymentEntries.isEmpty())
                assertTrue(snapshot.stockMovements.isEmpty())
                assertFalse(order.salePosted)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); LocalSessionState.establish("Synthetic test owner") }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun cashierCanAddConfiguredItemFromOpenOrderDetailsWithoutPostingSaleOrStock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("chai", "Regular Chai", "Chai", 1000, true, true, true)).contains("Product saved"))
        assertTrue(model.saveProduct(TapriProduct("samosa", "Samosa", "Snacks", 2000, true, false, true)).contains("Product saved"))
        assertTrue(model.apply("detail-add-create", ProposedCommand("create", null, "new order", "chai", 1)).contains("Order #12 created"))
        val originalRevision = model.allOrders().single().revision
        model.close()
        LocalSessionState.establishStaff("detail-cashier", "Detail Cashier", StaffRole.CASHIER)

        val activity = instrumentation.startActivitySync(Intent(context, OrderDetailsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, 12))
        try {
            var addButton: Button? = null
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is Button && view.text.toString() == "Add item to order") addButton = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(activity.findViewById(android.R.id.content))
                assertNotNull("Authenticated cashier should see the open-order add action", addButton)
                addButton!!.performClick()
            }

            val ui = instrumentation.uiAutomation
            fun awaitAndClick(predicate: (String) -> Boolean, description: String) {
                var clicked = false
                var visibleLabels = ""
                repeat(100) {
                    if (!clicked) {
                        val root = ui.rootInActiveWindow
                        fun visit(node: AccessibilityNodeInfo): Boolean {
                            val text = node.text?.toString().orEmpty()
                            if (text.isNotBlank()) visibleLabels += "$text | "
                            if (predicate(text)) {
                                val bounds = android.graphics.Rect()
                                node.getBoundsInScreen(bounds)
                                if (bounds.isEmpty) return false
                                val now = android.os.SystemClock.uptimeMillis()
                                val x = bounds.exactCenterX()
                                val y = bounds.exactCenterY()
                                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                                return try {
                                    ui.injectInputEvent(down, true) && ui.injectInputEvent(up, true)
                                } finally {
                                    down.recycle()
                                    up.recycle()
                                }
                            }
                            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> if (visit(child)) return true }
                            return false
                        }
                        if (root != null) clicked = visit(root)
                        if (!clicked) Thread.sleep(100)
                    }
                }
                assertTrue("Could not click $description; visible labels: $visibleLabels", clicked)
            }
            awaitAndClick({ it.startsWith("Samosa", ignoreCase = true) }, "configured Samosa product")
            awaitAndClick({ it.equals("Add item", ignoreCase = true) }, "add-item confirmation")
            SqliteCounterPersistence(context).use { db ->
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                var snapshot: CounterSnapshot? = null
                while (snapshot == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    val current = db.load()
                    if (current?.orders?.singleOrNull { it.number == 12 }?.revision == originalRevision + 1) snapshot = current
                    else Thread.sleep(100)
                }
                assertNotNull("Order item change should commit and increment the revision", snapshot)
                val committed = snapshot!!
                val order = committed.orders.single { it.number == 12 }
                assertEquals(originalRevision + 1, order.revision)
                assertEquals(3000, order.lines.sumOf { it.quantity * it.unitPricePaise })
                assertEquals(1, order.lines.single { it.productId == "chai" }.quantity)
                assertEquals(1, order.lines.single { it.productId == "samosa" }.quantity)
                assertFalse(order.salePosted)
                assertTrue(committed.paymentEntries.isEmpty())
                assertTrue(committed.stockMovements.isEmpty())
                assertTrue(db.loadAuditRecords().any { it.event.contains("add #12") && it.actorId == "detail-cashier" })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); LocalSessionState.establish("Synthetic test owner") }
            Thread.sleep(250)
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun cataloguePriceMustBeConfirmedAndIsTheSalePriceAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.products().any { it.productId == "cutting_chai" && !it.priceConfirmed })
        assertEquals("Unknown product or quantity.", model.apply("unconfirmed-sale", ProposedCommand("create", null, "create", "cutting_chai", 2)))
        assertTrue(model.saveProduct(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1275, true, true, true, listOf("cutting", "kadak cutting"))).startsWith("Product saved"))
        val create = model.parse("Naya order, do cutting")
        assertTrue(create != null)
        assertTrue(model.apply("catalog-sale", create!!).contains("Order #12 created"))
        assertEquals(2550, model.allOrders().single().totalPaise)
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(1275, reopened.products().single { it.productId == "cutting_chai" }.unitPricePaise)
        assertEquals(2550, reopened.allOrders().single().totalPaise)
        assertEquals("Duplicate command ignored.", reopened.apply("catalog-sale", create))
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun catalogueEditorPersistsLowStockThresholdAndAlertReadsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val seed = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(seed.saveProduct(TapriProduct("editor_tea", "Editor Tea", "Chai", 1200, true, false, true)).startsWith("Product saved"))
        assertTrue(seed.confirmOpeningStock("editor_tea", 1, "synthetic editor UI fixture", "editor-ui-stock").contains("1 units"))
        seed.close()

        val activity = instrumentation.startActivitySync(Intent(context, ProductCatalogueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var lastTree = "<no active window>"
            fun node(textPrefix: String? = null, hint: String? = null): AccessibilityNodeInfo {
                repeat(80) {
                    var match: AccessibilityNodeInfo? = null
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val labels = mutableListOf<String>()
                    fun walk(current: AccessibilityNodeInfo) {
                        val text = current.text?.toString().orEmpty()
                        if (text.isNotBlank()) labels += text
                        if (match == null && ((textPrefix != null && text.startsWith(textPrefix, true)) ||
                                (hint != null && current.hintText?.toString() == hint))) match = current
                        for (index in 0 until current.childCount) current.getChild(index)?.let(::walk)
                    }
                    root?.let(::walk)
                    lastTree = labels.joinToString(" | ")
                    match?.let { return it }
                    root?.let(::scrollActiveWindowForward)
                    Thread.sleep(100)
                }
                throw AssertionError("Catalogue UI element not found: text=$textPrefix hint=$hint; current UI=$lastTree")
            }
            assertTrue(node(textPrefix = "Edit Editor Tea").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val thresholdHint = "Low-stock alert threshold in units (optional, 1–100000)"
            val field = node(hint = thresholdHint)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2")
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            assertTrue(node(textPrefix = "Save").performAction(AccessibilityNodeInfo.ACTION_CLICK))

            var rendered = false
            repeat(60) {
                val store = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
                val product = store.products().single { it.productId == "editor_tea" }
                store.close()
                if (product.reorderThreshold == 2) rendered = true
                if (rendered) return@repeat
                Thread.sleep(100)
            }
            assertTrue("Threshold edit should persist in encrypted catalogue", rendered)
            val verify = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            assertEquals(2, verify.products().single { it.productId == "editor_tea" }.reorderThreshold)
            assertEquals(listOf("Editor Tea"), verify.lowStockProducts().map { it.first.name })
            verify.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun syncAcknowledgementAndConflictAreDurableAndCursorNeverSkipsConflict() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("sync_tea", "Sync Tea", "Chai", 1000, true, false, true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("sync_tea", 20, "synthetic sync fixture", "sync-stock-opening").contains("20 units"))
        assertTrue(model.quickSale(listOf("sync_tea" to 1), "cash", commandId = "sync-first-sale").startsWith("Quick sale"))
        assertTrue(model.quickSale(listOf("sync_tea" to 1), "cash", commandId = "sync-second-sale").startsWith("Quick sale"))
        assertTrue(model.quickSale(listOf("sync_tea" to 1), "cash", commandId = "sync-third-sale").startsWith("Quick sale"))
        model.close()
        val pendingBefore = SqliteCounterPersistence(context).use { it.loadPendingSyncEvents(10) }
        assertEquals(4, pendingBefore.size)
        SqliteCounterPersistence(context).use { assertEquals(0L, it.loadSyncCursor()) }

        // Simulate a real v19 file: retain all records but remove later v20/v21 columns and tables.
        SqliteCounterPersistence(context).use { legacy ->
            legacy.writableDatabase.execSQL("ALTER TABLE sync_outbox DROP COLUMN server_revision")
            legacy.writableDatabase.version = 19
        }
        val upgraded = SqliteCounterPersistence(context)
        val afterMigration = upgraded.loadPendingSyncEvents(10)
        assertEquals(4, afterMigration.size)
        assertEquals(pendingBefore.map { it.eventId }, afterMigration.map { it.eventId })
        assertEquals(0L, upgraded.loadSyncCursor())
        val first = afterMigration.first()
        assertFalse("Out-of-order server responses must not acknowledge past the queue head.",
            upgraded.recordSyncOutcome(afterMigration[1].eventId, "accepted", 1L))
        assertTrue(upgraded.recordSyncOutcome(first.eventId, "accepted", 1L))
        assertTrue(upgraded.recordSyncOutcome(first.eventId, "duplicate", 1L))
        assertFalse(upgraded.recordSyncOutcome(first.eventId, "accepted", 2L))
        assertEquals(1L, upgraded.loadSyncCursor())
        assertEquals(afterMigration.drop(1).map { it.eventId }, upgraded.loadPendingSyncEvents(10).map { it.eventId })

        val conflictEvent = afterMigration[1]
        assertTrue(upgraded.recordSyncOutcome(conflictEvent.eventId, "conflict", 5L))
        assertTrue(upgraded.recordSyncOutcome(conflictEvent.eventId, "conflict", 5L))
        assertEquals(1L, upgraded.loadSyncCursor())
        assertTrue(upgraded.loadPendingSyncEvents(10).isEmpty())
        upgraded.close()

        SqliteCounterPersistence(context).use { verify ->
            assertEquals("acknowledged", verify.writableDatabase.rawQuery("SELECT status FROM sync_outbox WHERE event_id=?", arrayOf(first.eventId)).use { it.moveToFirst(); it.getString(0) })
            assertEquals("conflict", verify.writableDatabase.rawQuery("SELECT status FROM sync_outbox WHERE event_id=?", arrayOf(conflictEvent.eventId)).use { it.moveToFirst(); it.getString(0) })
            assertEquals(5L, verify.writableDatabase.rawQuery("SELECT server_revision FROM sync_outbox WHERE event_id=?", arrayOf(conflictEvent.eventId)).use { it.moveToFirst(); it.getLong(0) })
            afterMigration.drop(2).forEach { queued ->
                assertEquals("pending", verify.writableDatabase.rawQuery("SELECT status FROM sync_outbox WHERE event_id=?", arrayOf(queued.eventId)).use { it.moveToFirst(); it.getString(0) })
            }
        }
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun quickSaleAtomicallyPostsStockAndDeclaredTenderThenRejectsDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        model.saveProduct(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1250, true, true, true))
        assertTrue(model.confirmOpeningStock("cutting_chai", 100, "synthetic initial count", "quick-stock-opening").contains("100 units"))
        val result = model.quickSale(listOf("cutting_chai" to 2), "cash", commandId = "quick-cash-once")
        assertTrue(result.startsWith("Quick sale"))
        assertEquals(1, model.allOrders().size)
        assertEquals(2500, model.allOrders().single().salePaise)
        assertEquals(Payment.STAFF_DECLARED, model.allOrders().single().payment)
        assertEquals(98, model.stockLevels()["cutting_chai"])
        assertEquals(-2, model.stockMovements().single { it.reason == "quick_sale" }.quantityDelta)
        val outboxStore = SqliteCounterPersistence(context)
        outboxStore.readableDatabase.rawQuery("SELECT event_id,command_id,status,payload_version,payload_json,blocked_reason FROM sync_outbox WHERE command_id=?", arrayOf("quick-cash-once")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(java.util.UUID.fromString(cursor.getString(0)).toString(), cursor.getString(0))
            assertEquals("quick-cash-once", cursor.getString(1))
            assertEquals("pending", cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
            assertNull(cursor.getString(5))
            val payload = org.json.JSONObject(cursor.getString(4))
            assertEquals("projection_delta.v1", payload.getString("kind"))
            assertEquals(98, payload.getJSONArray("stock").getJSONObject(0).getInt("quantity"))
            assertEquals(2500, payload.getJSONArray("orders").getJSONObject(0).getInt("paidPaise"))
            assertEquals("staff_declared", payload.getJSONArray("paymentEntries").getJSONObject(0).getString("status"))
            assertFalse(cursor.getString(4).contains("Cutting Chai"))
            assertFalse(cursor.getString(4).contains("audit"))
        }
        outboxStore.close()
        assertEquals("Duplicate command ignored.", model.quickSale(listOf("cutting_chai" to 2), "cash", commandId = "quick-cash-once"))
        assertEquals(1, model.paymentHistory().count { it.kind == "order_collection" })
        assertTrue(model.quickSale(listOf("cutting_chai" to 1), "upi_pending", commandId = "quick-upi-pending").startsWith("Quick sale"))
        assertEquals(Payment.UPI_PENDING, model.allOrders().first { it.number == 13 }.payment)
        assertEquals(0, model.allOrders().first { it.number == 13 }.paidPaise)
        assertTrue(model.quickSale(listOf("cutting_chai" to 2), "udhar", "customer-demo", "quick-udhar").startsWith("Quick sale"))
        assertEquals(2500, model.creditBalance("customer-demo"))
        assertEquals(95, model.stockLevels()["cutting_chai"])
        val summary = model.localBusinessSummary()
        assertEquals(6250L, summary.recordedSalesPaise)
        assertEquals(2500L, summary.cashRecordedPaise)
        assertEquals(1250L, summary.upiPendingPaise)
        assertEquals(2500L, summary.customerReceivablePaise)
        assertEquals(0, summary.openOrderCount)
        assertEquals(1, summary.productCount)
        assertEquals(1, summary.stockConfirmedProductCount)
        model.close()
        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(3, reopened.allOrders().size)
        assertEquals(95, reopened.stockLevels()["cutting_chai"])
        assertEquals("staff_declared", reopened.paymentHistory().single { it.method == "cash" }.status)
        assertEquals("pending", reopened.paymentHistory().single { it.method == "upi" }.status)
        assertEquals(2500, reopened.creditBalance("customer-demo"))
        assertEquals("Duplicate command ignored.", reopened.quickSale(listOf("cutting_chai" to 2), "cash", commandId = "quick-cash-once"))
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun quickSaleUdharSelectsSavedCustomerInsteadOfTypingAnAccountId() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val setup = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(setup.saveProduct(TapriProduct("udhar_tea", "Tea", "Chai", 1000, priceConfirmed = true)).contains("Product saved"))
        assertTrue(setup.confirmOpeningStock("udhar_tea", 10, "Customer picker UI synthetic count", "picker-stock").contains("10 units"))
        assertTrue(setup.saveCustomerProfile(CustomerProfile("cust-asha", "Asha")).contains("profile saved"))
        assertTrue(setup.quickSale(listOf("udhar_tea" to 1), "udhar", "legacy-customer", "picker-legacy-credit").startsWith("Quick sale"))
        setup.close()

        val activity = instrumentation.startActivitySync(Intent(context, QuickSaleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String): AccessibilityNodeInfo {
                repeat(80) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("UI item not found: $prefix")
            }
            fun click(prefix: String) {
                val node = awaitNode(prefix)
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                assertTrue("UI control has no visible bounds: $prefix", !bounds.isEmpty)
                val automation = instrumentation.uiAutomation
                val now = android.os.SystemClock.uptimeMillis()
                val x = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    assertTrue("Could not tap UI control: $prefix", automation.injectInputEvent(down, true))
                    assertTrue("Could not release tap: $prefix", automation.injectInputEvent(up, true))
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }

            click("Tea")
            click("Checkout")
            click("Udhar")
            val selected = awaitNode("Asha")
            assertTrue("Saved customer option should be selectable", selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val order = reopened.allOrders().maxByOrNull { it.number }
            assertNotNull("Quick Sale should commit the selected Udhar sale", order)
            assertEquals(Payment.CREDIT, order!!.payment)
            assertEquals("cust-asha", reopened.customerCredits().last().customerId)
            assertEquals(8, reopened.stockLevels()["udhar_tea"])
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun quickSaleUiRecordsCashAndUpiPendingAsDifferentPaymentStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val setup = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(setup.saveProduct(TapriProduct("ui_quick_tea", "Tea", "Chai", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(setup.confirmOpeningStock("ui_quick_tea", 5, "Synthetic Quick Sale UI tender test", "ui-quick-stock").contains("5 units"))
        setup.close()

        val activity = instrumentation.startActivitySync(Intent(context, QuickSaleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String): AccessibilityNodeInfo {
                repeat(80) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("UI item not found: $prefix")
            }
            fun click(prefix: String) {
                assertTrue("Could not activate UI control: $prefix", awaitNode(prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }

            click("Tea")
            click("Checkout")
            click("Cash received")
            click("Tea")
            click("Checkout")
            click("UPI pending")

            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val orders = reopened.allOrders().sortedBy { it.number }
            assertEquals(2, orders.size)
            assertEquals(Payment.STAFF_DECLARED, orders[0].payment)
            assertEquals(1500, orders[0].paidPaise)
            assertEquals(Payment.UPI_PENDING, orders[1].payment)
            assertEquals(0, orders[1].paidPaise)
            assertEquals(3, reopened.stockLevels()["ui_quick_tea"])
            assertEquals("staff_declared", reopened.paymentHistory().single { it.method == "cash" }.status)
            assertEquals("pending", reopened.paymentHistory().single { it.method == "upi" }.status)
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun quickSaleUiRecordsSplitCashAndUdharInOneAtomicSale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val setup = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(setup.saveProduct(TapriProduct("split_ui_tea", "Split Tea", "Chai", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(setup.confirmOpeningStock("split_ui_tea", 3, "Synthetic Quick Sale split tender", "split-ui-stock").contains("3 units"))
        assertTrue(setup.saveCustomerProfile(CustomerProfile("split_ui_ravi", "Ravi")).contains("profile saved"))
        setup.close()

        val activity = instrumentation.startActivitySync(Intent(context, QuickSaleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String? = null, className: String? = null): AccessibilityNodeInfo {
                repeat(80) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if ((prefix == null || node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) &&
                            (className == null || node.className?.toString() == className)) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("UI item not found: ${prefix ?: className}")
            }
            fun click(prefix: String) {
                assertTrue("Could not activate UI control: $prefix", awaitNode(prefix = prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }

            click("Split Tea")
            click("Checkout")
            click("Split cash + Udhar")
            val amount = awaitNode(className = EditText::class.java.name)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "5") }
            assertTrue("Cash split amount should be editable", amount.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            click("Choose customer")
            assertTrue("Saved customer should be selectable", awaitNode(prefix = "Ravi").performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val order = reopened.allOrders().single()
            assertEquals(1500, order.salePaise)
            assertEquals(500, order.paidPaise)
            assertEquals(1000, order.creditPaise)
            assertEquals(0, order.duePaise)
            assertEquals(Payment.SPLIT, order.payment)
            assertEquals("split_ui_ravi", reopened.customerCredits().single().customerId)
            assertEquals(1000, reopened.creditBalance("split_ui_ravi"))
            assertEquals(2, reopened.paymentHistory().count { it.orderNumber == order.number })
            assertEquals("staff_declared", reopened.paymentHistory().single { it.method == "cash" }.status)
            assertEquals("receivable", reopened.paymentHistory().single { it.method == "udhar" }.status)
            assertEquals(2, reopened.stockLevels()["split_ui_tea"])
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun liveOrdersUdharSelectsSavedCustomerAndSettlesOrderDue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val setup = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(setup.saveProduct(TapriProduct("live_udhar_tea", "Tea", "Chai", 1200, priceConfirmed = true)).contains("Product saved"))
        assertTrue(setup.confirmOpeningStock("live_udhar_tea", 4, "Synthetic Live Orders picker count", "live-picker-stock").contains("4 units"))
        assertTrue(setup.saveCustomerProfile(CustomerProfile("cust_live_asha", "Asha" )).contains("profile saved"))
        assertTrue(setup.apply("live-picker-create", ProposedCommand("create", null, "Create synthetic order", "live_udhar_tea", 1)).contains("Order #"))
        val order = setup.allOrders().single()
        assertTrue(setup.apply("live-picker-ready", ProposedCommand("ready", order.number, "Mark ready", expectedRevision = order.revision)).contains("ready"))
        val ready = setup.allOrders().single()
        assertTrue(setup.apply("live-picker-given", ProposedCommand("given", ready.number, "Hand over", expectedRevision = ready.revision)).contains("sale recorded"))
        setup.close()

        val activity = instrumentation.startActivitySync(Intent(context, LiveOrdersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun awaitNode(prefix: String): AccessibilityNodeInfo {
                repeat(80) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("UI item not found: $prefix")
            }
            fun click(prefix: String) {
                assertTrue("Could not activate UI control: $prefix", awaitNode(prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            click("Record Udhar")
            assertTrue("Saved customer option should be selectable", awaitNode("Asha").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click("Save credit")

            var creditedOrder: CounterOrder? = null
            for (attempt in 0 until 100) {
                CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true).use { model ->
                    val current = model.allOrders().single()
                    if (current.payment == Payment.CREDIT) creditedOrder = current
                }
                if (creditedOrder != null) break
                Thread.sleep(100)
            }
            assertNotNull("Credit entry should be durably posted before the order is read", creditedOrder)
            val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val savedOrder = reopened.allOrders().single()
            assertEquals(Payment.CREDIT, savedOrder.payment)
            assertEquals("cust_live_asha", reopened.customerCredits().single().customerId)
            assertEquals(0, savedOrder.duePaise)
            assertEquals(3, reopened.stockLevels()["live_udhar_tea"])
            reopened.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun quickSaleSearchAndCategoryFiltersUseConfiguredProductAliases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val productId = "quick_filter_$token"
        val alias = "tapri_alias_$token"
        val productName = "Filter Test $token"
        val model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct(productId, productName, "Test Category $token", 1250, favorite = true, priceConfirmed = true, aliases = listOf(alias))).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock(productId, 3, "synthetic UI filter count").startsWith("Opening stock confirmed"))
        model.close()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, QuickSaleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QuickSaleActivity
        try {
            instrumentation.waitForIdleSync()
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val all = descendants(activity.window.decorView)
            val search = all.filterIsInstance<EditText>().firstOrNull { it.hint?.toString() == "Search product, category or alias" }
            val spinner = all.filterIsInstance<Spinner>().firstOrNull { it.contentDescription?.toString() == "Filter products by category or favourites" }
            assertNotNull("Quick Sale must expose product search", search)
            assertNotNull("Quick Sale must expose category/favourite filtering", spinner)
            val searchField = search!!
            val filters = spinner!!
            instrumentation.runOnMainSync { searchField.setText(alias.uppercase(java.util.Locale.ROOT)) }
            instrumentation.waitForIdleSync()
            var visible = descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString().contains(productName) }
            assertTrue("Search must match the configured alias case-insensitively", visible)
            val categoryIndex = (0 until filters.count).first { filters.getItemAtPosition(it).toString() == "Test Category $token" }
            instrumentation.runOnMainSync { filters.setSelection(categoryIndex) }
            instrumentation.waitForIdleSync()
            visible = descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString().contains(productName) }
            assertTrue("Category filter must keep matching products visible", visible)
            val favouriteIndex = (0 until filters.count).first { filters.getItemAtPosition(it).toString() == "Favourites" }
            instrumentation.runOnMainSync { filters.setSelection(favouriteIndex) }
            instrumentation.waitForIdleSync()
            visible = descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString().contains(productName) }
            assertTrue("Favourite filter must keep the configured favourite product visible", visible)
            instrumentation.runOnMainSync { searchField.setText("no_match_$token") }
            instrumentation.waitForIdleSync()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "No products match this search and filter." })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun productScannerInputAddsExactProductAndCheckoutPostsCanonicalSale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val id = "scan_ui_$token"
        val name = "Scanner Tea $token"
        val code = "EAN-$token"
        val setup = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(setup.saveProduct(TapriProduct(id, name, "Scanner Test", 1250, priceConfirmed = true, scanCode = code)).startsWith("Product saved"))
        assertTrue(setup.confirmOpeningStock(id, 2, "scanner UI fixture").startsWith("Opening stock confirmed"))
        setup.close()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, QuickSaleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QuickSaleActivity
        try {
            instrumentation.waitForIdleSync()
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val scanInput = descendants(activity.window.decorView).filterIsInstance<EditText>()
                .firstOrNull { it.hint?.toString() == "Scan barcode / QR with paired keyboard scanner" }
            assertNotNull("Quick Sale must expose the scanner keyboard-wedge field", scanInput)
            instrumentation.runOnMainSync {
                scanInput!!.setText("  $code  ")
                scanInput.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            instrumentation.waitForIdleSync()
            var views = descendants(activity.window.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("1 × $name") })
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("Scanned $name") })

            instrumentation.runOnMainSync {
                scanInput!!.setText("unknown-$token")
                scanInput.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            instrumentation.waitForIdleSync()
            views = descendants(activity.window.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("No unique active product matches this code") })
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("1 × $name") })
            val check = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            assertTrue("Scanning selects cart items only; checkout alone commits a sale", check.allOrders().isEmpty())
            check.close()

            val checkout = descendants(activity.window.decorView).filterIsInstance<Button>()
                .firstOrNull { it.text.toString().startsWith("Checkout") }
            assertNotNull("Scanner cart should use the normal checkout", checkout)
            instrumentation.runOnMainSync { checkout!!.performClick() }
            instrumentation.waitForIdleSync()
            fun awaitNode(prefix: String): AccessibilityNodeInfo {
                repeat(80) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    find(root)?.let { return it }
                    Thread.sleep(100)
                }
                throw AssertionError("Checkout item did not appear: $prefix")
            }
            val cashOption = awaitNode("Cash received")
            val cashBounds = android.graphics.Rect().also(cashOption::getBoundsInScreen)
            assertTrue("Cash option should have visible bounds: $cashBounds", cashBounds.width() > 0 && cashBounds.height() > 0)
            val tapX = cashBounds.centerX().toFloat()
            val tapY = cashBounds.centerY().toFloat()
            val downTime = android.os.SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, tapX, tapY, 0))
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis() + 80, android.view.MotionEvent.ACTION_UP, tapX, tapY, 0))
            instrumentation.waitForIdleSync()

            val posted = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
            val sale = posted.allOrders().single()
            assertTrue(sale.salePosted)
            assertEquals(1250, sale.salePaise)
            assertEquals(id, sale.lines.single().productId)
            assertEquals(Payment.STAFF_DECLARED, sale.payment)
            assertEquals(1, posted.stockLevels()[id])
            assertEquals("staff_declared", posted.paymentHistory().single { it.method == "cash" }.status)
            posted.close()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }

    @Test fun schemaV26ToV27AddsScannerCodeWithoutChangingOrdersOrStock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        var model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("scan_migration_tea", "Scanner Migration Tea", "Test", 1500, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("scan_migration_tea", 4, "v26 migration fixture").startsWith("Opening stock confirmed"))
        assertTrue(model.quickSale(listOf("scan_migration_tea" to 1), "cash", commandId = "scan-v26-sale").startsWith("Quick sale"))
        model.close()

        val old = SqliteCounterPersistence(context)
        old.writableDatabase.execSQL("DROP INDEX product_catalog_scan_code_unique")
        old.writableDatabase.execSQL("ALTER TABLE product_catalog DROP COLUMN scan_code")
        old.writableDatabase.version = 26
        old.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence.DATABASE_VERSION)
        assertEquals(27, SqliteCounterPersistence(context).use { it.readableDatabase.version })
        assertNull(model.products().single { it.productId == "scan_migration_tea" }.scanCode)
        assertEquals(1, model.allOrders().size)
        assertEquals(1500, model.allOrders().single().salePaise)
        assertEquals(3, model.stockLevels()["scan_migration_tea"])
        assertTrue(model.saveProduct(model.products().single { it.productId == "scan_migration_tea" }.copy(scanCode = "MIG-TEA-01")).startsWith("Product saved"))
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals("MIG-TEA-01", reopened.products().single { it.productId == "scan_migration_tea" }.scanCode)
        assertEquals(1, reopened.allOrders().size)
        assertEquals(3, reopened.stockLevels()["scan_migration_tea"])
        reopened.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    @Test fun syncEnvelopeMapperCreatesBackendCompatibleRequestWithRevision() {
        val identity = SyncEnvelopeIdentity(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333"
        )
        val event = SyncOutboxEvent("44444444-4444-4444-8444-444444444444", "sale-command-1", 1,
            "{\"kind\":\"projection_delta.v1\",\"orders\":[],\"stock\":[{\"productId\":\"tea\",\"quantity\":2}],\"stockMovements\":[],\"customerCredits\":[],\"repayments\":[],\"paymentEntries\":[]}")
        val secondEvent = event.copy(eventId = "55555555-5555-4555-8555-555555555555", commandId = "sale-command-2")
        val mapped = org.json.JSONObject(SyncEnvelopeMapper.batch(identity, 7L, listOf(event, secondEvent))!!)
        assertEquals(identity.shopId, mapped.getString("shopId"))
        val envelope = mapped.getJSONArray("events").getJSONObject(0)
        assertEquals("business.command.v1", envelope.getString("type"))
        assertEquals(1, envelope.getInt("schemaVersion"))
        assertEquals(identity.businessId, envelope.getString("businessId"))
        assertEquals(identity.shopId, envelope.getString("shopId"))
        assertEquals(identity.deviceId, envelope.getString("deviceId"))
        assertEquals(7L, envelope.getLong("baseRevision"))
        assertEquals(event.commandId, envelope.getString("commandId"))
        assertEquals("projection_delta.v1", envelope.getJSONObject("payload").getString("kind"))
        assertEquals(8L, mapped.getJSONArray("events").getJSONObject(1).getLong("baseRevision"))
        assertNull(SyncEnvelopeMapper.batch(identity, Long.MAX_VALUE, listOf(event, secondEvent)))
        assertNull(SyncEnvelopeMapper.batch(identity, 7L, listOf(event, event)))
    }

    @Test fun configuredSalesRequireAuditedPhysicalOpeningCountAndPersistIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        var failOnce = true
        val persistence = SqliteCounterPersistence(context, CounterPersistenceFaultInjector {
            if (failOnce) { failOnce = false; throw IllegalStateException("synthetic stock count commit failure") }
        })
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1200, true, true, true)).startsWith("Product saved"))
        assertEquals(false, model.isStockConfirmed("cutting_chai"))
        assertTrue(model.quickSale(listOf("cutting_chai" to 1), "cash", commandId = "before-count").contains("Opening stock has not been physically confirmed"))
        assertTrue(model.allOrders().isEmpty())
        assertTrue(model.confirmOpeningStock("cutting_chai", 3, "physical count", "opening-count-1").startsWith("Could not save this action locally"))
        assertEquals(false, model.isStockConfirmed("cutting_chai"))
        assertEquals(0, model.stockLevels()["cutting_chai"])
        assertTrue(model.confirmOpeningStock("cutting_chai", 3, "physical count", "opening-count-1").contains("3 units"))
        assertEquals(true, model.isStockConfirmed("cutting_chai"))
        assertEquals(3, model.stockLevels()["cutting_chai"])
        assertTrue(model.stockMovements().single().let { it.quantityDelta == 3 && it.reason.startsWith("opening_count:") })
        assertTrue(model.quickSale(listOf("cutting_chai" to 2), "cash", commandId = "after-count").startsWith("Quick sale"))
        model.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(true, reopened.isStockConfirmed("cutting_chai"))
        assertEquals(1, reopened.stockLevels()["cutting_chai"])
        assertEquals(1, reopened.localBusinessSummary().stockConfirmedProductCount)
        assertEquals(1, reopened.stockMovements().count { it.reason.startsWith("opening_count:") })
        assertEquals("Duplicate command ignored.", reopened.confirmOpeningStock("cutting_chai", 3, "physical count", "opening-count-1"))
        assertEquals(1, reopened.stockLevels()["cutting_chai"])
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }


    @Test fun shopSetupIsSavedInEncryptedMetadataAndRespectsRecoveryWriteBarrier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        val profile = ShopProfile("Shree Tea Corner", "tea_snacks", "mr-IN", "INR")
        assertTrue(model.saveShopProfile(profile).startsWith("Shop setup saved"))
        val blocked = BusinessWriteBarrier.duringMaintenance("test recovery") {
            model.saveShopProfile(profile.copy(shopName = "Must Not Save"))
        }
        assertTrue(blocked.contains("could not be saved"))
        model.close()

        val reopenedPersistence = SqliteCounterPersistence(context)
        val reopened = CounterModel(reopenedPersistence)
        assertEquals(profile, reopened.shopProfile())
        assertTrue(reopened.allOrders().isEmpty())
        assertEquals(0, reopened.creditBalance("any-customer"))
        assertEquals(1L, reopenedPersistence.readableDatabase.rawQuery("SELECT COUNT(*) FROM audit_events WHERE event LIKE 'shop.profile_updated%'", null).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(0L, reopenedPersistence.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE command_id LIKE 'shop-profile-%' AND status='pending'", null).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(1L, reopenedPersistence.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE command_id LIKE 'shop-profile-%' AND status='blocked_contract' AND blocked_reason='shop_settings_contract_pending'", null).use { it.moveToFirst(); it.getLong(0) })
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }
    @Test fun partialItemRefundCreditReversalAndLedgerSurviveRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")

        val first = CounterModel(SqliteCounterPersistence(context), setOf("samosa"))
        assertTrue(first.confirmOpeningStock("cutting_chai", 100, "synthetic initial count", "partial-stock-chai").contains("100 units"))
        assertTrue(first.confirmOpeningStock("samosa", 100, "synthetic initial count", "partial-stock-samosa").contains("100 units"))
        first.apply("partial-create", ProposedCommand("create", null, "one cutting chai", "cutting_chai", 1))
        val order = first.allOrders().single().number
        first.apply("partial-add", ProposedCommand("add", order, "one samosa", "samosa", 1, expectedRevision = first.allOrders().single().revision))
        first.apply("partial-ready", ProposedCommand("ready", order, "ready", expectedRevision = first.allOrders().single().revision))
        first.apply("partial-given", ProposedCommand("given", order, "given", expectedRevision = first.allOrders().single().revision))
        first.recordCash(order, 1000, "partial-cash", first.allOrders().single().revision)
        first.recordUdhar(order, "return-customer", "partial-credit", 2000, first.allOrders().single().revision)
        first.repayCredit("return-customer", 500, "partial-repayment")
        assertTrue(first.refundItems(order, mapOf("samosa" to 1), "returned sealed item", mapOf("samosa" to "restock"), true, "partial-refund", first.allOrders().single().revision).contains("Refund"))
        assertEquals(1000, first.allOrders().single().paidPaise)
        assertEquals(0, first.creditBalance("return-customer"))
        first.close()

        val reopened = CounterModel(SqliteCounterPersistence(context), setOf("samosa"))
        val restored = reopened.allOrders().single()
        assertEquals(2000, restored.refundedPaise)
        assertEquals(500, restored.cashRefundedPaise)
        assertEquals(1500, restored.creditReversedPaise)
        assertEquals(1, restored.lines.single { it.productId == "samosa" }.refundedQuantity)
        assertEquals(0, reopened.creditBalance("return-customer"))
        assertEquals(1500, reopened.customerCredits().single().reversedPaise)
        assertEquals(-500, reopened.paymentHistory().single { it.kind == "cash_refund" }.amountPaise)
        assertEquals(-1500, reopened.paymentHistory().single { it.kind == "credit_reversal" }.amountPaise)
        assertEquals(100, reopened.stockLevels()["samosa"])
        assertEquals("Duplicate command ignored.", reopened.refundItems(restored.number, mapOf("samosa" to 1), "returned sealed item", mapOf("samosa" to "restock"), true, "partial-refund", restored.revision))
        assertEquals(100, reopened.stockLevels()["samosa"])
        reopened.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun failedSQLiteCommitRollsBackProjectionReceiptAndOutbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        var failOnce = true
        val persistence = SqliteCounterPersistence(context, CounterPersistenceFaultInjector {
            if (failOnce) { failOnce = false; throw IllegalStateException("synthetic mid-transaction failure") }
        })
        val model = CounterModel(persistence)
        assertTrue(model.apply("atomic-create", ProposedCommand("create", null, "create", "chai", 1)).startsWith("Could not save"))
        assertTrue(model.allOrders().isEmpty())
        assertEquals(0, model.stockLevels()["chai"])
        val reopenedAfterRollback = CounterModel(SqliteCounterPersistence(context))
        assertTrue(reopenedAfterRollback.allOrders().isEmpty())
        assertTrue(reopenedAfterRollback.apply("atomic-create", ProposedCommand("create", null, "create", "chai", 1)).contains("Order #12"))
        val reopenedAfterCommit = CounterModel(SqliteCounterPersistence(context))
        assertEquals("Duplicate command ignored.", reopenedAfterCommit.apply("atomic-create", ProposedCommand("create", null, "create", "chai", 1)))
        assertEquals(0, reopenedAfterCommit.stockLevels()["chai"])
        reopenedAfterRollback.close(); reopenedAfterCommit.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun newDatabaseIsCiphertextAndMissingDeviceKeyFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        model.apply("cipher-test-create", ProposedCommand("create", null, "one chai", "chai", 1))
        model.close()
        val databaseFile = context.getDatabasePath("chai_tapri_counter.db")
        assertTrue(databaseFile.exists())
        assertTrue(!SecureDatabaseKeyManager.isLegacyPlaintextDatabase(databaseFile))
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(databaseFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { plainDb ->
                plainDb.rawQuery("SELECT name FROM sqlite_master", null).use { it.moveToFirst() }
            }
            throw AssertionError("Platform SQLite unexpectedly opened SQLCipher data as plaintext")
        } catch (_: android.database.sqlite.SQLiteException) { }

        val isolatedFile = java.io.File(context.cacheDir, "key-loss-test-${java.util.UUID.randomUUID()}.db")
        val namespace = "key-loss-instrumentation-${java.util.UUID.randomUUID()}"
        val manager = SecureDatabaseKeyManager(context, isolatedFile, namespace)
        manager.getOrCreatePassphrase().fill(0)
        isolatedFile.writeBytes(ByteArray(64) { (it + 1).toByte() })
        SecureDatabaseKeyManager.removeWrappingKeyForTests(namespace)
        try {
            SecureDatabaseKeyManager(context, isolatedFile, namespace).getOrCreatePassphrase()
            throw AssertionError("Missing Keystore key must fail closed")
        } catch (_: DatabaseKeyUnavailableException) { }
        isolatedFile.delete()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun authenticatedBackupCanRecoverSameShopAfterDeviceKeyLossWithoutDiscardingCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "key-lost-recovery.db"
        val namespace = "key-lost-recovery-instrumentation-${java.util.UUID.randomUUID()}"
        context.deleteDatabase(databaseName)
        val original = CounterModel(SqliteCounterPersistence(context, databaseName, namespace))
        assertTrue(original.confirmOpeningStock("chai", 10, "synthetic key-loss fixture", "key-loss-stock").contains("10 units"))
        original.apply("key-loss-order", ProposedCommand("create", null, "one chai", "chai", 1))
        val orderNo = original.allOrders().single().number
        assertTrue(original.apply("key-loss-ready", ProposedCommand("ready", orderNo, "ready", expectedRevision = original.allOrders().single().revision)).contains("ready"))
        assertTrue(original.apply("key-loss-given", ProposedCommand("given", orderNo, "given", expectedRevision = original.allOrders().single().revision)).contains("given"))
        assertTrue(original.recordCash(orderNo, 1500, "key-loss-cash", original.allOrders().single().revision).contains("Cash declared"))
        original.close()

        val secret = "key-loss-recovery-2026".toCharArray()
        val output = java.io.ByteArrayOutputStream()
        PortableSnapshotBackup(context, databaseName, namespace).export(output, secret.copyOf())
        val identity = SqliteCounterPersistence(context, databaseName, namespace).use { it.shopIdentity() }
        val databaseFile = context.getDatabasePath(databaseName)
        val lostCiphertext = databaseFile.readBytes()
        SecureDatabaseKeyManager.removeWrappingKeyForTests(namespace)

        // A mismatched local binding must reject before replacing or archiving the locked database.
        context.getSharedPreferences("chai_tapri_shop_identity_guard", android.content.Context.MODE_PRIVATE)
            .edit().putString("$namespace:$databaseName", java.util.UUID.randomUUID().toString()).commit()
        try {
            PortableSnapshotBackup(context, databaseName, namespace).restore(output.toByteArray().inputStream(), secret.copyOf(), true)
            throw AssertionError("A backup with a different local recovery binding must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("local recovery binding"))
        }
        assertTrue(databaseFile.readBytes().contentEquals(lostCiphertext))
        assertTrue(context.databaseList().none { it.startsWith("$databaseName.key-lost-") })

        context.getSharedPreferences("chai_tapri_shop_identity_guard", android.content.Context.MODE_PRIVATE)
            .edit().putString("$namespace:$databaseName", identity).commit()
        val oldEnvelope = SecureDatabaseKeyManager(context, databaseFile, namespace).envelopeSnapshot()
        try {
            PortableSnapshotBackup(context, databaseName, namespace, BackupFaultInjector { point ->
                if (point == BackupFaultPoint.AFTER_DATABASE_REPLACED) throw java.io.IOException("synthetic failure after key switch")
            }).restore(output.toByteArray().inputStream(), secret.copyOf(), true)
            throw AssertionError("Post-switch failure must roll back the database and key envelope")
        } catch (_: IllegalStateException) { }
        assertTrue("Rollback must restore the original ciphertext byte-for-byte", databaseFile.readBytes().contentEquals(lostCiphertext))
        assertEquals(oldEnvelope?.alias, SecureDatabaseKeyManager(context, databaseFile, namespace).envelopeSnapshot()?.alias)

        PortableSnapshotBackup(context, databaseName, namespace).restore(output.toByteArray().inputStream(), secret.copyOf(), true)
        val restored = CounterModel(SqliteCounterPersistence(context, databaseName, namespace))
        assertEquals(1, restored.allOrders().size)
        assertEquals(1500, restored.allOrders().single().paidPaise)
        assertEquals("Duplicate command ignored.", restored.apply("key-loss-given", ProposedCommand("given", orderNo, "given")))
        restored.close()
        val archives = context.databaseList().filter { it.startsWith("$databaseName.key-lost-") }
        assertEquals("The prior unreadable encrypted database must remain archived", 1, archives.size)
        assertTrue(context.getDatabasePath(archives.single()).exists())
        secret.fill('\u0000')
        context.deleteDatabase(databaseName)
        archives.forEach(context::deleteDatabase)
        context.getSharedPreferences("chai_tapri_shop_identity_guard", android.content.Context.MODE_PRIVATE)
            .edit().remove("$namespace:$databaseName").commit()
    }

    @Test fun corruptLegacyDatabaseIsPreservedAndNeverReplacedWithAnEmptyStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val file = context.getDatabasePath("chai_tapri_counter.db")
        val corruptBytes = "SQLite format 3\u0000synthetic-corruption".toByteArray(Charsets.US_ASCII)
        file.writeBytes(corruptBytes)
        try {
            SqliteCounterPersistence(context)
            throw AssertionError("Corrupt legacy database must fail migration")
        } catch (_: Exception) { }
        assertTrue(file.exists())
        assertTrue(file.readBytes().contentEquals(corruptBytes))
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun portableEncryptedBackupRestoresFinancialSnapshotAndRejectsBadOrRepeatedImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        assertTrue(model.confirmOpeningStock("chai", 100, "synthetic initial count", "backup-opening-stock").contains("100 units"))
        model.apply("backup-create", ProposedCommand("create", null, "one chai", "chai", 1))
        val orderNumber = model.allOrders().single().number
        model.apply("backup-ready", ProposedCommand("ready", orderNumber, "ready", expectedRevision = model.allOrders().single().revision))
        model.apply("backup-given", ProposedCommand("given", orderNumber, "given", expectedRevision = model.allOrders().single().revision))
        model.recordCash(orderNumber, 1500, "backup-cash", model.allOrders().single().revision)
        model.close()

        val recoverySecret = "tapri-backup-secret-2026".toCharArray()
        val output = java.io.ByteArrayOutputStream()
        PortableSnapshotBackup(context).export(output, recoverySecret)
        val backup = output.toByteArray()
        assertTrue(backup.size > 4096)
        assertTrue(!String(backup, Charsets.ISO_8859_1).contains("SQLite format 3"))
        val throwingOutput = object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("synthetic full destination") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw java.io.IOException("synthetic full destination") }
        }
        try {
            PortableSnapshotBackup(context).export(throwingOutput, "tapri-backup-secret-2026".toCharArray())
            throw AssertionError("Failed backup destination must report a failure")
        } catch (_: java.io.IOException) { }
        assertTrue(!context.getDatabasePath("chai_tapri_counter.db.portable-export").exists())

        try {
            PortableSnapshotBackup(context).restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), false)
            throw AssertionError("Restore must require explicit replacement confirmation")
        } catch (_: IllegalArgumentException) { }
        try {
            PortableSnapshotBackup(context).restore(backup.inputStream(), "incorrect-recovery-secret".toCharArray(), true)
            throw AssertionError("Incorrect recovery secret must be rejected")
        } catch (_: Exception) { }
        val corruptedBackup = backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x5a).toByte() }
        try {
            PortableSnapshotBackup(context).restore(corruptedBackup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("Corrupted portable snapshot must be rejected")
        } catch (_: Exception) { }
        val alteredHeader = backup.copyOf().also { it[40] = (it[40].toInt() xor 0x01).toByte() }
        try {
            PortableSnapshotBackup(context).restore(alteredHeader.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("Altered backup ID must fail header authentication")
        } catch (_: Exception) { }
        val afterBadImport = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, afterBadImport.allOrders().size)
        assertEquals(1500, afterBadImport.allOrders().single().paidPaise)
        afterBadImport.close()

        val postBackupModel = CounterModel(SqliteCounterPersistence(context))
        postBackupModel.apply("after-backup-create", ProposedCommand("create", null, "one samosa", "samosa", 1))
        postBackupModel.close()
        val noSpaceDuringStaging = object : BackupFaultInjector {
            override fun at(point: BackupFaultPoint) = Unit
            override fun wrapImportStaging(output: java.io.OutputStream): java.io.OutputStream = object : java.io.FilterOutputStream(output) {
                private var accepted = 0
                override fun write(value: Int) {
                    if (accepted >= 1024) throw java.io.IOException("synthetic ENOSPC during portable staging")
                    out.write(value); accepted++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    val allowed = (1024 - accepted).coerceAtLeast(0).coerceAtMost(length)
                    if (allowed > 0) { out.write(bytes, offset, allowed); accepted += allowed }
                    if (allowed < length) throw java.io.IOException("synthetic ENOSPC during portable staging")
                }
            }
        }
        try {
            PortableSnapshotBackup(context, SqliteCounterPersistence.DATABASE_NAME, "default", noSpaceDuringStaging)
                .restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("A short write while staging a restore must fail safely")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("ENOSPC"))
        }
        val afterStagingFailure = CounterModel(SqliteCounterPersistence(context))
        assertEquals(2, afterStagingFailure.allOrders().size)
        assertTrue(afterStagingFailure.allOrders().any { it.items.contains("Samosa") })
        afterStagingFailure.close()
        assertTrue(!context.getDatabasePath("chai_tapri_counter.db.portable-import").exists())
        assertTrue(!context.getDatabasePath("chai_tapri_counter.db.restore-staged").exists())
        assertEquals(null, BusinessWriteBarrier.currentMaintenance())
        try {
            PortableSnapshotBackup(context, SqliteCounterPersistence.DATABASE_NAME, "default", BackupFaultInjector { point ->
                if (point == BackupFaultPoint.AFTER_ACTIVE_PRESERVED) throw java.io.IOException("synthetic process interruption after preserving the active database")
            }).restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("Injected replacement interruption must fail")
        } catch (_: java.io.IOException) { }
        val afterInterruptedReplace = CounterModel(SqliteCounterPersistence(context))
        assertEquals("Failed replacement must roll back to the prior shop snapshot", 2, afterInterruptedReplace.allOrders().size)
        assertTrue(afterInterruptedReplace.allOrders().any { it.items.contains("Samosa") })
        afterInterruptedReplace.close()
        try {
            PortableSnapshotBackup(context, SqliteCounterPersistence.DATABASE_NAME, "default", BackupFaultInjector { point ->
                if (point == BackupFaultPoint.AFTER_DATABASE_REPLACED) throw java.io.IOException("synthetic failure during replacement verification")
            }).restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("Post-replacement fault must roll back")
        } catch (_: IllegalStateException) { }
        val afterVerifiedRollback = CounterModel(SqliteCounterPersistence(context))
        assertEquals(2, afterVerifiedRollback.allOrders().size)
        afterVerifiedRollback.close()

        PortableSnapshotBackup(context).restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
        val restored = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, restored.allOrders().size)
        assertEquals(1500, restored.allOrders().single().paidPaise)
        assertEquals(1, restored.paymentHistory().count { it.kind == "order_collection" && it.method == "cash" })
        assertEquals("Duplicate command ignored.", restored.apply("backup-given", ProposedCommand("given", orderNumber, "given")))
        restored.close()
        try {
            PortableSnapshotBackup(context).restore(backup.inputStream(), "tapri-backup-secret-2026".toCharArray(), true)
            throw AssertionError("The same portable snapshot must not be imported twice")
        } catch (_: IllegalArgumentException) { }
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun partialBackupExportFailurePreservesShopDataCleansSnapshotAndReleasesWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = SqliteCounterPersistence.DATABASE_NAME
        context.deleteDatabase(databaseName)
        val model = CounterModel(SqliteCounterPersistence(context))
        assertTrue(model.apply("short-export-original", ProposedCommand("create", null, "one chai", "chai", 1)).contains("Order"))
        val originalOrder = model.allOrders().single()
        model.close()

        val partialArchive = java.io.ByteArrayOutputStream()
        val failingDestination = object : java.io.OutputStream() {
            private var accepted = 0
            override fun write(value: Int) {
                if (accepted >= 96) throw java.io.IOException("synthetic destination failure after partial encrypted archive")
                partialArchive.write(value)
                accepted++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val available = (96 - accepted).coerceAtLeast(0).coerceAtMost(length)
                if (available > 0) {
                    partialArchive.write(bytes, offset, available)
                    accepted += available
                }
                if (available < length) throw java.io.IOException("synthetic destination failure after partial encrypted archive")
            }
        }
        try {
            PortableSnapshotBackup(context).export(failingDestination, "synthetic-export-secret-2026".toCharArray())
            throw AssertionError("A partially written export must fail")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("partial encrypted archive"))
        }

        assertTrue("The failure must occur after an archive prefix was written", partialArchive.size() >= 96)
        assertTrue(!context.getDatabasePath("$databaseName.portable-export").exists())
        assertTrue(!java.io.File(context.getDatabasePath("$databaseName.portable-export").path + "-wal").exists())
        assertEquals(null, BusinessWriteBarrier.currentMaintenance())

        val reopened = CounterModel(SqliteCounterPersistence(context))
        assertEquals(listOf(originalOrder.totalPaise), reopened.allOrders().map { it.totalPaise })
        assertTrue(reopened.apply("short-export-followup", ProposedCommand("create", null, "one samosa", "samosa", 1)).contains("Order"))
        assertEquals(2, reopened.allOrders().size)
        reopened.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun maintenanceBarrierRejectsAndRollsBackBusinessWritesThenReleases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        val model = CounterModel(SqliteCounterPersistence(context))
        val rejected = BusinessWriteBarrier.duringMaintenance("test maintenance") {
            model.apply("barrier-rejected", ProposedCommand("create", null, "new chai", "chai", 1))
        }
        assertTrue(rejected.contains("not recorded"))
        assertTrue(model.allOrders().isEmpty())
        assertEquals(null, BusinessWriteBarrier.currentMaintenance())
        assertTrue(model.apply("barrier-released", ProposedCommand("create", null, "new chai", "chai", 1)).contains("Order"))
        assertEquals(1, model.allOrders().size)
        model.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun restoreJournalRecoversOriginalDatabaseAfterProcessStopsBeforeReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = SqliteCounterPersistence.DATABASE_NAME
        context.deleteDatabase(databaseName)
        val original = CounterModel(SqliteCounterPersistence(context))
        original.apply("journal-crash-original", ProposedCommand("create", null, "one chai", "chai", 1))
        val expectedOrder = original.allOrders().single()
        original.close()

        val active = context.getDatabasePath(databaseName)
        val previous = context.getDatabasePath("$databaseName.pre-restore")
        check(moveDatabaseFamilyForTest(active, previous))
        RestoreRecoveryJournal.markPending(context, databaseName, "synthetic-interrupted-restore")

        // Constructing the normal store runs startup recovery before opening SQLCipher.
        val recovered = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, recovered.allOrders().size)
        assertEquals(expectedOrder.number, recovered.allOrders().single().number)
        assertEquals(expectedOrder.salePaise, recovered.allOrders().single().salePaise)
        assertTrue(recovered.auditEvents().any { it.contains("journal-crash-original") })
        recovered.close()
        assertTrue("Recovery journal must be cleared after a successful rollback", !previous.exists())
        assertEquals(null, RestoreRecoveryJournal.preferences(context).getString("pending_restore:$databaseName", null))
        context.deleteDatabase(databaseName)
    }

    @Test fun restoreJournalFinalizesVerifiedReplacementAfterCommitMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = SqliteCounterPersistence.DATABASE_NAME
        context.deleteDatabase(databaseName)
        val model = CounterModel(SqliteCounterPersistence(context))
        model.apply("journal-commit-order", ProposedCommand("create", null, "two samosa", "samosa", 2))
        val expected = model.allOrders().single()
        model.close()

        val active = context.getDatabasePath(databaseName)
        val previous = context.getDatabasePath("$databaseName.pre-restore")
        active.copyTo(previous, overwrite = false)
        val snapshotId = "synthetic-verified-restore"
        RestoreRecoveryJournal.markPending(context, databaseName, snapshotId)
        RestoreRecoveryJournal.recordCommitted(context, databaseName, snapshotId)

        val recovered = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, recovered.allOrders().size)
        assertEquals(expected.number, recovered.allOrders().single().number)
        assertEquals(expected.salePaise, recovered.allOrders().single().salePaise)
        assertTrue(recovered.auditEvents().any { it.contains("journal-commit-order") })
        recovered.close()
        assertTrue("Committed recovery should remove the redundant previous database", !previous.exists())
        assertEquals(null, RestoreRecoveryJournal.preferences(context).getString("pending_restore:$databaseName", null))
        context.deleteDatabase(databaseName)
    }

    @Test fun restoreJournalRollsBackActivatedDatabaseWhenProcessStopsBeforeCommitMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = SqliteCounterPersistence.DATABASE_NAME
        context.deleteDatabase(databaseName)

        val original = CounterModel(SqliteCounterPersistence(context))
        original.apply("journal-precommit-original", ProposedCommand("create", null, "one chai", "chai", 1))
        val originalOrder = original.allOrders().single()
        original.close()

        val active = context.getDatabasePath(databaseName)
        val existingPassphrase = SecureDatabaseKeyManager(context, active, "default").getOrCreatePassphrase()
        val previous = context.getDatabasePath("$databaseName.pre-restore")
        val interrupted = context.getDatabasePath("$databaseName.restore-interrupted")
        check(moveDatabaseFamilyForTest(active, previous))

        // Simulate the staged snapshot already being activated while its durable journal
        // still has no committed marker, the state left by abrupt process death at cutover.
        val replacementStore = SqliteCounterPersistence(
            context, existingPassphrase.copyOf(), databaseName, context.applicationContext,
            false, CounterPersistenceFaultInjector { }
        )
        existingPassphrase.fill(0)
        val replacement = CounterModel(replacementStore)
        replacement.apply("journal-precommit-replacement", ProposedCommand("create", null, "two samosa", "samosa", 2))
        replacement.close()
        assertTrue(active.exists())
        assertTrue(previous.exists())
        RestoreRecoveryJournal.markPending(context, databaseName, "synthetic-stopped-before-commit")

        val recovered = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, recovered.allOrders().size)
        assertEquals(originalOrder.number, recovered.allOrders().single().number)
        assertEquals(originalOrder.salePaise, recovered.allOrders().single().salePaise)
        assertTrue(recovered.auditEvents().any { it.contains("journal-precommit-original") })
        recovered.close()

        assertTrue("Uncommitted replacement must be preserved for diagnosis", interrupted.exists())
        assertTrue("Original encrypted store must become active again", active.exists())
        assertTrue("Previous-store staging file should be consumed after rollback", !previous.exists())
        assertEquals(null, RestoreRecoveryJournal.preferences(context).getString("pending_restore:$databaseName", null))
        context.deleteDatabase(databaseName)
        interrupted.delete()
        java.io.File(interrupted.path + "-wal").delete()
        java.io.File(interrupted.path + "-shm").delete()
        java.io.File(interrupted.path + "-journal").delete()
    }

    private fun moveDatabaseFamilyForTest(from: java.io.File, to: java.io.File): Boolean {
        val moved = mutableListOf<Pair<java.io.File, java.io.File>>()
        for (suffix in listOf("-wal", "-shm", "-journal", "")) {
            val source = java.io.File(from.path + suffix)
            if (!source.exists()) continue
            val destination = java.io.File(to.path + suffix)
            if (!source.renameTo(destination)) {
                moved.asReversed().forEach { (old, new) -> new.renameTo(old) }
                return false
            }
            moved += source to destination
        }
        return true
    }

    @Test fun verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sourceContext = instrumentation.targetContext
        val isolatedContext = sourceContext
        val isolatedDatabase = "cat051_clean_install.db"
        val isolatedNamespace = "cat051-clean-install"
        sourceContext.deleteDatabase("chai_tapri_counter.db")
        isolatedContext.deleteDatabase(isolatedDatabase)

        val source = CounterModel(SqliteCounterPersistence(sourceContext), setOf("chai"))
        assertTrue(source.saveCustomerProfile(CustomerProfile("pilot-customer", "Synthetic Customer", "+919900112233", true)).contains("saved"))
        assertTrue(source.confirmOpeningStock("chai", 100, "synthetic initial count", "clean-stock-chai").contains("100 units"))
        assertTrue(source.confirmOpeningStock("samosa", 100, "synthetic initial count", "clean-stock-samosa").contains("100 units"))
        source.apply("clean-create-1", ProposedCommand("create", null, "two chai", "chai", 2))
        val firstOrder = source.allOrders().single().number
        source.apply("clean-ready-1", ProposedCommand("ready", firstOrder, "ready", expectedRevision = source.allOrders().single().revision))
        source.apply("clean-given-1", ProposedCommand("given", firstOrder, "given", expectedRevision = source.allOrders().single().revision))
        source.recordCash(firstOrder, 1000, "clean-cash-1", source.allOrders().single().revision)
        source.recordUdhar(firstOrder, "pilot-customer", "clean-credit-1", 2000, source.allOrders().single().revision)
        source.repayCredit("pilot-customer", 500, "clean-repay-1")
        source.refundItems(firstOrder, mapOf("chai" to 1), "sealed return", mapOf("chai" to "restock"), true, "clean-refund-1", source.allOrders().single().revision)
        source.apply("clean-create-2", ProposedCommand("create", null, "one samosa", "samosa", 1))
        val secondOrder = source.allOrders().first().number
        source.apply("clean-ready-2", ProposedCommand("ready", secondOrder, "ready", expectedRevision = source.allOrders().first().revision))
        source.apply("clean-given-2", ProposedCommand("given", secondOrder, "given", expectedRevision = source.allOrders().first().revision))
        source.apply("clean-upi-pending-2", ProposedCommand("upi", secondOrder, "UPI pending", expectedRevision = source.allOrders().first().revision))
        assertTrue(source.startStaffShift("Synthetic morning", 5_000, "clean-drawer-open").contains("Shift started"))
        assertTrue(source.quickSale(listOf("chai" to 1), "cash", commandId = "clean-drawer-sale").startsWith("Quick sale"))
        assertTrue(source.endStaffShift(6_200, "Synthetic evening", "synthetic recount follow-up", "clean-drawer-close").contains("unresolved variance"))
        assertTrue(source.recordCashClosingReview("clean-drawer-open", "synthetic recount follow-up", "clean-drawer-review").contains("review recorded"))
        assertTrue(source.recordExpense("utilities", 2_550, "upi", "synthetic recovery expense", "clean-expense-1").startsWith("Expense recorded"))
        val sourceExpenseTime = source.expenseHistory().single().recordedAtEpochMs
        assertNotNull(sourceExpenseTime)
        source.close()

        val recoverySecret = "separate-sandbox-recovery-2026".toCharArray()
        val backupBytes = java.io.ByteArrayOutputStream()
        PortableSnapshotBackup(sourceContext).export(backupBytes, recoverySecret.copyOf())
        val portable = backupBytes.toByteArray()
        if (InstrumentationRegistry.getArguments().getString("cat051b.saveRichBackup") == "true") {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "cat051b-full-synthetic-${System.currentTimeMillis()}.ctbackup")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = sourceContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the synthetic recovery-drill backup in Downloads.")
            try {
                sourceContext.contentResolver.openOutputStream(uri, "w")!!.use { it.write(portable) }
                sourceContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                System.out.println("CAT-051B rich synthetic snapshot saved to $uri")
            } catch (failure: Throwable) {
                sourceContext.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
        val verified = PortableSnapshotBackup(isolatedContext, isolatedDatabase, isolatedNamespace).verify(portable.inputStream(), recoverySecret.copyOf())
        assertEquals(SqliteCounterPersistence.DATABASE_VERSION, verified.schemaVersion)
        PortableSnapshotBackup(isolatedContext, isolatedDatabase, isolatedNamespace).restore(portable.inputStream(), recoverySecret.copyOf(), true)

        val restoredStore = SqliteCounterPersistence(isolatedContext, isolatedDatabase, isolatedNamespace)
        val restored = CounterModel(restoredStore, setOf("chai"))
        assertEquals(3, restored.allOrders().size)
        val restoredSale = restored.allOrders().single { it.number == firstOrder }
        assertEquals(1500, restoredSale.paidPaise)
        assertEquals(1500, restoredSale.creditReversedPaise)
        assertEquals(1, restoredSale.lines.single { it.productId == "chai" }.refundedQuantity)
        assertEquals(0, restored.creditBalance("pilot-customer"))
        assertEquals("Synthetic Customer", restored.customerProfiles().single { it.customerId == "pilot-customer" }.displayName)
        assertEquals("+919900112233", restored.customerProfiles().single { it.customerId == "pilot-customer" }.phone)
        assertEquals(1, restored.creditRepayments().size)
        assertEquals(2_550, restored.expenseHistory().single().amountPaise)
        assertEquals(sourceExpenseTime, restored.expenseHistory().single().recordedAtEpochMs)
        assertEquals(1, restored.paymentHistory().count { it.kind == "credit_reversal" })
        assertEquals(1, restored.paymentHistory().count { it.method == "upi" && it.status == "pending" })
        assertEquals(1, restored.stockMovements().count { it.reason == "sale_refund_restock" })
        assertEquals(98, restored.stockLevels()["chai"])
        assertEquals(6_500L, restored.expectedCashPaise("clean-drawer-open"))
        assertEquals(6_200, restored.cashDrawerHistory().first().closingCashPaise)
        assertEquals("Synthetic morning", restored.cashDrawerHistory().first().staffLabel)
        assertEquals("Synthetic evening", restored.cashDrawerHistory().first().handoverToLabel)
        assertEquals("synthetic recount follow-up", restored.cashDrawerHistory().first().handoverNote)
        assertEquals("synthetic recount follow-up", restored.closingReview("clean-drawer-open")?.note)
        assertEquals(1, restored.sessionPaymentEntries("clean-drawer-open").count { it.kind == "order_collection" && it.amountPaise == 1500 })
        assertTrue(restored.auditEvents().any { it.contains("clean-drawer-review") })
        assertTrue(restored.auditEvents().any { it.contains("clean-refund-1") })
        restoredStore.readableDatabase.use { db ->
            fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(count("command_receipts"), count("sync_outbox"))
            assertEquals(restored.auditEvents().size, count("audit_events"))
            assertEquals(restored.paymentHistory().size, count("payment_entries"))
        }
        assertEquals("Duplicate command ignored.", restored.apply("clean-given-1", ProposedCommand("given", firstOrder, "given")))
        restored.close()

        val sourceKey = SecureDatabaseKeyManager(sourceContext, sourceContext.getDatabasePath("chai_tapri_counter.db")).getOrCreatePassphrase()
        val restoredKey = SecureDatabaseKeyManager(isolatedContext, isolatedContext.getDatabasePath(isolatedDatabase), isolatedNamespace).getOrCreatePassphrase()
        assertTrue(!sourceKey.contentEquals(restoredKey))
        sourceKey.fill(0); restoredKey.fill(0); recoverySecret.fill('\u0000')
        sourceContext.deleteDatabase("chai_tapri_counter.db")
        isolatedContext.deleteDatabase(isolatedDatabase)
    }

    @Test fun authenticatedBackupFromAnotherShopCannotReplaceExistingBusinessData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")

        val source = CounterModel(SqliteCounterPersistence(context))
        source.apply("identity-source-order", ProposedCommand("create", null, "one chai", "chai", 1))
        source.close()
        val sourceIdentity = SqliteCounterPersistence(context).use { it.shopIdentity() }
        val secret = "shop-identity-recovery-2026".toCharArray()
        val backup = java.io.ByteArrayOutputStream()
        PortableSnapshotBackup(context).export(backup, secret.copyOf())

        context.deleteDatabase("chai_tapri_counter.db")
        val otherShop = CounterModel(SqliteCounterPersistence(context))
        otherShop.apply("identity-other-order", ProposedCommand("create", null, "one samosa", "samosa", 1))
        otherShop.close()
        val otherIdentity = SqliteCounterPersistence(context).use { it.shopIdentity() }
        assertTrue(sourceIdentity != otherIdentity)

        try {
            PortableSnapshotBackup(context).restore(backup.toByteArray().inputStream(), secret.copyOf(), true)
            throw AssertionError("An authenticated backup from another shop must not replace active business records")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("different or unverified shop identity"))
        }
        val afterRejectedRestore = CounterModel(SqliteCounterPersistence(context))
        assertEquals(1, afterRejectedRestore.allOrders().size)
        assertTrue(afterRejectedRestore.allOrders().single().items.contains("Samosa"))
        afterRejectedRestore.close()
        secret.fill('\u0000')
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun orderStockAuditAndIdempotencySurviveStoreRecreationAndV1Migration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("chai_tapri_counter.db")
        createVersionOneStore(context)

        val first = CounterModel(SqliteCounterPersistence(context), setOf("cutting_chai"))
        val outboxStore = SqliteCounterPersistence(context)
        outboxStore.readableDatabase.rawQuery("SELECT event_id,command_id,status,payload_version,payload_json,blocked_reason FROM sync_outbox WHERE command_id='legacy-command-1'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(java.util.UUID.fromString(cursor.getString(0)).toString(), cursor.getString(0))
            assertEquals("legacy-command-1", cursor.getString(1))
            assertEquals("blocked_contract", cursor.getString(2))
            assertTrue(cursor.isNull(3)); assertTrue(cursor.isNull(4))
            assertEquals("legacy_payload_unavailable", cursor.getString(5))
        }
        outboxStore.close()
        first.apply("sqlite-create", first.parse("Naya order, do cutting chai")!!)
        first.apply("sqlite-ready", first.parse("Order 12 ready hai")!!)
        first.apply("sqlite-given", first.parse("Order 12 de diya")!!)
        first.apply("sqlite-cash", first.parse("Order 12 ka 20 cash mila")!!)
        first.apply("sqlite-create-13", first.parse("New order, one samosa")!!)
        first.apply("sqlite-ready-13", first.parse("Order 13 ready hai")!!)
        first.apply("sqlite-given-13", first.parse("Order 13 de diya")!!)
        first.apply("sqlite-upi-13", first.parse("Order 13 UPI pending")!!)
        first.refundOrder(13, "cancel uncollected UPI attempt", "not_restocked", false, "sqlite-refund-13")
        first.apply("sqlite-create-14", first.parse("New order, one chai")!!)
        first.apply("sqlite-ready-14", first.parse("Order 14 ready hai")!!)
        first.apply("sqlite-given-14", first.parse("Order 14 de diya")!!)
        first.recordUdhar(14,"customer-1","sqlite-udhar-14")
        first.repayCredit("customer-1",500,"sqlite-repay-14")
        first.apply("sqlite-create-15", first.parse("New order, do cutting chai")!!)
        first.apply("sqlite-ready-15", first.parse("Order 15 ready hai")!!)
        first.apply("sqlite-given-15", first.parse("Order 15 de diya")!!)
        first.recordCash(15,1200,"sqlite-cash-15")
        first.recordUdhar(15,"customer-2","sqlite-credit-15",800)
        first.repayCredit("customer-2",300,"sqlite-repay-15")
        assertTrue(first.refundOrder(15,"customer return","restock",true,"sqlite-refund-15").contains("Refund"))
        assertEquals(98, first.stockLevels()["cutting_chai"])
        assertEquals(99, first.stockLevels()["samosa"])
        assertEquals(99, first.stockLevels()["chai"])
        first.close()

        val reopened = CounterModel(SqliteCounterPersistence(context))
        assertEquals(5, reopened.allOrders().size)
        assertEquals(Fulfillment.GIVEN, reopened.allOrders().single { it.number == 11 }.fulfillment)
        val cashOrder = reopened.allOrders().single { it.number == 12 }
        val upiOrder = reopened.allOrders().single { it.number == 13 }
        val creditOrder = reopened.allOrders().single { it.number == 14 }
        val refundedOrder = reopened.allOrders().single { it.number == 15 }
        assertEquals(Fulfillment.GIVEN, cashOrder.fulfillment)
        assertEquals(Payment.STAFF_DECLARED, cashOrder.payment)
        assertEquals(Fulfillment.GIVEN, upiOrder.fulfillment)
        assertEquals(Payment.CANCELLED, upiOrder.payment)
        assertEquals(Payment.SPLIT,creditOrder.payment)
        assertEquals(Payment.REFUNDED,refundedOrder.payment)
        assertTrue(refundedOrder.voided)
        assertEquals(2000,refundedOrder.refundedPaise)
        assertEquals(98, reopened.stockLevels()["cutting_chai"])
        assertEquals(99, reopened.stockLevels()["samosa"])
        assertEquals(5, reopened.stockMovements().size)
        assertEquals(1000,reopened.creditBalance("customer-1"))
        assertEquals(0,reopened.creditBalance("customer-2"))
        assertEquals(2,reopened.creditRepayments().size)
        assertEquals(5, reopened.paymentHistory().count { it.method == "cash" })
        assertEquals(2, reopened.paymentHistory().count { it.method == "upi" })
        assertEquals(3, reopened.paymentHistory().count { it.method == "udhar" })
        assertEquals("pending", reopened.paymentHistory().single { it.method == "upi" && it.status == "pending" }.status)
        val cancelledAttempt = reopened.paymentHistory().single { it.kind == "payment_attempt_cancelled" }
        assertEquals(reopened.paymentHistory().single { it.method == "upi" && it.status == "pending" }.entryId, cancelledAttempt.relatedEntryId)
        assertEquals(10, reopened.paymentHistory().size)
        assertTrue(reopened.auditEvents().any { it.contains("order.created") })
        assertEquals("Duplicate command ignored.", reopened.apply("sqlite-given", reopened.parse("Order 12 de diya")!!))
        assertEquals(98, reopened.stockLevels()["cutting_chai"])
        reopened.close()

        val strictAfterLegacyUpgrade = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        val preservedLegacyCount = strictAfterLegacyUpgrade.stockLevels()["cutting_chai"]
        assertEquals(false, strictAfterLegacyUpgrade.isStockConfirmed("cutting_chai"))
        assertTrue(strictAfterLegacyUpgrade.saveProduct(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1000, true, true, true)).startsWith("Product saved"))
        assertTrue(strictAfterLegacyUpgrade.quickSale(listOf("cutting_chai" to 1), "cash", commandId = "legacy-stock-unconfirmed").contains("Opening stock has not been physically confirmed"))
        assertEquals(preservedLegacyCount, strictAfterLegacyUpgrade.stockLevels()["cutting_chai"])
        strictAfterLegacyUpgrade.close()
        context.deleteDatabase("chai_tapri_counter.db")
    }

    @Test fun estimatedCostSnapshotSurvivesCatalogueEditsRestartAndV25Migration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val persistence = SqliteCounterPersistence(context)
        var model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("cost_test_tea", "Cost Test Tea", "Tea", 1500, true, true, true,
            estimatedUnitCostPaise = 425)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("cost_test_tea", 10, "synthetic cost snapshot test", "cost-test-opening").contains("10 units"))
        assertTrue(model.quickSale(listOf("cost_test_tea" to 2), "cash", commandId = "cost-test-sale").contains("Quick sale"))
        val saleBeforeEdit = model.allOrders().single().lines.single()
        assertEquals(425, saleBeforeEdit.estimatedUnitCostPaise)
        assertEquals(850L, saleBeforeEdit.estimatedCostPaise)
        val saleEvent = persistence.loadPendingSyncEvents(50).single { it.commandId == "cost-test-sale" }
        assertTrue(saleEvent.payloadJson!!.contains("\"estimatedUnitCostPaise\":425"))
        assertTrue(model.saveProduct(model.products().single { it.productId == "cost_test_tea" }.copy(estimatedUnitCostPaise = 500)).startsWith("Product saved"))
        model.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(500, model.products().single { it.productId == "cost_test_tea" }.estimatedUnitCostPaise)
        assertEquals(425, model.allOrders().single().lines.single().estimatedUnitCostPaise)
        assertEquals(3000, model.allOrders().single().salePaise)
        model.close()

        val legacy = SqliteCounterPersistence(context)
        legacy.writableDatabase.execSQL("ALTER TABLE product_catalog DROP COLUMN estimated_unit_cost_paise")
        legacy.writableDatabase.execSQL("ALTER TABLE order_lines DROP COLUMN estimated_unit_cost_paise")
        legacy.writableDatabase.version = 25
        legacy.close()

        model = CounterModel(SqliteCounterPersistence(context), requireConfiguredProducts = true)
        assertEquals(27, SqliteCounterPersistence(context).use { it.readableDatabase.version })
        assertNull(model.products().single { it.productId == "cost_test_tea" }.estimatedUnitCostPaise)
        assertNull(model.allOrders().single().lines.single().estimatedUnitCostPaise)
        assertEquals(3000, model.allOrders().single().salePaise)
        model.close()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    }

    private fun createVersionOneStore(context: android.content.Context) {
        val db = context.openOrCreateDatabase("chai_tapri_counter.db", android.content.Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE orders(order_no INTEGER PRIMARY KEY, fulfillment TEXT NOT NULL, payment TEXT NOT NULL, paid_paise INTEGER NOT NULL, sale_posted INTEGER NOT NULL, cancel_reason TEXT, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE order_lines(order_no INTEGER NOT NULL, product_id TEXT NOT NULL, name TEXT NOT NULL, quantity INTEGER NOT NULL, delivered_quantity INTEGER NOT NULL DEFAULT 0, unit_price_paise INTEGER NOT NULL, PRIMARY KEY(order_no, product_id))")
        db.execSQL("CREATE TABLE stock(product_id TEXT PRIMARY KEY, quantity INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE command_receipts(command_id TEXT PRIMARY KEY, result TEXT NOT NULL, committed_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE audit_events(event_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, event TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE stock_movements(movement_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, product_id TEXT NOT NULL, quantity_delta INTEGER NOT NULL, reason TEXT NOT NULL, order_no INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE customer_credit(credit_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, order_no INTEGER NOT NULL, amount_paise INTEGER NOT NULL, repaid_paise INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE credit_repayments(repayment_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, amount_paise INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sync_outbox(event_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL)")
        db.insertOrThrow("sync_outbox", null, ContentValues().apply { put("event_id", "legacy-command-1"); put("command_id", "legacy-command-1"); put("status", "pending"); put("created_at", 1L) })
        db.insertOrThrow("metadata", null, ContentValues().apply { put("key", "next_order_number"); put("value", "12") })
        db.insertOrThrow("orders", null, ContentValues().apply {
            put("order_no", 11); put("fulfillment", "GIVEN"); put("payment", "STAFF_DECLARED"); put("paid_paise", 1000)
            put("sale_posted", 1); put("revision", 2)
        })
        db.insertOrThrow("order_lines", null, ContentValues().apply {
            put("order_no", 11); put("product_id", "coffee"); put("name", "Coffee"); put("quantity", 1)
            put("delivered_quantity", 1); put("unit_price_paise", 1000)
        })
        listOf("cutting_chai", "chai", "samosa").forEach { product ->
            db.insertOrThrow("stock", null, ContentValues().apply { put("product_id", product); put("quantity", 100) })
        }
        db.insertOrThrow("stock", null, ContentValues().apply { put("product_id", "coffee"); put("quantity", 99) })
        db.version = 1
        db.close()
    }
}
