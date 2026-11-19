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

/** CT-02 first vertical slice: persist basic shop identity/settings in encrypted local metadata. */
class ShopSetupActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var shopName: EditText
    private lateinit var businessType: Spinner
    private lateinit var language: Spinner
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    @Volatile private var destroyed = false
    private var saving = false
    private val businessValues = listOf("chai_tapri", "paan_tapri", "tea_snacks", "cafe", "convenience")
    private val languageValues = listOf("hi-IN", "mr-IN", "en-IN")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireOwner(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 28) }
        content.addView(label("Opening encrypted shop settings…", 18f))
        setContentView(ScrollView(this).apply { addView(content) })
        RecoveryExecutor.executor.execute {
            val opened = runCatching {
                CounterModel(SqliteCounterPersistence(this)).let { store -> store to store.shopProfile() }
            }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) {
                    opened.getOrNull()?.first?.close()
                    return@runOnUiThread
                }
                opened.fold({ (store, profile) ->
                    model = store
                    renderSetup(profile)
                }, { error ->
                    content.removeAllViews()
                    content.addView(label("Shop settings could not be opened. No new store was created (${error.javaClass.simpleName}).", 16f))
                    content.addView(button("Back") { finish() })
                })
            }
        }
    }

    private fun renderSetup(profile: ShopProfile) {
        val column = content
        column.removeAllViews()

        val isConfigured = profile.shopName.isNotBlank()
        column.addView(label(if (isConfigured) "Shop Profile & Settings · CT-32" else "Shop Setup · CT-02", 26f))
        column.addView(label("Shop name, business type, preferred language, and currency are stored in the encrypted local database.", 15f))
        column.addView(label("Shop name", 16f))
        shopName = EditText(this).apply {
            hint = "For example, Shree Tea Corner"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            setText(profile.shopName)
        }
        column.addView(shopName, matchWidth())

        column.addView(label("Business type", 16f))
        businessType = spinner(listOf("Chai tapri", "Paan tapri", "Tea and snacks", "Small café", "Convenience shop"), businessValues)
        column.addView(businessType, matchWidth())
        businessType.setSelection(businessValues.indexOf(profile.businessType).coerceAtLeast(0))

        column.addView(label("Preferred language", 16f))
        language = spinner(listOf("Hindi / Hinglish", "Marathi", "English (India)"), languageValues)
        column.addView(language, matchWidth())
        language.setSelection(languageValues.indexOf(profile.languageTag).coerceAtLeast(0))

        column.addView(label("Currency", 16f))
        column.addView(label("Indian Rupee · INR", 18f))
        column.addView(label("Product prices and opening stock are configured separately. This setup does not create sales or financial records.", 14f))
        status = label("", 14f)
        column.addView(status)
        column.addView(button(if (isConfigured) "Save shop profile" else "Save shop setup") { save() })
        column.addView(button("Back to counter") { finish() })
    }

    private fun save() {
        if (saving) return
        val store = model ?: return
        val profile = ShopProfile(
            shopName = shopName.text.toString(),
            businessType = businessValues[businessType.selectedItemPosition],
            languageTag = languageValues[language.selectedItemPosition],
            currencyCode = "INR"
        )
        saving = true
        columnButtonsEnabled(false)
        status.text = "Saving shop settings…"
        RecoveryExecutor.executor.execute {
            val result = runCatching { store.saveShopProfile(profile) }
            runOnUiThread {
                saving = false
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                columnButtonsEnabled(true)
                status.text = result.getOrElse { "Shop settings could not be saved (${it.javaClass.simpleName})." }
                status.setTextColor(if (result.getOrNull()?.startsWith("Shop setup saved") == true) 0xff1b5e20.toInt() else 0xffb71c1c.toInt())
            }
        }
    }

    private fun columnButtonsEnabled(enabled: Boolean) {
        (content as? ViewGroup)?.let { root ->
            for (index in 0 until root.childCount) (root.getChildAt(index) as? Button)?.isEnabled = enabled
        }
    }

    private fun spinner(labels: List<String>, values: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ShopSetupActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        contentDescription = "Choose from ${values.size} supported options"
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 16, 0, 12)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        minHeight = 56
        setOnClickListener { action() }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        destroyed = true
        model?.let { store -> RecoveryExecutor.executor.execute { store.close() } }
        model = null
        super.onDestroy()
    }
}
